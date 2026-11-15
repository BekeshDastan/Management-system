package app.diet.integration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.DraftEntity
import app.diet.data.Operation
import app.diet.data.PreferenceDraft
import app.diet.data.VisualPreferencesRepository
import app.diet.data.newId
import app.diet.media.PhotoEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Verifies installed APK declarations and observed files, not a cloud/D2D/OEM transport claim. */
@RunWith(AndroidJUnit4::class)
class StoragePrivacyIntegrationTest {
    private lateinit var fixture: IntegrationDatabase
    private val ownedFiles = mutableListOf<File>()
    private val ownedDirectories = mutableListOf<File>()
    private val context: Context get() = fixture.context

    @Before fun setup(): Unit = runBlocking {
        fixture = IntegrationDatabase(ApplicationProvider.getApplicationContext())
        fixture.initialize()
    }
    @After fun cleanup(): Unit = runBlocking {
        if (!::fixture.isInitialized) return@runBlocking
        try {
            fixture.repository.media.assets().forEach { asset ->
                ownedFiles += File(context.noBackupFilesDir, "diet/media/objects/${asset.objectKey}")
                ownedFiles += File(context.cacheDir, "diet-media/${asset.id}.jpg")
                ownedFiles += File(context.cacheDir, "diet-media/${asset.id}.tmp")
            }
            fixture.repository.media.batches().forEach { batch ->
                val directory = File(context.noBackupFilesDir, "diet/staging/${batch.id}")
                ownedFiles += File(directory, "source.part"); ownedFiles += File(directory, "master.tmp")
                ownedDirectories += directory
            }
        } finally { fixture.close() }
        val allowed = listOf(context.noBackupFilesDir.canonicalFile.toPath(), context.cacheDir.canonicalFile.toPath())
        ownedFiles.distinctBy { it.path }.forEach { file ->
            check(allowed.any { file.canonicalFile.toPath().startsWith(it) })
            file.delete()
        }
        ownedDirectories.forEach { it.delete() } // Only empty, fixture-owned staging directories.
    }

    @Test fun compiledManifestAndBothBackupRuleGenerationsExcludeEveryDomain() {
        val info = context.applicationInfo
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertTrue(info.backupAgentName.isNullOrEmpty())
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(permissions.intersect(setOf("android.permission.INTERNET", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_IMAGES")).isEmpty())
        var legacy = 0; var modern = 0; var applicationSeen = false
        val manifest = context.assets.openXmlResourceParser("AndroidManifest.xml")
        try {
            while (manifest.next() != XmlPullParser.END_DOCUMENT) if (manifest.eventType == XmlPullParser.START_TAG) {
                if (manifest.name == "application") {
                    applicationSeen = true
                    assertFalse(manifest.getAttributeBooleanValue(ANDROID_NS, "allowBackup", true))
                    assertNull(manifest.getAttributeValue(ANDROID_NS, "backupAgent"))
                    legacy = manifest.getAttributeResourceValue(ANDROID_NS, "fullBackupContent", 0)
                    modern = manifest.getAttributeResourceValue(ANDROID_NS, "dataExtractionRules", 0)
                }
                if (manifest.name in setOf("application", "activity", "receiver", "service", "provider")) {
                    assertFalse(manifest.getAttributeBooleanValue(ANDROID_NS, "directBootAware", false))
                }
            }
        } finally { manifest.close() }
        assertTrue(applicationSeen); assertTrue(legacy != 0); assertTrue(modern != 0)
        assertRules(legacy, "full-backup-content", setOf("legacy"))
        assertRules(modern, "data-extraction-rules", setOf("cloud-backup", "device-transfer"))
    }

    private fun assertRules(resourceId: Int, root: String, expectedSections: Set<String>) {
        val parser = context.resources.getXml(resourceId)
        val actual = mutableMapOf<String, MutableSet<String>>()
        var section: String? = if (root == "full-backup-content") "legacy" else null
        var rootSeen = false
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                    root -> { assertFalse(rootSeen); rootSeen = true }
                    "cloud-backup", "device-transfer" -> { val current = parser.name; assertTrue(current in expectedSections); section = current; assertFalse(actual.containsKey(current)); actual[current] = mutableSetOf() }
                    "exclude" -> {
                        val current = checkNotNull(section)
                        assertEquals(".", parser.getAttributeValue(null, "path"))
                        val domain = parser.getAttributeValue(null, "domain")
                        assertTrue(domain in DOMAINS)
                        assertTrue(actual.getOrPut(current) { mutableSetOf() }.add(domain))
                    }
                    else -> fail("Unexpected backup rule element: ${parser.name}")
                }
                if (parser.eventType == XmlPullParser.END_TAG && parser.name in setOf("cloud-backup", "device-transfer")) section = null
            }
        } finally { parser.close() }
        assertTrue(rootSeen); assertEquals(expectedSections, actual.keys)
        actual.values.forEach { assertEquals(DOMAINS, it) }
    }

    @Test fun realRoomPreferencesDraftAndPhotoFilesStayInsideTheirPrivateRoots(): Unit = runBlocking {
        assertFalse(context.isDeviceProtectedStorage)
        val deviceContext = context.createDeviceProtectedStorageContext()
        assertNotEquals(context.dataDir.canonicalPath, deviceContext.dataDir.canonicalPath)
        val inventoryRoots = (listOf(context.dataDir, deviceContext.dataDir) + context.getExternalFilesDirs(null).filterNotNull() + context.externalCacheDirs.filterNotNull()).distinctBy { it.canonicalPath }
        val before = inventory(inventoryRoots)
        val marker = "integration-sensitive-${newId()}"
        val repo = fixture.repository
        repo.saveWeight("2026-11-15", 8012, null, Operation())
        val draft = repo.checkpointDraft(DraftEntity(kind = "moment", selectedLocalDate = "2026-11-15", rawPayload = "{\"note\":\"$marker\"}", updatedAtMillis = 0), null)
        val database = fixture.actualPath()
        val privateRoot = File(context.noBackupFilesDir, "diet").canonicalFile
        assertTrue(database.toPath().startsWith(privateRoot.toPath()))
        assertEquals(File(privateRoot, "db").canonicalFile, database.parentFile)
        assertEquals(fixture.name, database.name)
        assertFalse(context.getDatabasePath(fixture.name).exists())
        assertEquals(Triple(1L, 2L, "wal"), fixture.pragmas())
        assertTrue(database.isFile); assertTrue(File(database.path + "-wal").isFile); assertTrue(File(database.path + "-shm").isFile)

        val prefFile = VisualPreferencesRepository.preferencesFile(context, "integration-${newId()}.preferences_pb")
        ownedFiles += prefFile
        val store = PreferenceDataStoreFactory.create(scope = fixture.scope, produceFile = { prefFile })
        val visual = VisualPreferencesRepository(store, fixture.scope)
        val prefDraft = visual.checkpoint(PreferenceDraft(newId(), "view", 1, rawFields = mapOf("query" to marker)), null)
        visual.apply("view", prefDraft.rawFields, 1, newId(), prefDraft.id, prefDraft.draftRevision)
        withTimeout(5000) { visual.state.first { it.ready && it.viewContext["query"] == marker } }
        assertTrue(prefFile.isFile); assertEquals(File(privateRoot, "prefs").canonicalFile, prefFile.canonicalFile.parentFile)
        assertTrue(runCatching { VisualPreferencesRepository.preferencesFile(deviceContext, "integration.preferences_pb") }.isFailure)

        val source = File(context.cacheDir, "integration-input-${newId()}.png"); ownedFiles += source
        val image = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.GREEN); source.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
        val engine = PhotoEngine(context, repo, fixture.scope)
        var observedStaging = emptySet<String>()
        engine.phaseHook = { phase -> if (phase == "after-asset-prepared") observedStaging = inventory(listOf(File(privateRoot, "staging"))) - before }
        engine.import(draft.id, "2026-11-15", listOf(Uri.fromFile(source)))
        val asset = repo.media.draftAssets(draft.id).single()
        val master = File(privateRoot, "media/objects/${asset.objectKey}")
        assertTrue(master.isFile); assertEquals("${asset.id}.jpg", asset.objectKey)
        assertTrue(observedStaging.any { it.endsWith("/source.part") || it.endsWith("\\source.part") })
        val stagingRoot = File(privateRoot, "staging").toPath()
        observedStaging.forEach { assertTrue(File(it).toPath().startsWith(stagingRoot)) }
        assertTrue(source.delete())
        engine.decodeViewer(asset.id, 320).close()
        val derivative = File(context.cacheDir, "diet-media/${asset.id}.jpg")
        assertTrue(derivative.isFile); assertFalse(derivative.canonicalFile.toPath().startsWith(privateRoot.toPath()))
        assertTrue(master.isFile)

        val after = inventory(inventoryRoots)
        val allowed = listOf(privateRoot.toPath(), File(context.cacheDir, "diet-media").canonicalFile.toPath())
        (after - before).map(::File).filter { file -> allowed.none { file.canonicalFile.toPath().startsWith(it) } }.forEach { file ->
            assertFalse("Synthetic sensitive marker escaped approved roots: ${file.path}", contains(file, marker.toByteArray()))
            assertFalse("Database sidecar escaped its noBackup directory", file.name.startsWith(fixture.name))
            assertFalse("Photo escaped private media/cache roots", file.name.contains(asset.id))
        }
        assertFalse(File(deviceContext.noBackupFilesDir, "diet/db/${fixture.name}").exists())
    }

    private fun inventory(roots: List<File>): Set<String> = roots.flatMap { root -> if (root.isDirectory) root.walkTopDown().filter { it.isFile }.map { it.canonicalPath }.toList() else emptyList() }.toSet()
    private fun contains(file: File, marker: ByteArray): Boolean {
        file.inputStream().use { input ->
            var carry = byteArrayOf(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) return false
                val window = carry + buffer.copyOf(count)
                for (i in 0..window.size - marker.size) if (marker.indices.all { window[i + it] == marker[it] }) return true
                carry = window.takeLast(minOf(window.size, marker.size - 1)).toByteArray()
            }
        }
    }
    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private val DOMAINS = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
    }
}
