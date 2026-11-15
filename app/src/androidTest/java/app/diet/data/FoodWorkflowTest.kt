package app.diet.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.domain.FoodUnit
import app.diet.domain.Nutrients
import app.diet.ui.UiFormSession
import app.diet.ui.UiLine
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Synthetic isolated data only; no production database or user journal is touched. */
@RunWith(AndroidJUnit4::class)
class FoodWorkflowTest {
    private lateinit var db: DietDatabase
    private lateinit var repo: DietRepository
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dbRoot: File
    private val date = "2026-11-15"

    @Before fun setUp(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val name = "food-workflow-${newId()}.db"
        dbRoot = File(context.noBackupFilesDir, "diet/db")
        file = File(dbRoot, name)
        db = DietDatabase.open(context, name)
        repo = DietRepository(context, scope, Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC), db)
        repo.initialize()
    }

    @After fun tearDown(): Unit = runBlocking {
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::db.isInitialized) db.close()
        if (::file.isInitialized) {
            check(file.canonicalFile.parentFile == dbRoot.canonicalFile && file.name.startsWith("food-workflow-"))
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { check(!it.exists() || it.delete()) }
        }
    }

    private suspend fun product(name: String = "Синтетическое яблоко", kcal: Long = 100_000_000): CatalogItemEntity {
        val id = repo.saveProduct(null, name, FoodUnit.GRAM, null, MicroNutrients(kcal, 10_000_000, 2_000_000, 20_000_000), baseRevision = null, operation = Operation()).entityIds.single()
        return requireNotNull(repo.loadCatalogItem(id))
    }
    private fun meal(index: Int = 0) = repo.state.value.meals[index].id
    private suspend fun rows() = repo.queryHistory("2026-11-01", date, limit = 100)

    @Test fun reviewedBatchCommitsExactlyOnceAndKeepsCapturedSnapshots(): Unit = runBlocking {
        val a = product()
        val b = product("Синтетическая груша", 200_000_000)
        val inputs = listOf(LineInput(a.snapshot, 60_000_000, a.id), LineInput(b.snapshot, 40_000_000, b.id))
        repo.saveProduct(a.id, "Теперь другое название", FoodUnit.GRAM, null, MicroNutrients(999_000_000, 0, 0, 0), baseRevision = a.revision, operation = Operation())
        val operation = Operation()
        val results = coroutineScope { listOf(async { repo.addFoods(date, meal(), inputs, operation) }, async { repo.addFoods(date, meal(), inputs, operation) }).awaitAll() }
        assertEquals(results[0].entityIds, results[1].entityIds)
        assertEquals(1, results.count { it.replayed })
        val entries = rows()
        assertEquals(2, entries.size)
        assertEquals("140,0", Nutrients.sum(entries.map { it.nutrients() }).kcal.format(1))
        assertEquals(setOf("Синтетическое яблоко", "Синтетическая груша"), entries.map { it.displayName() }.toSet())
        assertEquals(setOf(60_000_000L, 40_000_000L), entries.map { it.quantityMicros }.toSet())
        assertEquals(operation.id, entries.first().createdByOperationId)
    }

    @Test fun invalidLastBatchLineLeavesNoFoodRecentOrReceipt(): Unit = runBlocking {
        val p = product()
        val operation = Operation()
        val result = runCatching { repo.addFoods(date, meal(), listOf(LineInput(p.snapshot, 10_000_000, p.id), LineInput(p.snapshot, 0, p.id)), operation) }
        assertTrue(result.isFailure)
        assertTrue(rows().isEmpty())
        assertTrue(repo.state.value.recents.isEmpty())
        assertNull(repo.recover(operation.id))
    }

    @Test fun batchReceiptSurvivesDeletionAndClosedDraft(): Unit = runBlocking {
        val p = product()
        val draft = UiFormSession.create(repo, scope, "batch-food", fields = mapOf("date" to date, "meal" to meal()), lines = listOf(UiLine.from(LineInput(p.snapshot, 15_000_000, p.id))))
        val op = draft.freeze()
        val result = repo.addFoods(date, meal(), draft.data.lines.map { it.input() }, op)
        val entry = requireNotNull(repo.loadEntry(result.entityIds.single()))
        repo.deleteFood(entry.id, entry.revision, Operation())
        val replay = repo.addFoods(date, meal(), draft.data.lines.map { it.input() }, op)
        assertTrue(replay.replayed)
        assertEquals(result.entityIds, replay.entityIds)
        assertTrue(rows().isEmpty())
        assertEquals("COMMITTED", repo.freshDraft(draft.entity.id)?.status)
        draft.stopDebounce()
    }

    @Test fun batchDraftRetainsRawQuantitiesAndLineIdsBeforeAnyFact(): Unit = runBlocking {
        val p = product()
        val original = UiFormSession.create(repo, scope, "batch-food", fields = mapOf("date" to date, "meal" to meal()), lines = listOf(UiLine.from(LineInput(p.snapshot, 10_000_000, p.id)), UiLine.from(LineInput(p.snapshot, 20_000_000, p.id))))
        original.lineQuantity(original.data.lines.first().id, "72,")
        val checkpoint = original.checkpoint()
        original.stopDebounce()
        val restored = UiFormSession.restore(repo, scope, requireNotNull(repo.freshDraft(checkpoint.id)))
        assertEquals(original.data.lines, restored.data.lines)
        assertEquals("72,", restored.data.lines.first().quantity)
        assertTrue(rows().isEmpty())
        restored.stopDebounce()
    }

    @Test fun movePreservesIdentitySnapshotAndClearsEmptySourceCompletion(): Unit = runBlocking {
        val p = product()
        val addOp = Operation()
        val id = repo.addFood("2026-11-14", meal(), LineInput(p.snapshot, 125_000_000, p.id), addOp).entityIds.single()
        repo.completeDay("2026-11-14", true, false, Operation())
        val before = requireNotNull(repo.loadEntry(id))
        val moveOp = Operation()
        repo.moveFood(id, before.revision, date, meal(1), moveOp)
        val moved = requireNotNull(repo.loadEntry(id))
        assertEquals(before.snapshot, moved.snapshot)
        assertEquals(before.quantityMicros, moved.quantityMicros)
        assertEquals(before.createdByOperationId, moved.createdByOperationId)
        assertEquals(date, moved.localDate)
        assertEquals(meal(1), moved.mealId)
        assertEquals(before.revision + 1, moved.revision)
        assertFalse(repo.dayComplete("2026-11-14"))
        assertFalse(repo.dayComplete(date))
        assertTrue(repo.moveFood(id, before.revision, date, meal(1), moveOp).replayed)
        assertEquals(1, rows().size)
    }

    @Test fun moveConflictAndArchivedMealDoNotModifySource(): Unit = runBlocking {
        val p = product()
        val id = repo.addFood(date, meal(), LineInput(p.snapshot, 100_000_000, p.id), Operation()).entityIds.single()
        val before = requireNotNull(repo.loadEntry(id))
        val target = repo.state.value.meals[1]
        repo.saveMeal(target.id, target.name, true, target.revision, Operation())
        assertTrue(runCatching { repo.moveFood(id, before.revision, date, target.id, Operation()) }.isFailure)
        assertTrue(runCatching { repo.moveFood(id, before.revision + 1, date, meal(2), Operation()) }.isFailure)
        assertEquals(before, repo.loadEntry(id))
    }

    @Test fun repeatKeepsQuickKindAndHistoricalNutritionWithIndependentIdentity(): Unit = runBlocking {
        val nutrients = MicroNutrients(123_456_789, 5_000_001, 6_000_002, 7_000_003)
        val sourceId = repo.addQuickFood("2026-11-14", meal(), "Синтетический быстрый приём", nutrients, Operation()).entityIds.single()
        val source = requireNotNull(repo.loadEntry(sourceId))
        val op = Operation()
        val result = repo.repeatFood(source.id, source.revision, date, meal(1), op)
        val repeated = requireNotNull(repo.loadEntry(result.entityIds.single()))
        assertNotEquals(source.id, repeated.id)
        assertEquals("quick", repeated.kind)
        assertEquals(source.quick, repeated.quick)
        assertEquals(source.quickName, repeated.quickName)
        assertNull(repeated.snapshot)
        assertEquals(1L, repeated.revision)
        assertEquals(op.id, repeated.createdByOperationId)
        assertTrue(repo.repeatFood(source.id, source.revision, date, meal(1), op).replayed)
        assertEquals(2, rows().size)
        repo.editFood(source.id, null, nutrients.copy(kcal = 222_000_000), source.quickName, source.revision, Operation())
        assertTrue(runCatching { repo.repeatFood(source.id, source.revision, date, meal(1), Operation()) }.isFailure)
        assertEquals(nutrients, repo.loadEntry(repeated.id)?.quick)
    }

    @Test fun favoriteThenRecentOrderingIsStableAcrossPages(): Unit = runBlocking {
        val alphabeticalFirst = product("Альфа")
        val recent = product("Бета")
        val favorite = product("Яблоко")
        repo.addFood(date, meal(), LineInput(recent.snapshot, 100_000_000, recent.id), Operation())
        repo.toggleFavorite(favorite.id, true, favorite.revision, Operation())
        val firstPage = repo.queryCatalog(limit = 2)
        val secondPage = repo.queryCatalog(offset = 2, limit = 2)
        assertEquals(listOf(favorite.id, recent.id), firstPage.map { it.id })
        assertEquals(listOf(alphabeticalFirst.id), secondPage.map { it.id })
        assertEquals(listOf(recent.id), repo.queryCatalog(recentOnly = true).map { it.id })
    }
}
