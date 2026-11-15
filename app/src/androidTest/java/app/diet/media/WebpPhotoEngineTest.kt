package app.diet.media

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class WebpPhotoEngineTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var database: DietDatabase
    private lateinit var repository: DietRepository
    private lateinit var engine: PhotoEngine

    @Before fun setup(): Unit = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "webp-codec-fixture-${newId()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = DietDatabase.open(context, "codec.db")
        repository = DietRepository(context, scope, Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC), database)
        repository.initialize(); engine = PhotoEngine(context, repository, scope)
    }
    @After fun cleanup(): Unit = runBlocking {
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::database.isInitialized) database.close()
        if (::root.isInitialized) {
            val base = ApplicationProvider.getApplicationContext<Context>().cacheDir.canonicalFile
            check(root.canonicalFile.parentFile == base && root.name.startsWith("webp-codec-fixture-"))
            root.deleteRecursively()
        }
    }
    @Suppress("DEPRECATION") // The legacy WEBP encoder exists on the selected minimum API29.
    private fun source(transparent: Boolean): File {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(if (transparent) Color.TRANSPARENT else Color.GREEN)
        if (transparent) Canvas(bitmap).drawRect(32f, 0f, 64f, 48f, Paint().apply { color = Color.RED })
        val result = File(root, "source.webp")
        result.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP, 95, it)) }
        bitmap.recycle(); return result
    }
    private suspend fun imported(source: File): String {
        val draft = repository.checkpointDraft(DraftEntity(kind = "moment", selectedLocalDate = "2026-11-15", rawPayload = "{}", updatedAtMillis = 0), null)
        engine.import(draft.id, "2026-11-15", listOf(Uri.fromFile(source)))
        val asset = repository.media.draftAssets(draft.id).single()
        assertEquals("READY", asset.state); assertEquals("image/jpeg", asset.mimeType)
        engine.validateMaster(File(context.noBackupFilesDir, "diet/media/objects/${asset.objectKey}"), asset.width, asset.height)
        assertTrue(source.delete())
        return asset.id
    }

    @Test fun staticWebpBecomesIndependentSrgbJpeg(): Unit = runBlocking {
        val id = imported(source(false))
        engine.decodeViewer(id).use { handle ->
            val image = handle.bitmap; assertEquals(64, image.width); assertEquals(48, image.height)
            val pixel = image.getPixel(16, 24)
            assertTrue(Color.green(pixel) > 200 && Color.red(pixel) < 60 && Color.blue(pixel) < 60)
        }
    }
    @Test fun transparentWebpCompositesOnWhiteWithoutLosingOpaqueContent(): Unit = runBlocking {
        val id = imported(source(true))
        engine.decodeViewer(id).use { handle ->
            val white = handle.bitmap.getPixel(16, 24); val red = handle.bitmap.getPixel(48, 24)
            assertEquals(255, Color.alpha(white))
            assertTrue(Color.red(white) > 240 && Color.green(white) > 240 && Color.blue(white) > 240)
            assertTrue(Color.red(red) > 200 && Color.green(red) < 60 && Color.blue(red) < 60)
        }
    }
    @Test fun animatedWebpUsesItsFirstFrameExactlyOnce(): Unit = runBlocking {
        // Generated offline with installed Pillow12: two 32x24 lossless frames, red then blue.
        // This is synthetic test-APK content; no gallery data or metadata is embedded.
        val source = File(root, "animated.webp")
        InstrumentationRegistry.getInstrumentation().context.assets.open("synthetic-first-frame.webp").use { input -> source.outputStream().use { input.copyTo(it) } }
        val id = imported(source)
        engine.decodeViewer(id).use { handle ->
            assertEquals(32, handle.bitmap.width); assertEquals(24, handle.bitmap.height)
            val firstFrame = handle.bitmap.getPixel(16, 12)
            assertTrue(Color.red(firstFrame) > 200 && Color.green(firstFrame) < 60 && Color.blue(firstFrame) < 60)
        }
    }
}
