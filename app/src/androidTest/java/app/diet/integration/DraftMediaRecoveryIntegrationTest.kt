package app.diet.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Metadata crash-boundary fixtures only; these tests do not claim JPEG/file validation. */
@RunWith(AndroidJUnit4::class)
class DraftMediaRecoveryIntegrationTest {
    private lateinit var fixture: IntegrationDatabase
    @Before fun setup(): Unit = runBlocking { fixture = IntegrationDatabase(ApplicationProvider.getApplicationContext()); fixture.initialize() }
    @After fun cleanup(): Unit = runBlocking { if (::fixture.isInitialized) fixture.close() }
    private fun asset(): MediaAssetEntity { val id = newId(); return MediaAssetEntity(id = id, objectKey = "$id.jpg", width = 10, height = 10, byteCount = 100, sha256 = "0".repeat(64), state = "READY", integrity = "VERIFIED", createdAtMillis = 0) }
    private suspend fun existing(date: String): PhotoEntity = fixture.repository.metadataTransaction { dao ->
        val moment = MomentEntity(localDate = date)
        val image = asset(); val photo = PhotoEntity(momentId = moment.id, assetId = image.id, caption = "Синтетическая подпись", ordinal = 0)
        dao.put(moment); dao.put(image); dao.put(photo); photo
    }
    private suspend fun stagedDraft(count: Int): DraftEntity {
        val repo = fixture.repository
        val draft = repo.checkpointDraft(DraftEntity(kind = "moment", selectedLocalDate = "2026-11-15", rawPayload = "{\"note\":\"synthetic preserved text\"}", updatedAtMillis = 0), null)
        return repo.metadataTransaction { dao ->
            repeat(count) { index -> val image = asset(); dao.put(image); dao.put(DraftMediaEntity(draftId = draft.id, assetId = image.id, role = "staged", ordinal = index.toLong())) }
            val checkpoint = draft.copy(draftRevision = 2, mediaRevision = 2); dao.put(checkpoint); checkpoint
        }
    }

    @Test fun missingExistingRefsAreRestoredWithoutDroppingStagedAssetsOrText(): Unit = runBlocking {
        val repo = fixture.repository
        val photo = existing("2026-11-15"); val otherDay = existing("2026-11-14")
        val draft = stagedDraft(1)
        val staged = fixture.database.dao().draftMedia(draft.id).single()
        val restored = repo.media.ensureExistingDraftAssets(draft.id, draft.mediaRevision, listOf(photo.assetId))
        assertEquals(draft.rawPayload, restored.rawPayload)
        val refs = fixture.database.dao().draftMedia(draft.id)
        assertEquals(2, refs.size); assertTrue(refs.contains(staged))
        assertTrue(refs.any { it.assetId == photo.assetId && it.role == "existing" })
        val inputs = repo.media.draftPhotoInputs(draft.id)
        assertEquals(PhotoInput(photo.id, photo.assetId, photo.caption, photo.mealId), inputs.single { it.assetId == photo.assetId })
        assertEquals(restored, repo.media.ensureExistingDraftAssets(draft.id, restored.mediaRevision, listOf(photo.assetId)))
        assertTrue(runCatching { repo.media.ensureExistingDraftAssets(draft.id, draft.mediaRevision, listOf(photo.assetId)) }.isFailure)
        assertTrue(runCatching { repo.media.ensureExistingDraftAssets(draft.id, restored.mediaRevision, listOf(otherDay.assetId)) }.isFailure)
        assertEquals(refs, fixture.database.dao().draftMedia(draft.id))
    }

    @Test fun restoredReferencesCannotExceedFourAndFailureLeavesCheckpointIntact(): Unit = runBlocking {
        val photo = existing("2026-11-15"); val draft = stagedDraft(4)
        val before = fixture.database.dao().draftMedia(draft.id)
        assertTrue(runCatching { fixture.repository.media.ensureExistingDraftAssets(draft.id, draft.mediaRevision, listOf(photo.assetId)) }.isFailure)
        assertEquals(before, fixture.database.dao().draftMedia(draft.id))
        assertEquals(draft, fixture.repository.freshDraft(draft.id))
    }
}
