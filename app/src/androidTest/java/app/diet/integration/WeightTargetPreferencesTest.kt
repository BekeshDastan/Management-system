package app.diet.integration

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Independent personal intention: validation/recovery must not normalize unfinished raw input. */
@RunWith(AndroidJUnit4::class)
class WeightTargetPreferencesTest {
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var file: File
    private lateinit var repo: VisualPreferencesRepository

    @Before fun setup(): Unit = runBlocking {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = VisualPreferencesRepository.preferencesFile(context, "weight-target-${newId()}.preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        repo = VisualPreferencesRepository(store, scope)
        ready { it.ready }
    }

    @After fun cleanup(): Unit = runBlocking {
        if (::scope.isInitialized) scope.coroutineContext[Job]?.cancelAndJoin()
        if (::file.isInitialized) {
            check(file.name.startsWith("weight-target-") && file.name.endsWith(".preferences_pb"))
            listOf(file, File(file.path + ".tmp")).forEach { check(!it.exists() || it.delete()) }
        }
    }

    private suspend fun ready(predicate: (VisualPreferences) -> Boolean): VisualPreferences = withTimeout(5_000) { repo.state.first(predicate) }
    private suspend fun rejects(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        assertTrue("Invalid or conflicting update must reject", failed)
    }

    @Test fun defaultDarkDoesNotOverrideAnExplicitThemeOrExistingOtherGroup(): Unit = runBlocking {
        assertEquals("dark", ready { it.ready }.theme)
        repo.apply("theme", mapOf("theme" to "light"), 1, newId())
        repo.apply("weightTarget", mapOf("kg" to "68,45"), 1, newId())
        val saved = ready { it.desiredWeightCentiKg == 6845L }
        assertEquals("light", saved.theme)
        assertEquals(2L, saved.revisions["theme"])
        assertEquals(2L, saved.revisions["weightTarget"])
    }

    @Test fun rawDraftSurvivesInvalidApplyAndValidValueUsesTheSameRevisionContract(): Unit = runBlocking {
        repo.apply("weightTarget", mapOf("kg" to "70"), 1, newId())
        val draft = repo.checkpoint(PreferenceDraft(newId(), "weightTarget", 2, rawFields = mapOf("kg" to "72,")), null)
        rejects { repo.apply("weightTarget", mapOf("kg" to "72,"), 2, newId(), draft.id, draft.draftRevision) }
        assertEquals("72,", repo.freshPreferenceDraft("weightTarget")!!.rawFields["kg"])
        assertEquals(7000L, ready { it.desiredWeightCentiKg == 7000L }.desiredWeightCentiKg)
        val corrected = repo.checkpoint(draft.copy(rawFields = mapOf("kg" to "72,40")), draft.draftRevision)
        val fields = mapOf("kg" to "72,40")
        val token = newId()
        repo.apply("weightTarget", fields, 2, token, corrected.id, corrected.draftRevision)
        assertTrue(repo.recoverPreference("weightTarget", token, fields, 2))
        assertEquals(7240L, ready { it.desiredWeightCentiKg == 7240L }.desiredWeightCentiKg)
        assertEquals("COMMITTED", repo.freshPreferenceDraft("weightTarget")!!.status)
    }

    @Test fun repeatedTokenDoesNotApplyTwiceAndClearingOnlyRemovesTheIntention(): Unit = runBlocking {
        val token = newId()
        val fields = mapOf("kg" to "72.40")
        repo.apply("weightTarget", fields, 1, token)
        repo.apply("weightTarget", fields, 1, token)
        assertEquals(2L, ready { it.desiredWeightCentiKg == 7240L }.revisions["weightTarget"])
        rejects { repo.apply("weightTarget", mapOf("kg" to "75"), 1, token) }
        rejects { repo.apply("weightTarget", mapOf("kg" to "0"), 2, newId()) }
        rejects { repo.apply("weightTarget", mapOf("kg" to "1.001"), 2, newId()) }
        repo.apply("weightTarget", mapOf("kg" to ""), 2, newId())
        val cleared = ready { it.revisions["weightTarget"] == 3L }
        assertNull(cleared.desiredWeightCentiKg)
        assertEquals("dark", cleared.theme)
    }
}
