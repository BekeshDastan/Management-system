package app.diet.integration

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.PreferenceDraft
import app.diet.data.VisualPreferences
import app.diet.data.VisualPreferencesRepository
import app.diet.data.newId
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated synthetic DataStores; no production preferences or health database is opened. */
@RunWith(AndroidJUnit4::class)
class PreferencesRecoveryIntegrationTest {
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var file: File
    private val version = longPreferencesKey("formatVersion")
    private val theme = stringPreferencesKey("value.theme")
    private val appearance = stringPreferencesKey("value.appearance")
    private val appearanceDraft = stringPreferencesKey("draft.appearance")
    private val fontDraft = stringPreferencesKey("draft.font")

    @Before fun setup() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = VisualPreferencesRepository.preferencesFile(context, "prefs-recovery-${newId()}.preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @After fun cleanup(): Unit = runBlocking {
        if (::scope.isInitialized) scope.coroutineContext[Job]?.cancelAndJoin()
        if (::file.isInitialized) listOf(file, File(file.path + ".tmp"), File(file.path + ".bak")).forEach { it.delete() }
    }

    private suspend fun seed(draft: String? = null) {
        store.edit { p ->
            p[version] = 1L
            p[theme] = "{\"theme\":\"dark\"}"
            p[appearance] = "{\"accent\":\"fern\",\"cover\":\"5\"}"
            if (draft != null) p[appearanceDraft] = draft
        }
    }

    private fun futureDraft(id: String, extra: String = "kept"): String =
        """{"id":"$id","group":"appearance","baseRevision":1,"draftRevision":1,"rawFields":{"accent":"berry","cover":"2"},"status":"OPEN","formatVersion":2,"futureStep":{"token":"$extra"}}"""

    private suspend fun state(repo: VisualPreferencesRepository, predicate: (VisualPreferences) -> Boolean): VisualPreferences =
        withTimeout(5_000) { repo.state.first(predicate) }

    private suspend fun rejected(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        assertTrue("Unsupported data must reject this ordinary mutation", failed)
    }

    @Test fun transientReadFailureCanRetryWithoutResettingSavedChoices(): Unit = runBlocking {
        seed()
        val subscriptions = AtomicInteger()
        val firstReadFails = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (subscriptions.getAndIncrement() == 0) throw IOException("Synthetic transient first read failure")
                emitAll(store.data)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = store.updateData(transform)
        }
        val repo = VisualPreferencesRepository(firstReadFails, scope)
        val failed = state(repo) { it.error != null }
        assertFalse(failed.ready)
        assertEquals("{\"theme\":\"dark\"}", store.data.first()[theme])
        repo.retryObservation()
        val recovered = state(repo) { it.ready && it.theme == "dark" && it.error == null }
        assertEquals("fern", recovered.accent)
        assertEquals(2, subscriptions.get())
        store.edit { it[theme] = "{\"theme\":\"light\"}" }
        state(repo) { it.ready && it.theme == "light" }
    }

    @Test fun futureAndMalformedDraftsAreIsolatedAndOrdinaryMutationsKeepTheirExactRaw(): Unit = runBlocking {
        val id = newId()
        val raw = futureDraft(id)
        val malformed = "{\"formatVersion\":1,\"unknownExtra\":true}"
        seed(raw)
        store.edit { it[fontDraft] = malformed }
        val repo = VisualPreferencesRepository(store, scope)
        val loaded = state(repo) { it.ready && it.unsupportedDraftGroups == setOf("appearance", "font") }
        assertEquals("dark", loaded.theme)
        assertEquals("fern", loaded.accent)
        assertEquals("5", loaded.cover)
        assertTrue(loaded.drafts.none { it.group in loaded.unsupportedDraftGroups })
        repo.apply("theme", mapOf("theme" to "light"), 1L, newId())
        state(repo) { it.ready && it.theme == "light" }
        rejected { repo.freshPreferenceDraft("appearance") }
        rejected { repo.apply("appearance", mapOf("accent" to "ocean", "cover" to "1"), 1L, newId()) }
        rejected { repo.checkpoint(PreferenceDraft(id, "appearance", 1L, rawFields = mapOf("accent" to "ocean", "cover" to "1")), 1L) }
        rejected { repo.discard("appearance", id, 1L) }
        assertEquals(raw, store.data.first()[appearanceDraft])
        assertEquals(malformed, store.data.first()[fontDraft])
        assertEquals("{\"accent\":\"fern\",\"cover\":\"5\"}", store.data.first()[appearance])
        assertEquals(1L, store.data.first()[version])
    }

    @Test fun explicitDiscardOfUnknownDraftRequiresTheCurrentlyReviewedFingerprint(): Unit = runBlocking {
        val raw = futureDraft(newId())
        seed(raw)
        val repo = VisualPreferencesRepository(store, scope)
        val first = state(repo) { it.ready && "appearance" in it.unsupportedDraftGroups }
        val reviewed = first.unsupportedDrafts.single { it.group == "appearance" }.rawFingerprint
        val changed = raw.replace("kept", "changed-after-preview")
        store.edit { it[appearanceDraft] = changed }
        val newer = state(repo) { it.ready && it.unsupportedDrafts.any { d -> d.group == "appearance" && d.rawFingerprint != reviewed } }
        rejected { repo.discardUnsupportedDraft("appearance", reviewed) }
        assertEquals(changed, store.data.first()[appearanceDraft])
        repo.discardUnsupportedDraft("appearance", newer.unsupportedDrafts.single { it.group == "appearance" }.rawFingerprint)
        state(repo) { it.ready && "appearance" !in it.unsupportedDraftGroups }
        assertNull(store.data.first()[appearanceDraft])
        assertEquals("{\"theme\":\"dark\"}", store.data.first()[theme])
        assertEquals("{\"accent\":\"fern\",\"cover\":\"5\"}", store.data.first()[appearance])
    }

    @Test fun unknownWholeFileFormatRemainsBlockedAndAllMutationsPreserveIt(): Unit = runBlocking {
        val id = newId()
        seed(futureDraft(id))
        val repo = VisualPreferencesRepository(store, scope)
        val known = state(repo) { it.ready && "appearance" in it.unsupportedDraftGroups }
        val fingerprint = known.unsupportedDrafts.single { it.group == "appearance" }.rawFingerprint
        store.edit { it[version] = 2L }
        state(repo) { !it.ready && it.error != null }
        val original = store.data.first()
        rejected { repo.apply("theme", mapOf("theme" to "light"), 1L, newId()) }
        rejected { repo.checkpoint(PreferenceDraft(id, "appearance", 1L, rawFields = mapOf("accent" to "ocean", "cover" to "1")), 1L) }
        rejected { repo.discard("appearance", id, 1L) }
        rejected { repo.discardUnsupportedDraft("appearance", fingerprint) }
        repo.retryObservation()
        assertEquals(original, store.data.first())
        assertEquals(2L, store.data.first()[version])
        assertFalse(repo.state.value.ready)
    }
}
