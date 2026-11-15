package app.diet.media

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.diet.data.DietDatabase
import app.diet.data.DietRepository
import app.diet.data.DraftEntity
import app.diet.data.MediaAssetEntity
import app.diet.data.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native, synthetic codec fixtures only: this does not represent a phone camera/HDR display test. */
@RunWith(AndroidJUnit4::class)
class PhotoColorIntegrationTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var database: DietDatabase
    private lateinit var repository: DietRepository
    private lateinit var engine: PhotoEngine

    @Before fun setup(): Unit = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "color-codec-fixture-${newId()}").apply { check(mkdirs()) }
        context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = DietDatabase.open(context, "color.db")
        repository = DietRepository(context, scope, Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC), database)
        repository.initialize()
        engine = PhotoEngine(context, repository, scope)
    }

    @After fun cleanup(): Unit = runBlocking {
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::database.isInitialized) database.close()
        if (::root.isInitialized) {
            val cache = ApplicationProvider.getApplicationContext<Context>().cacheDir.canonicalFile
            check(root.canonicalFile.parentFile == cache && root.name.startsWith("color-codec-fixture-"))
            check(root.deleteRecursively())
        }
    }

    @Test fun wideGamutSixteenBitPngBecomesOpaqueEightBitSrgbJpeg(): Unit = runBlocking {
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val warmP3 = Color.pack(0.7f, 0.4f, 0.2f, 1f, p3)
        val nativePng = File(root, "native-p3.png")
        val source = File(root, "wide-f16.png")
        val input = Bitmap.createBitmap(128, 96, Bitmap.Config.RGBA_F16, true, p3)
        try {
            input.eraseColor(Color.TRANSPARENT)
            Canvas(input).drawRect(32f, 0f, 128f, 96f, Paint().apply { setColor(warmP3) })
            nativePng.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { input.recycle() }

        // API29's native PNG encoder quantizes F16 to eight bits. Keep its real P3 ICC profile,
        // but write a small, true 16-bit RGBA PNG so the native decoder actually exercises F16.
        writeSixteenBitPng(source, nativeIccProfile(nativePng), warmP3)
        assertTrue(nativePng.delete())
        RandomAccessFile(source, "r").use { png ->
            assertEquals(0x89504e470d0a1a0aUL.toLong(), png.readLong())
            png.seek(24)
            assertEquals("The encoded fixture must contain 16-bit components", 16, png.readUnsignedByte())
        }
        val sourceDecoded = decodeSoftware(source)
        try {
            assertEquals(Bitmap.Config.RGBA_F16, sourceDecoded.config)
            assertTrue(sourceDecoded.colorSpace?.isWideGamut == true)
            assertFalse(sourceDecoded.colorSpace?.isSrgb == true)
            assertEquals(0, Color.alpha(sourceDecoded.getPixel(16, 48)))
        } finally { recycleOwned(sourceDecoded) }

        val (_, master) = imported(source)
        val output = decodeSoftware(master)
        try {
            assertSdrMaster(output, 128, 96)
            assertRgbNear(Color.WHITE, output.getPixel(16, 48), 3)
            // This P3 colour is within the sRGB gamut but needs a real colour conversion.
            val expected = Color.toArgb(Color.convert(warmP3, ColorSpace.get(ColorSpace.Named.SRGB)))
            assertRgbNear(expected, output.getPixel(80, 48), 6)
        } finally { recycleOwned(output) }
    }

    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun nativeGainmapJpegLosesAuxiliaryImageAndMetadataInFreshSdrMaster(): Unit = runBlocking {
        val source = File(root, "synthetic-gainmap.jpg")
        val base = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
        val gainPixels = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try {
            base.eraseColor(Color.rgb(96, 96, 96))
            gainPixels.eraseColor(Color.rgb(128, 128, 128))
            base.setGainmap(Gainmap(gainPixels).apply {
                setRatioMin(1f, 1f, 1f)
                setRatioMax(4f, 4f, 4f)
                setGamma(1f, 1f, 1f)
                setEpsilonSdr(1f / 64f, 1f / 64f, 1f / 64f)
                setEpsilonHdr(1f / 64f, 1f / 64f, 1f / 64f)
                setMinDisplayRatioForHdrTransition(1f)
                setDisplayRatioForFullHdr(4f)
            })
            assertTrue(base.hasGainmap())
            source.outputStream().use { assertTrue(base.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally {
            base.setGainmap(null)
            base.recycle()
            gainPixels.recycle()
        }

        // Some platform/OEM encoders may not serialize Gainmap. Such an input cannot prove stripping.
        val encoded = decodeSoftware(source)
        val hasEncodedGainmap = try { encoded.hasGainmap() } finally { recycleOwned(encoded) }
        assumeTrue("Native encoder did not emit a decodable gainmap plus MPF; HDR fixture not exercised", hasEncodedGainmap && hasMpf(source))
        val (_, master) = imported(source)
        assertFalse("The normalized master must not retain the MPF auxiliary-image index", hasMpf(master))
        assertFalse("The fresh JPEG must not retain XMP/Exif APP1", hasAppMarker(master, 0xe1))
        val output = decodeSoftware(master)
        try {
            assertSdrMaster(output, 128, 96)
            assertFalse(output.hasGainmap())
            // The new SDR canvas retains the base rendition, not the brighter auxiliary rendition.
            assertRgbNear(Color.rgb(96, 96, 96), output.getPixel(64, 48), 5)
        } finally { recycleOwned(output) }
    }

    @Test fun compressibleLargePngIsSampledBeforeDecodeIntoAnAllowedBudgetProfile(): Unit = runBlocking {
        val source = File(root, "large-compressible.png")
        // 4096x1024 RGB_565 is 8 MiB, not a full-size ARGB/F16 allocation or a large pixel list.
        val input = Bitmap.createBitmap(4096, 1024, Bitmap.Config.RGB_565)
        try {
            assertTrue(input.allocationByteCount.toLong() <= 8 * PhotoBudget.MIB)
            input.eraseColor(Color.RED)
            Canvas(input).drawRect(2048f, 0f, 4096f, 1024f, Paint().apply { color = Color.BLUE })
            source.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { input.recycle() } // Release the fixture before PhotoEngine acquires its native slot.
        assertTrue(source.length() in 1..5_242_880L)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        assertEquals(4096, bounds.outWidth)
        assertEquals(1024, bounds.outHeight)

        val (asset, master) = imported(source)
        val am = context.getSystemService(ActivityManager::class.java)
        val budget = PhotoBudget.forMemoryClass(am.memoryClass, am.isLowRamDevice)
        // A shared process may still hold UI bitmaps from another test: L can reduce the profile.
        // Check the actual native dimensions against all fitting profiles, without assuming L=0.
        assertTrue(budget.candidates(4096, 1024, 0).contains(asset.width to asset.height))
        assertTrue(asset.width <= 2048 && asset.width < bounds.outWidth)
        assertEquals(asset.width, 4 * asset.height)
        assertTrue(12L * asset.width * asset.height + budget.codecReserve + budget.io <= budget.total)
        Log.i("DietPhotoColorTest", "Synthetic 4096x1024 PNG -> ${asset.width}x${asset.height}; H=${am.memoryClass}; lowRam=${am.isLowRamDevice}")
        val output = decodeSoftware(master)
        try {
            assertSdrMaster(output, asset.width, asset.height)
            assertRgbNear(Color.RED, output.getPixel(output.width / 4, output.height / 2), 8)
            assertRgbNear(Color.BLUE, output.getPixel(3 * output.width / 4, output.height / 2), 8)
        } finally { recycleOwned(output) }
    }

    private suspend fun imported(source: File): Pair<MediaAssetEntity, File> {
        assertTrue(source.length() in 1..5_242_880L)
        val draft = repository.checkpointDraft(DraftEntity(kind = "moment", selectedLocalDate = "2026-11-15", rawPayload = "{}", updatedAtMillis = 0), null)
        engine.import(draft.id, "2026-11-15", listOf(Uri.fromFile(source)))
        val asset = repository.media.draftAssets(draft.id).single()
        assertEquals("READY", asset.state)
        assertEquals("image/jpeg", asset.mimeType)
        val master = File(context.noBackupFilesDir, "diet/media/objects/${asset.objectKey}")
        assertTrue(master.canonicalPath.startsWith(root.canonicalPath + File.separator))
        engine.validateMaster(master, asset.width, asset.height)
        assertEquals(asset.byteCount, master.length())
        assertTrue(source.delete()) // The master must decode with no original input remaining.
        return asset to master
    }

    private fun decodeSoftware(file: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setOnPartialImageListener { false }
        // No target colour space/config: verify the encoded image's own SDR/sRGB representation.
    }

    private fun assertSdrMaster(bitmap: Bitmap, width: Int, height: Int) {
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        assertTrue(bitmap.colorSpace?.isSrgb == true)
        assertFalse(bitmap.hasAlpha())
        if (Build.VERSION.SDK_INT >= 34) assertFalse(bitmap.hasGainmap())
    }

    private fun recycleOwned(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()) {
            val auxiliary = bitmap.gainmap?.gainmapContents
            bitmap.setGainmap(null)
            if (auxiliary != null && auxiliary !== bitmap && !auxiliary.isRecycled) auxiliary.recycle()
        }
        bitmap.recycle()
    }

    private fun assertRgbNear(expected: Int, actual: Int, tolerance: Int) {
        assertEquals(255, Color.alpha(actual))
        assertTrue("red: expected ${Color.red(expected)}, actual ${Color.red(actual)}", abs(Color.red(expected) - Color.red(actual)) <= tolerance)
        assertTrue("green: expected ${Color.green(expected)}, actual ${Color.green(actual)}", abs(Color.green(expected) - Color.green(actual)) <= tolerance)
        assertTrue("blue: expected ${Color.blue(expected)}, actual ${Color.blue(actual)}", abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
    }

    private fun nativeIccProfile(file: File): ByteArray = RandomAccessFile(file, "r").use { input ->
        check(input.readLong() == 0x89504e470d0a1a0aUL.toLong())
        while (input.filePointer < input.length()) {
            val size = input.readInt()
            check(size in 0..65_536 && input.filePointer + 4L + size + 4 <= input.length())
            val type = ByteArray(4).also(input::readFully)
            if (type.toString(Charsets.US_ASCII) == "iCCP") {
                val payload = ByteArray(size).also(input::readFully)
                val crc = CRC32().apply { update(type); update(payload) }
                check(input.readInt() == crc.value.toInt())
                return@use payload
            }
            input.seek(input.filePointer + size + 4)
        }
        throw AssertionError("Native Display-P3 PNG did not contain an ICC profile")
    }

    /** A 1025-byte scanline and small compressed payload; no full-image pixel array or new codec. */
    private fun writeSixteenBitPng(file: File, iccProfile: ByteArray, warmP3: Long) {
        val header = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(128); it.writeInt(96)
            it.write(byteArrayOf(16, 6, 0, 0, 0)) // RGBA, 16 bits/channel, deflate, no interlace.
        } }.toByteArray()
        val components = intArrayOf(
            (Color.red(warmP3) * 65_535).roundToInt(),
            (Color.green(warmP3) * 65_535).roundToInt(),
            (Color.blue(warmP3) * 65_535).roundToInt(), 65_535,
        )
        val row = ByteArray(1 + 128 * 8) // Filter byte zero; first32 pixels transparent.
        for (x in 32 until 128) for (channel in components.indices) {
            val offset = 1 + x * 8 + channel * 2
            row[offset] = (components[channel] ushr 8).toByte()
            row[offset + 1] = components[channel].toByte()
        }
        val compressed = ByteArrayOutputStream().also { bytes ->
            DeflaterOutputStream(bytes).use { output -> repeat(96) { output.write(row) } }
        }.toByteArray()
        DataOutputStream(file.outputStream()).use { output ->
            output.writeLong(0x89504e470d0a1a0aUL.toLong())
            fun chunk(name: String, payload: ByteArray) {
                val type = name.toByteArray(Charsets.US_ASCII)
                val crc = CRC32().apply { update(type); update(payload) }
                output.writeInt(payload.size); output.write(type); output.write(payload); output.writeInt(crc.value.toInt())
            }
            chunk("IHDR", header)
            chunk("iCCP", iccProfile)
            chunk("IDAT", compressed)
            chunk("IEND", byteArrayOf())
        }
    }

    private fun hasMpf(file: File): Boolean = hasAppMarker(file, 0xe2, "MPF\u0000")

    /** Stream only primary-JPEG headers; never load or scan the compressed pixel stream in memory. */
    private fun hasAppMarker(file: File, wanted: Int, prefix: String? = null): Boolean = RandomAccessFile(file, "r").use { input ->
        check(input.readUnsignedShort() == 0xffd8)
        while (input.filePointer < input.length()) {
            check(input.readUnsignedByte() == 0xff)
            var marker = input.readUnsignedByte()
            while (marker == 0xff) marker = input.readUnsignedByte()
            if (marker == 0xda || marker == 0xd9) return@use false
            val size = input.readUnsignedShort() - 2
            check(size >= 0 && input.filePointer + size <= input.length())
            val next = input.filePointer + size
            if (marker == wanted) {
                if (prefix == null) return@use true
                if (size >= prefix.length) {
                    val bytes = ByteArray(prefix.length)
                    input.readFully(bytes)
                    if (bytes.toString(Charsets.US_ASCII) == prefix) return@use true
                }
            }
            input.seek(next)
        }
        false
    }
}
