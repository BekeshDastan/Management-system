package app.diet.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.*
import app.diet.integration.IntegrationDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Deferred deletion must not discard raw edits created after the user queued that deletion. */
@RunWith(AndroidJUnit4::class)
class DeletionUxTest {
    private lateinit var fixture: IntegrationDatabase
    private lateinit var preferences: VisualPreferencesRepository
    private lateinit var preferenceFile: File
    private lateinit var host: UiHost
    private val date = "2026-11-15"

    @Before fun setUp(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fixture = IntegrationDatabase(context)
        fixture.initialize()
        preferenceFile = File(context.noBackupFilesDir, "diet/prefs/deletion-${newId()}.preferences_pb")
        check(preferenceFile.parentFile!!.isDirectory || preferenceFile.parentFile!!.mkdirs())
        preferences = VisualPreferencesRepository(PreferenceDataStoreFactory.create(scope = fixture.scope, produceFile = { preferenceFile }), fixture.scope)
        withTimeout(10_000) { preferences.state.first { it.ready } }
        fixture.repository.saveWeight(date, 7200, null, Operation())
        host = UiHost(fixture.repository, preferences, SilentMedia(), fixture.scope)
        host.date = date
    }

    @After fun tearDown(): Unit = runBlocking {
        if (::fixture.isInitialized) fixture.close()
        if (::preferenceFile.isInitialized) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            check(preferenceFile.canonicalFile.toPath().startsWith(context.noBackupFilesDir.canonicalFile.toPath()))
            listOf(preferenceFile, File(preferenceFile.path + ".tmp")).forEach { it.delete() }
        }
    }

    private suspend fun queued(): Pair<UiFormSession, PendingDeletion> {
        host.openForm("weight")
        withTimeout(10_000) { while (host.openingForm || host.form?.editable != true) delay(10) }
        val editor = requireNotNull(host.form)
        val record = fixture.repository.state.value.weights.single { it.localDate == date }
        host.queueDeletion("delete-weight", record.id, record.revision, emptyMap())
        withTimeout(10_000) { while (host.pendingDeletion == null || editor.busy) delay(10) }
        return editor to requireNotNull(host.pendingDeletion)
    }

    @Test fun undoRetainsFactAndItsRawDraft(): Unit = runBlocking {
        val (editor, pending) = queued()
        host.undoDeletion()
        host.commitDeletion(pending) // A timeout already enqueued before undo is now harmless.
        assertNull(host.pendingDeletion)
        assertFalse(host.deleting)
        assertEquals(7200L, fixture.repository.pageReportDays(LocalDate.parse(date), LocalDate.parse(date), 0, 1).days.single().weightCentiKg)
        assertEquals("OPEN", fixture.repository.freshDraft(editor.entity.id)!!.status)
    }

    @Test fun activeReopenedRawDraftSurvivesBackgroundDelete(): Unit = runBlocking {
        newerDraftSurvives(active = true)
    }

    @Test fun newerCheckpointSurvivesEvenWhenEditorHasAlreadyClosed(): Unit = runBlocking {
        newerDraftSurvives(active = false)
    }

    private suspend fun newerDraftSurvives(active: Boolean) {
        val (old, pending) = queued()
        val reopened = UiFormSession.restore(fixture.repository, fixture.scope, fixture.repository.freshDraft(old.entity.id)!!)
        reopened.field("weight", "73,")
        val newCheckpoint = reopened.checkpoint()
        reopened.stopDebounce()
        assertTrue(newCheckpoint.draftRevision > old.entity.draftRevision)
        // Model the race where a deletion was already accepted before reopening could cancel it.
        if (active) { host.form = reopened; host.push(UiRoute("editor")) }
        host.commitDeletion(pending)
        withTimeout(10_000) { while (host.deleting) delay(10) }
        val preserved = requireNotNull(fixture.repository.freshDraft(old.entity.id))
        assertEquals("OPEN", preserved.status)
        assertEquals(newCheckpoint.draftRevision, preserved.draftRevision)
        assertEquals("73,", UiFormSession.restore(fixture.repository, fixture.scope, preserved).data["weight"])
        assertNull(fixture.repository.pageReportDays(LocalDate.parse(date), LocalDate.parse(date), 0, 1).days.single().weightCentiKg)
        if (active) assertSame(reopened, host.form)
    }

    @Test fun changedFactRejectsOldDeletionRevision(): Unit = runBlocking {
        val (editor, pending) = queued()
        val record = fixture.repository.state.value.weights.single { it.localDate == date }
        fixture.repository.saveWeight(date, 7350, record.revision, Operation())
        host.commitDeletion(pending)
        withTimeout(10_000) { while (host.deleting) delay(10) }
        assertEquals(7350L, fixture.repository.pageReportDays(LocalDate.parse(date), LocalDate.parse(date), 0, 1).days.single().weightCentiKg)
        assertEquals("OPEN", fixture.repository.freshDraft(editor.entity.id)!!.status)
        assertNotNull(host.error)
    }

    private class SilentMedia : UiMediaActions {
        override val state = MutableStateFlow(UiMediaState())
        override fun pickPhotos(draftId: String, date: String, freeSlots: Int) = Unit
        override fun cancelImport(draftId: String) = Unit
        override fun clearError() = Unit
        override fun requestNotifications() = Unit
        override fun openNotificationSettings() = Unit
        override fun consumeOpenAction(id: String) = Unit
        @Composable override fun Thumbnail(assetId: String, modifier: Modifier, description: String) = Unit
    }
}
