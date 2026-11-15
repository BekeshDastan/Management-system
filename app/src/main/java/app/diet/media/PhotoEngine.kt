package app.diet.media

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.CancellationSignal
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.diet.data.DietRepository
import app.diet.data.DraftEntity
import app.diet.data.MediaAssetEntity
import app.diet.data.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

sealed class PhotoFailure(message: String) : IOException(message) {
    class Format : PhotoFailure("Файл повреждён или имеет неподдерживаемый формат. Выберите JPEG, PNG или WebP.")
    class Size : PhotoFailure("Фото превышает допустимые размеры или 5 МиБ.")
    class Memory : PhotoFailure("Недостаточно памяти для фото. Закройте просмотр и повторите.")
    class Space : PhotoFailure("Недостаточно свободного места для безопасной подготовки фото.")
    class Missing : PhotoFailure("Сохранённое фото недоступно или повреждено.")
    internal class OutputOverflow : PhotoFailure("Закодированное фото превышает допустимый размер.")
}

internal class LimitedOutput(out: OutputStream, private val limit: Long) : FilterOutputStream(out) {
    var count = 0L; private set
    var overflowed = false; private set
    override fun write(b: Int) { if (count >= limit) { overflowed = true; throw PhotoFailure.OutputOverflow() }; out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { if (len.toLong() > limit - count) { overflowed = true; throw PhotoFailure.OutputOverflow() }; out.write(b, off, len); count += len }
}

/** No URI, provider filename, EXIF or pixel payload is persisted in the database. */
class PhotoEngine(private val context: Context, private val repository: DietRepository, private val scope: CoroutineScope) {
    /** Instrumentation-only fault/cancellation seam; never receives user values or paths. */
    internal var phaseHook: ((String) -> Unit)? = null
    val sessionId: String = PROCESS_SESSION
    private val generation = AtomicLong(0)
    @Volatile private var importJob: Job? = null
    @Volatile private var resolverSignal: CancellationSignal? = null
    @Volatile private var activeInput: InputStream? = null
    private val root = File(context.noBackupFilesDir, "diet").canonicalFile
    private val objects = File(root, "media/objects")
    private val staging = File(root, "staging")
    private val thumbnails = File(context.cacheDir, "diet-media")
    private val quarantine = File(root, "metadata/quarantine")
    private val budget: PhotoBudget by lazy {
        val am = context.getSystemService(ActivityManager::class.java)
        PhotoBudget.forMemoryClass(am.memoryClass, am.isLowRamDevice)
    }
    private val memoryCache by lazy { object : LruCache<String, Bitmap>(budget.cache.toInt()) { override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount } }

    private fun directory(file: File) {
        if (file.isDirectory) return
        val missing = mutableListOf<File>(); var current: File? = file
        while (current != null && !current.exists()) { missing += current; current = current.parentFile }
        check(file.mkdirs()) { "Не удалось создать каталог фото" }
        // A durable child file also requires durable directory entries up to the existing ancestor.
        for (created in missing.asReversed()) { syncDirectory(created); syncDirectory(requireNotNull(created.parentFile)) }
    }
    private fun assetFile(asset: MediaAssetEntity): File {
        require(asset.objectKey == "${asset.id}.jpg" && UUID_PATTERN.matches(asset.id))
        val file = File(objects, asset.objectKey).canonicalFile
        require(file.parentFile == objects.canonicalFile)
        return file
    }
    private fun batchDirectory(id: String): File { require(UUID_PATTERN.matches(id)); return File(staging, id).canonicalFile.also { require(it.parentFile == staging.canonicalFile) } }

    /** Caller keeps the form alive. Callback success means a durable draft checkpoint, not a saved day. */
    suspend fun import(draftId: String, selectedDate: String, uris: List<Uri>): DraftEntity = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext repository.freshDraft(draftId) ?: error("Черновик недоступен")
        require(uris.size in 1..4)
        val job = currentCoroutineContext()[Job]!!
        val ticket = synchronized(this@PhotoEngine) {
            if (importJob?.isActive == true) throw IOException("Добавление фото уже выполняется")
            importJob = job
            generation.incrementAndGet()
        }
        try {
            HEAVY.withLock {
                checkToken(ticket, job)
                budget // Fail before disk/metadata allocation on unsupported memory class.
                directory(objects); directory(staging); directory(thumbnails); directory(quarantine)
                cleanKnownResources()
                val outstanding = repository.media.reservedBytes()
                if (root.usableSpace < Math.addExact(RESERVE + MIN_FREE, outstanding)) throw PhotoFailure.Space()
                val draft = repository.freshDraft(draftId) ?: error("Черновик недоступен")
                require(draft.selectedLocalDate == selectedDate) { "Дата редактора изменилась" }
                val (batch, assets) = repository.media.beginImport(draftId, draft.mediaRevision, uris.size, sessionId)
                val dir = batchDirectory(batch.id)
                try {
                    phaseHook?.invoke("after-batch-allocated")
                    directory(dir); syncDirectory(staging)
                    for ((i, uri) in uris.withIndex()) {
                        checkToken(ticket, job)
                        val raw = File(dir, "source.part")
                        val output = File(dir, "master.tmp")
                        copyBounded(uri, raw, job)
                        checkToken(ticket, job)
                        val encoded = encode(raw, output, 2048, 85, MAX_FILE, job)
                        checkToken(ticket, job)
                        val asset = assets[i]; val destination = assetFile(asset)
                        require(!destination.exists()) { "Назначение фото уже существует" }
                        Os.rename(output.path, destination.path)
                        syncDirectory(objects); syncDirectory(dir)
                        val hash = sha256(destination)
                        repository.media.prepared(batch.id, batch.generation, asset.id, encoded.first, encoded.second, destination.length(), hash, "jpeg85-srgb-${maxOf(encoded.first, encoded.second)}")
                        phaseHook?.invoke("after-asset-prepared")
                        if (!raw.delete()) throw IOException("Не удалось освободить временный исходник")
                        syncDirectory(dir)
                    }
                    checkToken(ticket, job)
                    phaseHook?.invoke("before-publish")
                    checkToken(ticket, job)
                    repository.media.publishBatch(batch.id, batch.generation).also {
                        phaseHook?.invoke("after-batch-published")
                        checkToken(ticket, job)
                        // No master move is needed after the durable reference has been published.
                        runCatching { if (dir.listFiles()?.isEmpty() == true && dir.delete()) syncDirectory(staging) }
                    }
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        repository.media.abortImport(batch.id, if (error is CancellationException) "CANCELLED" else "IMPORT_FAILED")
                        cleanBatchTemporary(batch.id)
                        // This non-cancellable section is bounded to this batch's at most four files.
                        assets.forEach { cleanAsset(it.id) }
                        repository.media.releaseBatchReserve(batch.id)
                    }
                    if (error is OutOfMemoryError) throw PhotoFailure.Memory()
                    throw error
                }
            }
        } finally {
            resolverSignal = null; activeInput = null
            if (importJob === job) importJob = null
        }
    }

    /** Cancels owned IO; the heavy mutex is held until a native codec actually returns. */
    fun cancelImport() {
        generation.incrementAndGet(); importJob?.cancel()
        val signal = resolverSignal; val input = activeInput
        scope.launch(Dispatchers.IO) { signal?.cancel(); runCatching { input?.close() } }
    }
    private fun checkToken(ticket: Long, job: Job) { job.ensureActive(); if (generation.get() != ticket) throw CancellationException("Import superseded") }

    private fun copyBounded(uri: Uri, destination: File, job: Job) {
        val signal = CancellationSignal(); resolverSignal = signal
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r", signal) ?: throw PhotoFailure.Missing()
        afd.use { descriptor ->
            descriptor.createInputStream().use { input ->
                activeInput = input
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(32 * 1024); var total = 0L
                    while (true) {
                        job.ensureActive()
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_FILE - total + 1).toInt())
                        if (read == -1) break
                        total += read
                        if (total > MAX_FILE) throw PhotoFailure.Size()
                        output.write(buffer, 0, read)
                    }
                    if (total == 0L) throw PhotoFailure.Format()
                    output.flush(); phaseHook?.invoke("before-file-sync"); output.fd.sync()
                }
            }
        }
        activeInput = null; resolverSignal = null
        syncDirectory(destination.parentFile!!)
    }

    private fun verifySignature(file: File): String {
        if (file.length() !in 1..MAX_FILE) throw PhotoFailure.Size()
        val prefix = ByteArray(12); val read = file.inputStream().use { it.read(prefix) }
        return when {
            read >= 3 && prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte() && prefix[2] == 0xff.toByte() -> "image/jpeg"
            read >= 8 && prefix.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
            read >= 12 && String(prefix, 0, 4, Charsets.US_ASCII) == "RIFF" && String(prefix, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> throw PhotoFailure.Format()
        }
    }
    private fun decode(source: File, profile: Int, job: Job): Bitmap {
        val mime = verifySignature(source)
        memoryCache.evictAll()
        var expectedW = 0; var expectedH = 0
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            job.ensureActive()
            if (info.mimeType != mime) throw PhotoFailure.Format()
            val width = info.size.width; val height = info.size.height
            if (width !in 1..32768 || height !in 1..32768 || width.toLong() * height > 64_000_000) throw PhotoFailure.Size()
            val chosen = budget.candidates(width, height, liveBytes(), profile).firstOrNull() ?: throw PhotoFailure.Memory()
            expectedW = chosen.first; expectedH = chosen.second
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSize(expectedW, expectedH)
            decoder.setOnPartialImageListener { false }
        }
        if (bitmap.width != expectedW || bitmap.height != expectedH || bitmap.config == Bitmap.Config.HARDWARE || bitmap.allocationByteCount.toLong() > 8L * expectedW * expectedH || !budget.fits(expectedW, expectedH, liveBytes())) {
            bitmap.recycle(); throw PhotoFailure.Memory()
        }
        return bitmap
    }

    private fun encode(source: File, output: File, maxLong: Int, quality: Int, limit: Long, job: Job): Pair<Int, Int> {
        val attempted = mutableSetOf<Pair<Int, Int>>()
        var previousLong = Int.MAX_VALUE
        for (profile in PhotoBudget.PROFILES.map { minOf(it, maxLong) }.distinct()) {
            if (profile >= previousLong) continue
            job.ensureActive()
            if (output.exists() && !output.delete()) throw IOException("Не удалось очистить незавершённое фото")
            var decoded: Bitmap? = null; var canvasBitmap: Bitmap? = null
            try {
                decoded = decode(source, profile, job)
                val size = decoded.width to decoded.height
                previousLong = maxOf(size.first, size.second)
                if (!attempted.add(size)) continue
                job.ensureActive()
                if (decoded.allocationByteCount.toLong() + 4L * decoded.width * decoded.height + budget.codecReserve + budget.io + liveBytes() > budget.total) throw PhotoFailure.Memory()
                canvasBitmap = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
                if (canvasBitmap.colorSpace?.isSrgb != true || canvasBitmap.hasAlpha()) throw PhotoFailure.Format()
                if (canvasBitmap.allocationByteCount.toLong() + decoded.allocationByteCount + budget.codecReserve + budget.io + liveBytes() > budget.total) throw PhotoFailure.Memory()
                Canvas(canvasBitmap).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
                job.ensureActive()
                FileOutputStream(output).use { stream ->
                    val limited = LimitedOutput(stream, limit)
                    val success = try { canvasBitmap.compress(Bitmap.CompressFormat.JPEG, quality, limited) } catch (e: Exception) { if (limited.overflowed) throw PhotoFailure.OutputOverflow() else throw e }
                    if (limited.overflowed) throw PhotoFailure.OutputOverflow()
                    if (!success) throw IOException("Не удалось закодировать фото")
                    limited.flush(); stream.fd.sync()
                }
                job.ensureActive()
                validateMaster(output, size.first, size.second, limit)
                syncDirectory(output.parentFile!!)
                return size
            } catch (overflow: PhotoFailure.OutputOverflow) {
                if (output.exists() && !output.delete()) throw IOException("Не удалось очистить слишком большой результат", overflow)
                // Only encoded overflow permits a smaller profile; IO/decode/OOM do not retry.
            } finally {
                canvasBitmap?.recycle(); decoded?.recycle()
            }
        }
        throw PhotoFailure.OutputOverflow()
    }

    internal fun validateMaster(file: File, width: Int, height: Int, limit: Long = MAX_FILE) {
        if (file.length() !in 1..limit || verifySignature(file) != "image/jpeg") throw PhotoFailure.Format()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth != width || options.outHeight != height || options.outMimeType != "image/jpeg") throw PhotoFailure.Format()
        RandomAccessFile(file, "r").use { input ->
            input.seek(file.length() - 2); if (input.readUnsignedShort() != 0xffd9) throw PhotoFailure.Format()
            input.seek(2)
            var iccSeen = false
            while (input.filePointer < file.length()) {
                if (input.readUnsignedByte() != 0xff) throw PhotoFailure.Format()
                var marker = input.readUnsignedByte(); while (marker == 0xff) marker = input.readUnsignedByte()
                if (marker == 0xda || marker == 0xd9) break
                val length = input.readUnsignedShort(); if (length < 2 || input.filePointer + length - 2 > file.length()) throw PhotoFailure.Format()
                val payloadStart = input.filePointer; val payloadSize = length - 2
                fun ascii(count: Int) = ByteArray(count).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                when (marker) {
                    0xe0 -> {
                        // JFIF density is harmless; embedded JFIF/JFXX thumbnails are not allowed.
                        if (payloadSize != 14 || ascii(5) != "JFIF\u0000") throw PhotoFailure.Format()
                        input.seek(payloadStart + 12)
                        if (input.readUnsignedByte() != 0 || input.readUnsignedByte() != 0) throw PhotoFailure.Format()
                    }
                    0xe2 -> {
                        // Android's encoder generates ICC_PROFILE for the new named-sRGB canvas.
                        // Permit only that complete ICC container, never APP2 MPF/gainmap metadata.
                        if (iccSeen || payloadSize < 142 || ascii(12) != "ICC_PROFILE\u0000" || input.readUnsignedByte() != 1 || input.readUnsignedByte() != 1) throw PhotoFailure.Format()
                        if ((input.readInt().toLong() and 0xffffffffL) != payloadSize - 14L) throw PhotoFailure.Format()
                        input.seek(payloadStart + 30); if (ascii(4) != "RGB ") throw PhotoFailure.Format()
                        input.seek(payloadStart + 50); if (ascii(4) != "acsp") throw PhotoFailure.Format()
                        iccSeen = true
                    }
                    0xee -> if (payloadSize != 12 || ascii(5) != "Adobe") throw PhotoFailure.Format()
                    in 0xe1..0xed, 0xef, 0xfe -> throw PhotoFailure.Format()
                }
                input.seek(payloadStart + payloadSize)
            }
        }
    }

    suspend fun recoverAndClean() = withContext(Dispatchers.IO) { HEAVY.withLock {
        directory(objects); directory(staging); directory(thumbnails); directory(quarantine)
        repository.media.recoverPreviousProcess(sessionId)
        cleanKnownResources()
        var after = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = repository.media.assetsPage(after); if (page.isEmpty()) break
            page.filter { it.state == "READY" }.forEach { asset -> if (!assetFile(asset).isFile) repository.media.markIntegrity(asset.id, "MISSING") }
            after = page.last().id
        }
        // Unknown objects are preserved and inventoried, never deleted based on age/name alone.
        java.nio.file.Files.newDirectoryStream(objects.toPath()).use { paths ->
            for (unknown in paths) {
                currentCoroutineContext().ensureActive()
                if (repository.media.assetByObjectKey(unknown.fileName.toString()) != null) continue
                val key = MessageDigest.getInstance("SHA-256").digest(unknown.fileName.toString().toByteArray()).joinToString("") { "%02x".format(it) }
                val marker = File(quarantine, "$key.unknown")
                if (!marker.exists()) FileOutputStream(marker).use { out -> out.write("Unknown private object; retained for diagnosis.".toByteArray()); out.fd.sync() }
            }
        }
        trimDiskCache()
    } }

    private suspend fun cleanKnownResources() {
        var after = ""
        while (true) {
            val page = repository.media.unownedPage(after); if (page.isEmpty()) break
            for (candidate in page) cleanAsset(candidate.id)
            after = page.last().id
        }
        after = ""
        while (true) {
            val page = repository.media.cleanupBatchesPage(after); if (page.isEmpty()) break
            for (batch in page) { cleanBatchTemporary(batch.id); repository.media.releaseBatchReserve(batch.id) }
            after = page.last().id
        }
    }
    private suspend fun cleanAsset(id: String) {
        val claimed = repository.media.claimCleanup(id) ?: return
        val file = assetFile(claimed)
        if (file.exists() && !file.delete()) throw IOException("Не удалось завершить удаление фото")
        syncDirectory(objects)
        repository.media.finishCleanup(claimed.id, claimed.generation)
    }
    private fun cleanBatchTemporary(id: String) {
        val dir = batchDirectory(id)
        if (!dir.exists()) return
        listOf("source.part", "master.tmp").forEach { name -> val file = File(dir, name); if (file.exists() && !file.delete()) throw IOException("Не удалось очистить временный файл") }
        syncDirectory(dir)
        if (dir.listFiles()?.isEmpty() == true) { if (!dir.delete()) throw IOException("Не удалось очистить каталог импорта"); syncDirectory(staging) }
    }

    private suspend fun bitmap(assetId: String, maxLong: Int): Bitmap = withContext(Dispatchers.IO) { HEAVY.withLock {
        val cacheKey = "$assetId:$maxLong"
        val (asset, lease) = repository.media.acquireRead(assetId, sessionId)
        try {
            memoryCache.get(cacheKey)?.let { return@withLock it }
            val master = assetFile(asset)
            if (!master.isFile || master.length() != asset.byteCount || sha256(master) != asset.sha256) {
                repository.media.markIntegrity(assetId, if (!master.isFile) "MISSING" else "CORRUPT")
                throw PhotoFailure.Missing()
            }
            val job = currentCoroutineContext()[Job]!!
            val source = if (maxLong <= 320) {
                directory(thumbnails); val thumb = File(thumbnails, "$assetId.jpg")
                if (!thumb.exists()) {
                    val tmp = File(thumbnails, "$assetId.tmp")
                    try { encode(master, tmp, 320, 75, 262_144, job); Os.rename(tmp.path, thumb.path); syncDirectory(thumbnails); trimDiskCache() }
                    catch (e: CancellationException) { tmp.delete(); throw e }
                    catch (_: Exception) { tmp.delete() } // A derivative is optional; master remains intact.
                }
                if (thumb.exists()) thumb else master
            } else master
            val decoded = try { decode(source, maxLong, job) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (source != master) { source.delete(); decode(master, maxLong, job) } else throw e
            }
            try { job.ensureActive(); register(decoded); memoryCache.put(cacheKey, decoded) } catch (e: Throwable) { decoded.recycle(); throw e }
            if (source != master) source.setLastModified(System.currentTimeMillis())
            decoded
        } finally { withContext(NonCancellable) { repository.media.releaseRead(lease.id) } }
    } }
    class BitmapHandle internal constructor(bitmap: Bitmap) : Closeable {
        private var owned: Bitmap? = bitmap
        val bitmap: Bitmap get() = checkNotNull(owned)
        override fun close() { owned = null } // UI may still hold a rendering reference; never recycle it under Canvas.
    }
    suspend fun decodeViewer(assetId: String, viewportLong: Int = 2048): BitmapHandle = BitmapHandle(bitmap(assetId, viewportLong.coerceIn(1, 2048)))

    @Composable fun Thumbnail(assetId: String, modifier: Modifier = Modifier, description: String = "Фото дня") {
        val loaded by produceState<Result<Bitmap>?>(null, assetId) {
            value = try { Result.success(bitmap(assetId, 320)) } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(e) }
        }
        val image = loaded?.getOrNull()
        if (image != null) Image(image.asImageBitmap(), description, modifier, contentScale = ContentScale.Crop)
        else Box(modifier) { Text(if (loaded == null) "Загрузка фото…" else "Фото недоступно", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    private fun trimDiskCache() {
        val files = thumbnails.listFiles()?.filter { it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".tmp")) }?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        for (file in files) { if (bytes <= 128 * PhotoBudget.MIB) break; val size = file.length(); if (file.delete()) bytes -= size }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun syncDirectory(dir: File) { val fd = Os.open(dir.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0); try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) { "Ожидался каталог" }; phaseHook?.invoke("before-directory-sync"); Os.fsync(fd) } finally { Os.close(fd) } }

    companion object {
        private const val MAX_FILE = 5_242_880L
        private const val RESERVE = 32 * PhotoBudget.MIB
        private const val MIN_FREE = 128 * PhotoBudget.MIB
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val HEAVY = Mutex()
        private val PROCESS_SESSION = newId()
        private val live = mutableListOf<WeakReference<Bitmap>>()
        @Synchronized private fun register(bitmap: Bitmap) { live.removeAll { it.get() == null || it.get()?.isRecycled == true }; if (live.none { it.get() === bitmap }) live += WeakReference(bitmap) }
        @Synchronized private fun liveBytes(): Long { live.removeAll { it.get() == null || it.get()?.isRecycled == true }; return live.sumOf { it.get()?.allocationByteCount?.toLong() ?: 0 } }
    }
}
