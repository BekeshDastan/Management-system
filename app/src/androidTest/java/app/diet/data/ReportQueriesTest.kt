package app.diet.data

import android.content.Context
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.domain.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic isolated databases only; no production database, provider or photo bytes are used. */
@RunWith(AndroidJUnit4::class)
class ReportQueriesTest {
    private lateinit var db: DietDatabase
    private lateinit var file: File
    private val mealId = newId()
    private fun day(value: Int) = LocalDate.of(2026, 11, value)

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "report-test-${newId()}.db"
        file = File(context.noBackupFilesDir, "diet/db/$name")
        db = DietDatabase.open(context, name)
        write { it.put(MealEntity(mealId, "Обед", NameKeys.normalize("Обед"), ordinal = 0)) }
    }

    @After fun cleanup() {
        if (::db.isInitialized) db.close()
        if (::file.isInitialized) listOf(file, File(file.path + "-wal"), File(file.path + "-shm")).forEach { it.delete() }
    }

    private suspend fun write(block: suspend (DietDao) -> Unit) {
        db.useWriterConnection { connection -> connection.immediateTransaction { block(db.dao()) } }
    }

    private fun micro(k: Long, p: Long = 0, f: Long = 0, c: Long = 0) = MicroNutrients(k * Numbers.MICRO_FACTOR, p * Numbers.MICRO_FACTOR, f * Numbers.MICRO_FACTOR, c * Numbers.MICRO_FACTOR)
    private fun entry(date: LocalDate, values: MicroNutrients, id: String = newId()): FoodEntryEntity = FoodEntryEntity(
        id = id, localDate = date.toString(), mealId = mealId, ordinal = 0,
        snapshot = SnapshotColumns.from(NutritionSnapshot("Пример", FoodUnit.PORTION, Numbers.MICRO_FACTOR, values.toDomain())),
        quantityMicros = Numbers.MICRO_FACTOR, createdByOperationId = newId(),
    )

    @Test fun referenceWeekAndMomentOnlyActivityUseIndependentSamples() = runBlocking {
        write { dao ->
            listOf(8 to micro(2000, 100, 70, 240), 9 to micro(850, 40, 25, 115), 12 to micro(2200, 110, 80, 260), 13 to micro(1200, 65, 40, 140), 14 to micro(1800, 90, 60, 220)).forEach { (d, values) -> dao.put(entry(day(d), values)) }
            listOf(8, 11, 12, 14).forEach { dao.put(DayConfirmationEntity(localDate = day(it).toString())) }
            listOf(8 to 8020, 10 to 8000, 11 to 7980, 13 to 7990, 14 to 8001).forEach { (d, weight) -> dao.put(WeightEntity(localDate = day(d).toString(), centiKg = weight)) }
            listOf(8 to 420, 9 to 480, 11 to 0, 12 to 421, 13 to 450).forEach { (d, minutes) -> dao.put(SleepEntity(localDate = day(d).toString(), minutes = minutes)) }
            dao.put(GoalEntity(startDate = day(9).toString(), nutrients = micro(2000, 100, 70, 250)))
            dao.put(GoalEntity(startDate = day(13).toString(), nutrients = micro(2100, 105, 75, 260)))
            val moment = MomentEntity(localDate = day(10).toString(), mood = "good", note = "\u0085\u00A0\u2007")
            dao.put(moment)
            // Logical photo records remain countable even if their file is unavailable.
            repeat(2) { index ->
                val asset = MediaAssetEntity(objectKey = "media/objects/${newId()}.jpg", width = 10, height = 10, state = "READY", createdAtMillis = 0)
                dao.put(asset)
                dao.put(PhotoEntity(momentId = moment.id, assetId = asset.id, ordinal = index.toLong()))
            }
            dao.put(MomentEntity(localDate = day(13).toString(), mood = "tired", note = "Заметка"))
            dao.put(MomentEntity(localDate = day(14).toString(), note = "\u0085\u00A0\u2007"))
        }
        val report = ReportQueries.read(db, day(8), day(14))
        assertEquals(7L, report.nutrition.m)
        assertEquals(4, report.nutrition.n)
        assertEquals(3, report.nutrition.q)
        assertEquals("1500,0", report.nutrition.mean?.kcal?.format())
        assertEquals("1333,3", report.nutrition.comparison?.actualMean?.kcal?.format())
        assertEquals("2033,3", report.nutrition.comparison?.targetMean?.kcal?.format())
        assertEquals("79,98", report.weight.meanKg?.format(2))
        assertEquals(354, report.sleep.roundedMeanMinutes)
        assertEquals(7L, report.recordedDays)
        assertEquals(2L, report.moments.photoCount)
        assertEquals(1L, report.moments.noteCount)
        assertEquals(2L, report.moments.recordedDays)
        assertEquals(1L, report.moments.moodCounts["good"])
        assertEquals(1L, report.moments.moodCounts["tired"])
        assertEquals(7, report.series.size)
        assertTrue(report.series.none { it.aggregated })
        assertEquals(0, report.series.single { it.start == day(10) }.nutritionCompleteDays)
        assertEquals(1L, report.series.single { it.start == day(10) }.momentDays)
        val page = ReportQueries.pageReportDays(db, day(8), day(14), offset = 2, limit = 3)
        assertEquals(listOf(day(10), day(11), day(12)), page.days.map { it.date })
        assertEquals(DayStatus.MISSING, page.days[0].food.status)
        assertEquals(DayStatus.CONFIRMED_ZERO, page.days[1].food.status)
        assertEquals(0, page.days[1].sleepMinutes)
        assertEquals(Rational.of(2000), page.days[0].goal?.kcal)
        assertTrue(page.hasMore)
    }

    @Test fun wholeSupportedDateRangeUsesBoundedBucketsAndReachableExactPages() = runBlocking {
        write { dao ->
            dao.put(entry(day(8), micro(1500)))
            dao.put(DayConfirmationEntity(localDate = day(8).toString()))
            dao.put(GoalEntity(startDate = day(1).toString(), nutrients = micro(2000)))
        }
        val report = ReportQueries.read(db, DomainDates.MIN, DomainDates.MAX)
        assertEquals(DomainDates.TOTAL_SUPPORTED_DAYS, report.nutrition.m)
        assertEquals(1, report.nutrition.n)
        assertEquals(Rational.of(1500), report.nutrition.mean?.kcal)
        assertTrue(report.series.size <= 600)
        assertEquals(DomainDates.MIN, report.series.first().start)
        assertEquals(DomainDates.MAX, report.series.last().endInclusive)
        assertEquals(DomainDates.TOTAL_SUPPORTED_DAYS, report.series.sumOf { it.calendarDays })
        assertEquals(1, report.series.sumOf { it.nutritionCompleteDays })
        assertTrue(report.series.filter { it.nutritionCompleteDays == 0 }.all { it.nutritionMean == null })
        assertTrue(report.series.all { it.aggregated && it.hasNutritionGaps })
        val lastPage = ReportQueries.pageReportDays(db, DomainDates.MIN, DomainDates.MAX, DomainDates.TOTAL_SUPPORTED_DAYS - 1, 40)
        assertEquals(1, lastPage.days.size)
        assertEquals(DomainDates.MAX, lastPage.days.single().date)
        assertEquals(DayStatus.MISSING, lastPage.days.single().food.status)
        assertEquals(Rational.of(2000), lastPage.days.single().goal?.kcal)
        assertFalse(lastPage.hasMore)
        assertTrue(ReportQueries.pageReportDays(db, DomainDates.MIN, DomainDates.MAX, DomainDates.TOTAL_SUPPORTED_DAYS, 40).days.isEmpty())
    }

    @Test fun concurrentWritesCannotMixFoodAndGoalFromDifferentSnapshots() = runBlocking {
        val foodId = newId()
        val goalId = newId()
        write { dao ->
            dao.put(entry(day(8), micro(100), foodId))
            dao.put(DayConfirmationEntity(localDate = day(8).toString()))
            dao.put(GoalEntity(goalId, day(8).toString(), micro(200)))
        }
        coroutineScope {
            val writer = launch(Dispatchers.IO) {
                repeat(20) { index ->
                    val kcal = if (index % 2 == 0) 200L else 100L
                    write { dao ->
                        dao.put(entry(day(8), micro(kcal), foodId))
                        delay(2)
                        dao.put(GoalEntity(goalId, day(8).toString(), micro(kcal * 2)))
                    }
                }
            }
            repeat(15) {
                val result = ReportQueries.read(db, day(8), day(8))
                val comparison = checkNotNull(result.nutrition.comparison)
                assertEquals(comparison.actualMean.kcal * Rational.of(2), comparison.targetMean.kcal)
            }
            writer.join()
        }
    }
}
