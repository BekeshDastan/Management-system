package app.diet.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.domain.FoodUnit
import app.diet.domain.NameKeys
import app.diet.domain.NutritionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone

/** All values/files are synthetic; no application/user database is opened by these tests. */
@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var context: Context
    private lateinit var db: DietDatabase
    private lateinit var repo: DietRepository
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private val clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC)
    private val today = "2026-11-15"
    private val nutrients = MicroNutrients(150_000_000, 10_000_000, 4_000_000, 18_000_000)

    @Before fun setup(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val name = "test-${newId()}.db"
        file = File(context.noBackupFilesDir, "diet/db/$name")
        db = DietDatabase.open(context, name)
        repo = DietRepository(context, scope, clock, db)
        repo.initialize()
    }
    @After fun cleanup(): Unit = runBlocking {
        // Finish the repository's native reads before closing/releasing the test database.
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::db.isInitialized) db.close()
        // Delete only this test's explicitly allocated files, never the production database.
        if (::file.isInitialized) {
            check(file.canonicalFile.parentFile == File(context.noBackupFilesDir, "diet/db").canonicalFile && file.name.startsWith("test-"))
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { check(!it.exists() || it.delete()) }
        }
    }
    private fun meal() = repo.state.value.meals.first().id
    private suspend fun product(): CatalogItemEntity {
        repo.saveProduct(null, "Тестовый продукт", FoodUnit.GRAM, null, nutrients, baseRevision = null, operation = Operation())
        return repo.state.value.catalog.single()
    }
    private suspend fun rejected(block: suspend () -> Unit) { assertTrue("Операция должна быть отклонена", runCatching { block() }.isFailure) }

    @Test fun initializationIsIdempotentAndPathsAreNoBackup(): Unit = runBlocking {
        val first = repo.state.value
        repo.initialize()
        assertEquals(first.profile?.id, repo.state.value.profile?.id)
        assertEquals(first.meals.map { it.id }, repo.state.value.meals.map { it.id })
        assertTrue(repo.state.value.entries.isEmpty()); assertTrue(repo.state.value.goals.isEmpty())
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertTrue(file.isFile)
        VerifiedSQLiteDriver().open(file.absolutePath).use { DietDatabase.verifyConnection(it) }
    }

    @Test fun historicalSnapshotSurvivesCatalogEditAndDelete(): Unit = runBlocking {
        val p = product()
        repo.addFood(today, meal(), LineInput(p.snapshot, 80_000_000, p.id), Operation())
        repo.saveProduct(p.id, "Изменённый продукт", FoodUnit.GRAM, null, nutrients.copy(kcal = 200_000_000), baseRevision = p.revision, operation = Operation())
        val updated = repo.state.value.catalog.single()
        repo.deleteCatalogItem(updated.id, updated.revision, Operation())
        val entry = repo.state.value.entries.single()
        assertEquals("120,0", entry.nutrients().kcal.format(1))
        assertEquals("Тестовый продукт", entry.displayName())
        assertNull(entry.sourceItemId)
        repo.editFood(entry.id, 160_000_000, null, null, entry.revision, Operation())
        assertEquals("240,0", repo.state.value.entries.single().nutrients().kcal.format(1))
    }

    @Test fun explicitSnapshotReplacementChangesOnlyTheChosenFactAndRequiresNewQuantity(): Unit = runBlocking {
        val product = product()
        repo.addFood(today, meal(), LineInput(product.snapshot, 80_000_000, product.id), Operation())
        repo.addFood(today, meal(), LineInput(product.snapshot, 80_000_000, product.id), Operation())
        val original = repo.state.value.entries.first(); val untouched = repo.state.value.entries.last()
        val sourceResult = repo.saveProduct(null, "Синтетический напиток", FoodUnit.MILLILITRE, null, nutrients.copy(kcal = 200_000_000), baseRevision = null, operation = Operation())
        val source = repo.loadCatalogItem(sourceResult.entityIds.single())!!
        val input = LineInput(source.snapshot, 50_000_000, source.id)
        rejected { repo.replaceFoodSnapshot(original.id, input.copy(quantityMicros = 0), original.revision, Operation()) }
        val operation = Operation()
        repo.replaceFoodSnapshot(original.id, input, original.revision, operation)
        val replaced = repo.loadEntry(original.id)!!
        assertEquals("100,0", replaced.nutrients().kcal.format(1))
        assertEquals("MILLILITRE", replaced.snapshot!!.unit)
        assertEquals(original.localDate, replaced.localDate); assertEquals(original.mealId, replaced.mealId); assertEquals(original.ordinal, replaced.ordinal)
        assertEquals(untouched, repo.loadEntry(untouched.id))
        repo.saveProduct(source.id, source.name, FoodUnit.MILLILITRE, null, nutrients.copy(kcal = 900_000_000), baseRevision = source.revision, operation = Operation())
        assertEquals(replaced, repo.loadEntry(original.id))
        assertTrue(repo.replaceFoodSnapshot(original.id, input, original.revision, operation).replayed)
        rejected { repo.replaceFoodSnapshot(original.id, input, original.revision, Operation()) }
    }

    @Test fun skippedOnboardingDraftCanExplicitlyBecomeManualGoalWithNewCommand(): Unit = runBlocking {
        val originalOperation = newId()
        val draft = repo.checkpointDraft(DraftEntity(kind = "onboarding-goal", selectedLocalDate = today, rawPayload = "{\"kcal\":\"150\"}", operationId = originalOperation, updatedAtMillis = 0), null)
        repo.completeOnboarding(Operation())
        val completed = repo.state.value.onboarding!!
        assertNull(repo.recover(originalOperation))
        val manualOperation = newId()
        val confirmed = repo.checkpointDraft(draft.copy(operationId = manualOperation, rawPayload = "{\"manualAfterOnboarding\":true,\"kcal\":\"150\"}"), draft.draftRevision)
        val operation = Operation(manualOperation, draft.id, confirmed.draftRevision)
        val result = repo.saveDeferredOnboardingGoal(today, nutrients, null, repo.state.value.goalTimelineRevision, operation)
        assertEquals(completed, repo.state.value.onboarding)
        assertEquals(1, repo.state.value.goals.size)
        assertEquals("deferred-manual-goal", repo.recover(result.operationId)!!.first.kind)
        assertTrue(repo.saveDeferredOnboardingGoal(today, nutrients, null, 1, operation).replayed)
        rejected { repo.completeOnboardingWithGoal(today, nutrients, 1, Operation(originalOperation, draft.id, draft.draftRevision)) }
        assertEquals(1, repo.state.value.goals.size)
    }

    @Test fun seedEnsureSurvivesChangedManifestWithoutResetOrResurrection(): Unit = runBlocking {
        val category = CategoryEntity(code = "synthetic", name = "Синтетическая категория", ordinal = 0)
        fun item(name: String) = CatalogItemEntity(name = name, searchKey = NameKeys.normalize(name), categoryId = category.id, input = nutrients,
            snapshot = SnapshotColumns.from(NutritionSnapshot(name, FoodUnit.GRAM, 100_000_000, nutrients.toDomain())),
            sourceFdcId = 1, sourceDescription = "Synthetic fixture", sourceRelease = "Synthetic v1", sourceVerifiedRevision = 1)
        val original = listOf(item("Пример один"), item("Пример два"))
        val installed = repo.seedCatalogOnce(original, listOf(category), "a".repeat(64))
        val receipt = repo.recover(installed.operationId)!!.first
        repo.saveProduct(original[0].id, "Моя правка", FoodUnit.GRAM, null, nutrients.copy(kcal = 300_000_000), category.id, original[0].revision, Operation())
        repo.deleteCatalogItem(original[1].id, original[1].revision, Operation())
        val future = item("Пример три")
        val ensured = repo.seedCatalogOnce(original + future, listOf(category), "b".repeat(64))
        assertTrue(ensured.replayed); assertTrue(repo.catalogSeedV1Applied())
        assertEquals(installed.entityIds, ensured.entityIds)
        assertEquals(receipt, repo.recover(installed.operationId)!!.first)
        assertEquals("Моя правка", repo.loadCatalogItem(original[0].id)!!.name)
        assertNull(repo.loadCatalogItem(original[1].id)); assertNull(repo.loadCatalogItem(future.id))
        assertEquals(1, repo.queryCatalog().size)
    }

    @Test fun receiptIsFirstEvenAfterFactDeletedAndDraftClosed(): Unit = runBlocking {
        val p = product()
        val draft = repo.checkpointDraft(DraftEntity(kind = "portion", selectedLocalDate = today, mealId = meal(), rawPayload = "{\"quantity\":\"80\"}", updatedAtMillis = 0), null)
        val op = Operation(draftId = draft.id, draftRevision = draft.draftRevision)
        val line = LineInput(p.snapshot, 80_000_000, p.id)
        val first = repo.addFood(today, meal(), line, op)
        val entry = repo.state.value.entries.single()
        repo.deleteFood(entry.id, entry.revision, Operation())
        repo.deleteCatalogItem(p.id, p.revision, Operation())
        val replay = repo.addFood(today, meal(), line, op)
        assertTrue(replay.replayed); assertEquals(first.entityIds, replay.entityIds)
        assertTrue(repo.state.value.entries.isEmpty())
        assertEquals("COMMITTED", repo.freshDraft(draft.id)?.status)
        rejected { repo.addFood(today, meal(), line.copy(quantityMicros = 90_000_000), op) }
    }

    @Test fun partialAndExplicitZeroAreDifferentAndLastDeleteUnconfirms(): Unit = runBlocking {
        rejected { repo.completeDay(today, true, false, Operation()) }
        repo.completeDay(today, true, true, Operation())
        assertEquals(1, repo.state.value.confirmations.size); assertTrue(repo.state.value.entries.isEmpty())
        val p = product(); repo.addFood(today, meal(), LineInput(p.snapshot, 80_000_000), Operation())
        assertEquals(1, repo.state.value.confirmations.size)
        val entry = repo.state.value.entries.single(); repo.deleteFood(entry.id, entry.revision, Operation())
        assertTrue(repo.state.value.entries.isEmpty()); assertTrue(repo.state.value.confirmations.isEmpty())
    }

    @Test fun failedBatchLeavesNeitherPartialFactsNorReceipt(): Unit = runBlocking {
        val p = product(); val line = LineInput(p.snapshot, 80_000_000)
        val op = Operation()
        rejected { repo.saveTemplate(null, "Неполный", listOf(line, line.copy(quantityMicros = 0)), null, op) }
        assertTrue(repo.state.value.templates.isEmpty()); assertTrue(repo.state.value.templateLines.isEmpty())
        assertNull(repo.recover(op.id))
        rejected { repo.addFood("2026-11-16", meal(), line, Operation()) }
        assertTrue(repo.state.value.entries.isEmpty())
    }
    @Test fun writerFailureRollsBackEarlierStatements(): Unit = runBlocking {
        rejected { repo.metadataTransaction { dao ->
            dao.put(WeightEntity(localDate = today, centiKg = 8012))
            dao.put(DayConfirmationEntity(localDate = today))
            throw IOException("Synthetic failure after both writes")
        } }
        assertNull(db.dao().weightOn(today)); assertNull(db.dao().confirmationOn(today))
    }

    @Test fun committedResultSurvivesSubsequentReadFailureAndRetry(): Unit = runBlocking {
        val operation = Operation()
        repo.beforeRefresh = { throw IOException("Synthetic read failure after transaction") }
        val result = repo.saveWeight(today, 8012, null, operation)
        assertFalse(result.replayed)
        assertEquals(listOf(db.dao().weightOn(today)!!.id), result.entityIds)
        assertEquals(result.entityIds, repo.recover(operation.id)!!.second.map { it.entityId })
        assertNotNull(repo.state.value.error)
        val replay = repo.saveWeight(today, 8012, null, operation)
        assertTrue(replay.replayed); assertEquals(result.entityIds, replay.entityIds)
        repo.beforeRefresh = null
        repo.setVisibleDate(today)
        assertEquals(8012, repo.state.value.weights.single().centiKg)
        assertNull(repo.state.value.error)
    }

    @Test fun staleEditorCannotOverwriteAndMeasurementsDoNotCompleteFood(): Unit = runBlocking {
        repo.saveWeight(today, 8012, null, Operation())
        val w = repo.state.value.weights.single()
        repo.saveWeight(today, 8123, w.revision, Operation())
        rejected { repo.saveWeight(today, 9000, w.revision, Operation()) }
        assertEquals(8123, repo.state.value.weights.single().centiKg)
        rejected { repo.saveSleep(today, 0, false, null, Operation()) }
        repo.saveSleep(today, 0, true, null, Operation())
        assertEquals(0, repo.state.value.sleeps.single().minutes)
        assertTrue(repo.state.value.confirmations.isEmpty())
    }

    @Test fun rawDraftAndDiscardFenceSurviveReopen(): Unit = runBlocking {
        val draft = repo.checkpointDraft(DraftEntity(kind = "weight", selectedLocalDate = today, rawPayload = "{\"weight\":\"1,\"}", updatedAtMillis = 0), null)
        repo.discardDraft(draft.id, draft.draftRevision)
        rejected { repo.checkpointDraft(draft.copy(rawPayload = "{\"weight\":\"80\"}"), draft.draftRevision) }
        assertEquals("DISCARDED", repo.freshDraft(draft.id)?.status)
        val open = repo.checkpointDraft(DraftEntity(kind = "sleep", rawPayload = "{\"hours\":\"oops\",\"minutes\":\"\"}", updatedAtMillis = 0), null)
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); db = DietDatabase.open(context, file.name); repo = DietRepository(context, scope, clock, db); repo.initialize()
        assertEquals(open.rawPayload, repo.freshDraft(open.id)?.rawPayload)
        assertTrue(repo.state.value.sleeps.isEmpty())
    }

    @Test fun draftHeaderPagingFindsOldDraftWithoutLoadingItsRawPayloadIntoState(): Unit = runBlocking {
        val payload = "{\"note\":\"${"x".repeat(65_536)}\"}"
        val old = DraftEntity(kind = "weight", selectedLocalDate = today, rawPayload = payload, updatedAtMillis = 0)
        repo.metadataTransaction { dao ->
            dao.put(old)
            repeat(24) { i -> dao.put(DraftEntity(kind = "quick", selectedLocalDate = today, rawPayload = payload, updatedAtMillis = i + 1L)) }
            dao.put(DraftEntity(kind = "weight", targetEntityId = newId(), selectedLocalDate = today, rawPayload = payload, updatedAtMillis = 100))
        }
        repo.setVisibleDate(today)
        assertEquals(20, repo.state.value.drafts.size)
        assertFalse(repo.state.value.drafts.any { it.id == old.id })
        assertEquals(6, repo.queryDraftHeaders(20, 20).size)
        assertEquals(old.id, repo.findOpenDraft("weight", selectedLocalDate = today)?.id)
        assertEquals(payload, repo.freshDraft(old.id)?.rawPayload)
        repo.discardDraft(old.id, old.draftRevision)
        assertNull(repo.findOpenDraft("weight", selectedLocalDate = today))
    }

    @Test fun goalTimelineConflictAndDateEffectiveValues(): Unit = runBlocking {
        val revision = repo.state.value.goalTimelineRevision
        repo.saveGoal("2026-11-01", nutrients, null, revision, Operation())
        rejected { repo.saveGoal("2026-11-10", nutrients.copy(kcal = 200_000_000), null, revision, Operation()) }
        repo.saveGoal("2026-11-10", nutrients.copy(kcal = 200_000_000), null, repo.state.value.goalTimelineRevision, Operation())
        assertEquals(2, repo.state.value.goals.size)
        assertEquals(150_000_000, repo.state.value.goals.first().nutrients.kcal)
        assertTrue(repo.state.value.entries.isEmpty())
    }

    @Test fun futureDraftCannotBeDowngradedOrCommitted(): Unit = runBlocking {
        val draft = DraftEntity(kind = "weight", draftFormatVersion = 2, selectedLocalDate = today, rawPayload = "{\"newField\":\"kept\"}", updatedAtMillis = 0)
        repo.metadataTransaction { it.put(draft) }
        rejected { repo.checkpointDraft(draft.copy(draftFormatVersion = 1), draft.draftRevision) }
        rejected { repo.saveWeight(today, 8000, null, Operation(draftId = draft.id, draftRevision = draft.draftRevision)) }
        assertEquals(draft, repo.freshDraft(draft.id)); assertTrue(repo.state.value.weights.isEmpty())
    }

    @Test fun productionClockReadsChangedTimezoneWithoutRecreation() {
        val original = TimeZone.getDefault()
        try {
            val retained = SystemLocalClock
            TimeZone.setDefault(TimeZone.getTimeZone("GMT-12:00"))
            assertEquals(ZoneOffset.ofHours(-12), retained.zone.rules.getOffset(Instant.EPOCH))
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+14:00"))
            assertEquals(ZoneOffset.ofHours(14), retained.zone.rules.getOffset(Instant.EPOCH))
            assertEquals(ZoneOffset.UTC, clock.zone)
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun visibleDayDoesNotHideOlderHistoryOrCompletion(): Unit = runBlocking {
        val previous = "2026-11-14"
        repo.addQuickFood(previous, meal(), "Старая запись", nutrients, Operation())
        repo.completeDay(previous, true, false, Operation())
        repo.addQuickFood(today, meal(), "Сегодня", nutrients, Operation())
        assertEquals(listOf(today), repo.state.value.entries.map { it.localDate })
        assertFalse(repo.dayComplete(today)); assertTrue(repo.dayComplete(previous))
        assertEquals(listOf(today, previous), repo.queryHistory(previous, today).map { it.localDate })
        assertEquals(previous, repo.queryHistory(previous, today, "СТАРАЯ").single().localDate)
        repo.setVisibleDate(previous)
        assertEquals(previous, repo.state.value.entries.single().localDate)
        assertEquals(1L, repo.state.value.completedDayCount)
    }

    @Test fun unsupportedNewerSchemaIsNotDestroyed(): Unit = runBlocking {
        repo.saveWeight(today, 8012, null, Operation())
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close()
        VerifiedSQLiteDriver().open(file.absolutePath).use { it.execSQL("PRAGMA user_version = 2") }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); db = DietDatabase.open(context, file.name); repo = DietRepository(context, scope, clock, db)
        rejected { repo.initialize() }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
        VerifiedSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.prepare("PRAGMA user_version").use { assertTrue(it.step()); assertEquals(2L, it.getLong(0)) }
            connection.prepare("SELECT centiKg FROM weight_records").use { assertTrue(it.step()); assertEquals(8012L, it.getLong(0)) }
        }
    }

    @Test fun planTransferCanBeIntentionallyRepeatedButRetryCannotDuplicate(): Unit = runBlocking {
        val p = product(); repo.savePlan(null, "План", today, meal(), listOf(LineInput(p.snapshot, 80_000_000)), null, Operation())
        val plan = repo.state.value.plans.single(); val op = Operation()
        repo.transferPlan(plan.id, plan.revision, today, meal(), op)
        repo.transferPlan(plan.id, plan.revision, today, meal(), op)
        assertEquals(1, repo.state.value.entries.size)
        repo.transferPlan(plan.id, plan.revision, today, meal(), Operation())
        assertEquals(2, repo.state.value.entries.size)
        repo.deletePlan(plan.id, plan.revision, Operation())
        assertEquals(2, repo.queryTransfers(plan.id).size); assertEquals(2, repo.state.value.entries.size)
    }

    @Test fun preferenceGroupsAreIsolatedAndReplayIsFenced(): Unit = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefFile = File(context.noBackupFilesDir, "diet/prefs/test-${newId()}.preferences_pb")
        prefFile.parentFile!!.mkdirs()
        try {
            val store = PreferenceDataStoreFactory.create(scope = testScope, produceFile = { prefFile })
            val prefs = VisualPreferencesRepository(store, testScope)
            withTimeout(5000) { prefs.state.first { it.ready } }
            val draft = prefs.checkpoint(PreferenceDraft(newId(), "appearance", 1, rawFields = mapOf("accent" to "berry", "cover" to "2")), null)
            prefs.apply("theme", mapOf("theme" to "dark"), 1, newId())
            val token = newId(); prefs.apply("appearance", draft.rawFields, 1, token, draft.id, draft.draftRevision)
            prefs.apply("appearance", draft.rawFields, 1, token, draft.id, draft.draftRevision)
            rejected { prefs.apply("appearance", draft.rawFields, 2, token) }
            assertTrue(prefs.recoverPreference("appearance", token, draft.rawFields, 1))
            rejected { prefs.recoverPreference("appearance", token, mapOf("accent" to "ocean", "cover" to "2"), 1) }
            assertEquals("COMMITTED", prefs.freshPreferenceDraft("appearance")?.status)
            withTimeout(5000) { prefs.state.first { it.accent == "berry" && it.theme == "dark" } }
            assertEquals(2L, prefs.state.value.revisions["appearance"])
            prefs.apply("appearance", mapOf("accent" to "ocean", "cover" to "3"), 2, newId())
            rejected { prefs.recoverPreference("appearance", token, draft.rawFields, 1) }
            rejected { prefs.apply("appearance", draft.rawFields, 1, token) }
            withTimeout(5000) { prefs.state.first { it.accent == "ocean" && it.cover == "3" } }
            assertTrue(prefFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        } finally {
            testScope.coroutineContext[Job]!!.cancelAndJoin()
            check(!prefFile.exists() || prefFile.delete())
        }
    }

    @Test fun futurePreferenceFormatIsNeverResetByAnyMutation(): Unit = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefFile = File(context.noBackupFilesDir, "diet/prefs/test-${newId()}.preferences_pb")
        prefFile.parentFile!!.mkdirs()
        try {
            val store = PreferenceDataStoreFactory.create(scope = testScope, produceFile = { prefFile })
            val prefs = VisualPreferencesRepository(store, testScope)
            val draft = prefs.checkpoint(PreferenceDraft(newId(), "theme", 1, rawFields = mapOf("theme" to "dark")), null)
            store.edit { it[longPreferencesKey("formatVersion")] = 2 }
            val before = store.data.first()
            rejected { prefs.checkpoint(draft, draft.draftRevision) }
            rejected { prefs.discard(draft.group, draft.id, draft.draftRevision) }
            rejected { prefs.apply("theme", draft.rawFields, 1, newId(), draft.id, draft.draftRevision) }
            assertEquals(before, store.data.first())
        } finally {
            testScope.coroutineContext[Job]!!.cancelAndJoin()
            check(!prefFile.exists() || prefFile.delete())
        }
    }

    @Test fun viewContextUsesCanonicalMapSerializationOnRealDataStore(): Unit = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefFile = File(context.noBackupFilesDir, "diet/prefs/test-${newId()}.preferences_pb")
        prefFile.parentFile!!.mkdirs()
        try {
            val store = PreferenceDataStoreFactory.create(scope = testScope, produceFile = { prefFile })
            val prefs = VisualPreferencesRepository(store, testScope)
            val fields = linkedMapOf("screen" to "welcome", "query" to "Синтетический контекст", "date" to today)
            val reversed = fields.entries.reversed().associate { it.key to it.value }
            val token = newId()
            prefs.apply("view", fields, 1, token)
            assertTrue(prefs.recoverPreference("view", token, reversed, 1))
            prefs.apply("view", reversed.toSortedMap(), 1, token)
            prefs.apply("theme", mapOf("theme" to "dark"), 1, newId())
            withTimeout(5000) { prefs.state.first { it.viewContext == fields && it.theme == "dark" } }
            assertEquals(2L, prefs.state.value.revisions["view"])
            assertTrue(prefFile.isFile)
        } finally {
            testScope.coroutineContext[Job]!!.cancelAndJoin()
            check(!prefFile.exists() || prefFile.delete())
        }
    }
}
