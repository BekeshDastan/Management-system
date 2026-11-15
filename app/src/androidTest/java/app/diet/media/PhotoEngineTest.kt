package app.diet.media

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.DietDatabase
import app.diet.data.DietRepository
import app.diet.data.DraftEntity
import app.diet.data.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class PhotoEngineTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var db: DietDatabase
    private lateinit var repo: DietRepository
    private lateinit var engine: PhotoEngine
    private lateinit var ownedRoot: File
    @Before fun setup(): Unit = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        ownedRoot = File(base.cacheDir, "photo-instrumentation-${newId()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = File(ownedRoot, "no-backup").apply { mkdirs() }
            override fun getCacheDir() = File(ownedRoot, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        db = DietDatabase.open(context, "fixture.db")
        repo = DietRepository(context, scope, Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC), db)
        repo.initialize(); engine = PhotoEngine(context, repo, scope)
    }
    @After fun close(): Unit = runBlocking {
        // Cancellation is cooperative: join the Room observer before closing its native database.
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::db.isInitialized) db.close()
        if (::ownedRoot.isInitialized) {
            val cache = ApplicationProvider.getApplicationContext<Context>().cacheDir.canonicalFile
            check(ownedRoot.canonicalFile.parentFile == cache && ownedRoot.name.startsWith("photo-instrumentation-"))
            check(ownedRoot.deleteRecursively())
        }
    }
    private suspend fun draft(date: String = "2026-11-15") = repo.checkpointDraft(DraftEntity(kind = "moment", selectedLocalDate = date, rawPayload = "{\"note\":\"synthetic\"}", updatedAtMillis = 0), null)
    private fun pattern(name: String = "source.jpg", transparent: Boolean = false): File {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); val paint = Paint()
        if (!transparent) {
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { i, color ->
                paint.color = color; val left = (i % 2) * 60f; val top = (i / 2) * 40f; canvas.drawRect(left, top, left + 60, top + 40, paint)
            }
        }
        val file = File(ownedRoot, name)
        file.outputStream().use { bitmap.compress(if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle(); return file
    }
    private fun withOrientation(original: File, value: Int): File {
        val exif = byteArrayOf(69,120,105,102,0,0,73,73,42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,value.toByte(),0,0,0,0,0,0,0)
        val file = File(ownedRoot, "orientation-$value.jpg")
        original.inputStream().use { input -> file.outputStream().use { out ->
            assertEquals(0xff, input.read()); assertEquals(0xd8, input.read()); out.write(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xe1.toByte(),0,(exif.size+2).toByte())); out.write(exif); input.copyTo(out)
        } }
        return file
    }
    private fun closestColor(color: Int): Int = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).minBy { expected ->
        val dr = Color.red(color) - Color.red(expected); val dg = Color.green(color) - Color.green(expected); val db = Color.blue(color) - Color.blue(expected); dr*dr+dg*dg+db*db
    }

    @Test fun allExifOrientationsAreAppliedExactlyOnceAndMastersNeedNoSource(): Unit = runBlocking {
        val base = pattern()
        val expected = listOf(
            listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW), listOf(Color.GREEN,Color.RED,Color.YELLOW,Color.BLUE),
            listOf(Color.YELLOW,Color.BLUE,Color.GREEN,Color.RED), listOf(Color.BLUE,Color.YELLOW,Color.RED,Color.GREEN),
            listOf(Color.RED,Color.BLUE,Color.GREEN,Color.YELLOW), listOf(Color.BLUE,Color.RED,Color.YELLOW,Color.GREEN),
            listOf(Color.YELLOW,Color.GREEN,Color.BLUE,Color.RED), listOf(Color.GREEN,Color.YELLOW,Color.RED,Color.BLUE))
        for (orientation in 1..8) {
            val input = withOrientation(base, orientation)
            val d = draft("2026-11-${orientation.toString().padStart(2,'0')}")
            engine.import(d.id, d.selectedLocalDate!!, listOf(Uri.fromFile(input)))
            assertTrue(input.delete())
            val asset = repo.media.draftAssets(d.id).single()
            assertEquals(if (orientation <= 4) 120 else 80, asset.width)
            assertEquals(if (orientation <= 4) 80 else 120, asset.height)
            assertEquals("READY", asset.state)
            assertEquals("${asset.id}.jpg", asset.objectKey)
            engine.decodeViewer(asset.id).use { handle ->
                val b = handle.bitmap
                val corners = listOf(b.getPixel(b.width/4,b.height/4),b.getPixel(3*b.width/4,b.height/4),b.getPixel(b.width/4,3*b.height/4),b.getPixel(3*b.width/4,3*b.height/4)).map(::closestColor)
                assertEquals("EXIF $orientation", expected[orientation-1], corners)
            }
            val master = File(context.noBackupFilesDir, "diet/media/objects/${asset.objectKey}")
            engine.validateMaster(master, asset.width, asset.height)
            assertTrue(master.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        }
    }
    @Test fun transparentInputBecomesWhiteOpaqueSrgbJpeg(): Unit = runBlocking {
        val d = draft(); engine.import(d.id, d.selectedLocalDate!!, listOf(Uri.fromFile(pattern("transparent.png", true))))
        val asset = repo.media.draftAssets(d.id).single()
        engine.decodeViewer(asset.id).use { handle ->
            val pixel = handle.bitmap.getPixel(10,10)
            assertTrue(Color.red(pixel)>245&&Color.green(pixel)>245&&Color.blue(pixel)>245)
            assertEquals(255, Color.alpha(pixel))
        }
    }
    @Test fun nativeSrgbJpegMetadataIsRecognizedAndPrivateSegmentsRejected() {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
        bitmap.eraseColor(Color.WHITE)
        val fixture = File(ownedRoot, "native-srgb-encoder.jpg")
        fixture.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
        bitmap.recycle()
        val markers = mutableListOf<String>()
        RandomAccessFile(fixture, "r").use { input ->
            assertEquals(0xffd8, input.readUnsignedShort())
            while (input.filePointer < input.length()) {
                assertEquals(0xff, input.readUnsignedByte())
                val marker = input.readUnsignedByte(); if (marker == 0xda || marker == 0xd9) break
                val size = input.readUnsignedShort() - 2; val start = input.filePointer
                if (marker in 0xe0..0xef || marker == 0xfe) {
                    val prefix = ByteArray(minOf(14, size)); input.readFully(prefix)
                    markers += "%02x:%d:%s".format(marker, size, prefix.joinToString("") { "%02x".format(it) })
                }
                input.seek(start + size)
            }
        }
        // Only generated fixture segment IDs/sizes/magic bytes, never gallery bytes or real metadata.
        android.util.Log.i("DietSyntheticJPEG", "API ${android.os.Build.VERSION.SDK_INT}: $markers")
        try { engine.validateMaster(fixture, 16, 12) }
        catch (error: Exception) { throw AssertionError("Native synthetic sRGB JPEG metadata: $markers", error) }
        val original = fixture.readBytes()
        for ((marker, payload) in listOf(0xe1 to "Exif\u0000\u0000synthetic-gps", 0xe2 to "MPF\u0000synthetic-gainmap", 0xed to "Photoshop synthetic", 0xe0 to "JFXX\u0000synthetic-thumbnail")) {
            val bytes = payload.toByteArray(Charsets.US_ASCII); val rejected = File(ownedRoot, "private-$marker.jpg")
            rejected.outputStream().use { output ->
                output.write(original, 0, 2)
                output.write(byteArrayOf(0xff.toByte(), marker.toByte(), ((bytes.size + 2) shr 8).toByte(), (bytes.size + 2).toByte()))
                output.write(bytes); output.write(original, 2, original.size - 2)
            }
            assertTrue("Private segment ${marker.toString(16)} must be rejected", runCatching { engine.validateMaster(rejected, 16, 12) }.isFailure)
        }
    }
    @Test fun badSecondFilePublishesNoneAndKeepsOriginalRawDraft(): Unit = runBlocking {
        val d = draft(); val invalid = File(ownedRoot, "broken.jpg").apply { writeText("not a picture") }
        val failure = runCatching { engine.import(d.id, d.selectedLocalDate!!, listOf(Uri.fromFile(pattern()),Uri.fromFile(invalid))) }
        assertTrue(failure.isFailure)
        assertTrue(repo.media.draftAssets(d.id).isEmpty())
        assertEquals(d.rawPayload, repo.freshDraft(d.id)?.rawPayload)
        assertEquals(d.draftRevision, repo.freshDraft(d.id)?.draftRevision)
        assertTrue(repo.state.value.photos.isEmpty())
    }
    @Test fun oversizeActualStreamAndUnknownMimeAreRejectedWithoutFacts(): Unit = runBlocking {
        val d = draft()
        val big = File(ownedRoot, "oversize.jpg")
        big.outputStream().use { out -> repeat(5 * 1024) { out.write(ByteArray(1024)) }; out.write(0) }
        assertTrue(runCatching { engine.import(d.id, d.selectedLocalDate!!, listOf(Uri.fromFile(big))) }.isFailure)
        assertTrue(repo.media.draftAssets(d.id).isEmpty()); assertTrue(repo.state.value.photos.isEmpty())
    }
    @Test fun cancellationAfterNativeReturnCannotPublishLateBatch(): Unit = runBlocking {
        val d = draft()
        engine.phaseHook = { phase -> if (phase == "after-asset-prepared") { engine.phaseHook = null; engine.cancelImport() } }
        assertTrue(runCatching { engine.import(d.id, d.selectedLocalDate!!, listOf(Uri.fromFile(pattern()))) }.isFailure)
        assertTrue(repo.media.draftAssets(d.id).isEmpty()); assertTrue(repo.state.value.photos.isEmpty())
        assertEquals(d.rawPayload, repo.freshDraft(d.id)?.rawPayload)
    }
    @Test fun cancellationAfterPublishedCheckpointPreservesReadyDraftAndMaster(): Unit = runBlocking {
        val d = draft()
        engine.phaseHook = { phase -> if (phase == "after-batch-published") { engine.phaseHook = null; engine.cancelImport() } }
        assertTrue(runCatching { engine.import(d.id, d.selectedLocalDate!!, listOf(Uri.fromFile(pattern()))) }.isFailure)
        val asset = repo.media.draftAssets(d.id).single()
        assertEquals("READY", asset.state)
        assertTrue(File(context.noBackupFilesDir, "diet/media/objects/${asset.objectKey}").isFile)
        assertTrue(repo.freshDraft(d.id)!!.draftRevision > d.draftRevision)
        assertEquals(d.rawPayload, repo.freshDraft(d.id)?.rawPayload)
        assertEquals(0L, repo.media.reservedBytes())
    }
    @Test fun failedFsyncNeverCreatesReadyReference(): Unit = runBlocking {
        val d = draft()
        engine.phaseHook = { phase -> if (phase == "before-file-sync") { engine.phaseHook = null; throw IOException("Synthetic fsync failure") } }
        assertTrue(runCatching { engine.import(d.id, d.selectedLocalDate!!, listOf(Uri.fromFile(pattern()))) }.isFailure)
        assertTrue(repo.media.draftAssets(d.id).isEmpty()); assertEquals(d.draftRevision, repo.freshDraft(d.id)?.draftRevision)
    }
    @Test fun directoryFailureAfterBatchAllocationAbortsAndReleasesReserve(): Unit = runBlocking {
        val d = draft()
        var allocated = false
        engine.phaseHook = { phase ->
            if (phase == "after-batch-allocated") allocated = true
            if (allocated && phase == "before-directory-sync") { engine.phaseHook = null; throw IOException("Synthetic directory sync failure") }
        }
        assertTrue(runCatching { engine.import(d.id, d.selectedLocalDate!!, listOf(Uri.fromFile(pattern()))) }.isFailure)
        assertTrue(repo.media.draftAssets(d.id).isEmpty())
        assertEquals(0L, repo.media.reservedBytes())
        assertTrue(repo.media.batches().none { it.status == "PREPARING" })
    }
    @Test fun garbageCollectionRespectsReadLeaseAndPreservesUnknownObjects(): Unit = runBlocking {
        val d = draft(); engine.import(d.id, d.selectedLocalDate!!, listOf(Uri.fromFile(pattern())))
        val asset = repo.media.draftAssets(d.id).single()
        val master = File(context.noBackupFilesDir, "diet/media/objects/${asset.objectKey}")
        val unknown = File(master.parentFile, "unrecognized.bin").apply { writeText("synthetic unknown resource") }
        val (_, lease) = repo.media.acquireRead(asset.id, engine.sessionId)
        repo.discardDraft(d.id, repo.freshDraft(d.id)!!.draftRevision)
        engine.recoverAndClean()
        assertTrue(master.isFile); assertTrue(unknown.isFile)
        repo.media.releaseRead(lease.id)
        engine.recoverAndClean()
        assertFalse(master.exists()); assertTrue(unknown.isFile)
        assertNull(repo.media.assetByObjectKey(asset.objectKey))
    }
    @Test fun directDraftPhotoInputsHaveStableIdsAndPreserveExistingCaptions(): Unit = runBlocking {
        val d = draft(); engine.import(d.id, d.selectedLocalDate!!, listOf(Uri.fromFile(pattern())))
        val inputs = repo.media.draftPhotoInputs(d.id)
        assertEquals(inputs, repo.media.draftPhotoInputs(d.id))
        val captioned = inputs.single().copy(caption = "Синтетическая подпись")
        val ready = repo.freshDraft(d.id)!!
        repo.saveMomentWithPhotos(d.selectedLocalDate!!, null, "", listOf(captioned), null, null, app.diet.data.Operation(draftId = d.id, draftRevision = ready.draftRevision))
        val edit = draft()
        repo.media.attachExisting(edit.id, edit.mediaRevision, listOf(captioned.assetId))
        assertEquals(listOf(captioned), repo.media.draftPhotoInputs(edit.id))
    }
}
