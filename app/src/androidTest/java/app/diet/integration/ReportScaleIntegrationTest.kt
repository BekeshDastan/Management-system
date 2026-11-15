package app.diet.integration

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.*
import app.diet.domain.*
import java.io.File
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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

/** S2 food-history correctness slice. Timings are emulator metadata, not Samsung performance PASS. */
@RunWith(AndroidJUnit4::class)
class ReportScaleIntegrationTest {
    private lateinit var db: DietDatabase
    private lateinit var repository: DietRepository
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private val clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC)
    private val first = LocalDate.of(2016, 1, 1)
    private val last = LocalDate.of(2025, 12, 31)
    private val mealId = newId()

    @Before fun setup(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "report-scale-${newId()}.db"
        file = File(context.noBackupFilesDir, "diet/db/$name")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        db = DietDatabase.open(context, name)
        repository = DietRepository(context, scope, clock, db)
        db.useWriterConnection { connection -> connection.immediateTransaction {
            db.dao().put(MealEntity(mealId, "Синтетический приём", NameKeys.normalize("Синтетический приём"), ordinal = 0))
        } }
    }

    @After fun cleanup(): Unit = runBlocking {
        if (::scope.isInitialized) scope.coroutineContext[Job]?.cancelAndJoin()
        if (::db.isInitialized) db.close()
        if (::file.isInitialized) listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
    }

    @Test fun hundredThousandFactsKeepExactSamplesAndBoundedReachablePresentation(): Unit = runBlocking {
        DomainDates.validateFactDate(last, LocalDate.now(clock))
        val period = DatePeriod(first, last)
        assertEquals(3653L, period.dayCount)
        val ordinary = FACTS / FOOD_DAYS
        val extra = FACTS % FOOD_DAYS
        var completeFoodFacts = 0L
        var goalFoodFacts = 0L
        var completeDays = 0
        var goalDays = 0
        var goalKcalSum = 0L
        for (index in 0 until FOOD_DAYS) {
            if (index % 2 == 0) {
                completeDays++
                val rows = ordinary + if (index < extra) 1 else 0
                completeFoodFacts += rows
                if (index >= FIRST_GOAL_DAY) {
                    goalDays++
                    goalFoodFacts += rows
                    goalKcalSum += if (index < SECOND_GOAL_DAY) 1000 else 2000
                }
            }
        }
        // A separate confirmed empty day belongs to N and Q, with no invented food rows.
        completeDays++
        goalDays++
        goalKcalSum += 2000
        assertEquals(50_000L, completeFoodFacts)
        assertEquals(24_624L, goalFoodFacts)
        assertEquals(1826, completeDays)
        assertEquals(913, goalDays)
        assertEquals(1_239_000L, goalKcalSum)

        val pssBefore = Debug.getPss()
        val seedStart = SystemClock.elapsedRealtime()
        seedFood(ordinary, extra)
        db.useWriterConnection { connection -> connection.immediateTransaction {
            val dao = db.dao()
            for (index in 0 until FOOD_DAYS step 2) dao.put(DayConfirmationEntity(localDate = first.plusDays(index.toLong()).toString()))
            dao.put(DayConfirmationEntity(localDate = first.plusDays(FOOD_DAYS.toLong()).toString()))
            dao.put(GoalEntity(startDate = first.plusDays(FIRST_GOAL_DAY.toLong()).toString(), nutrients = goal(1000)))
            dao.put(GoalEntity(startDate = first.plusDays(SECOND_GOAL_DAY.toLong()).toString(), nutrients = goal(2000)))
            dao.put(WeightEntity(localDate = first.toString(), centiKg = 8000))
            dao.put(SleepEntity(localDate = first.toString(), minutes = 0))
            // This date has measurements but missing food; the final date has no records at all.
            val measurementOnly = first.plusDays(FOOD_DAYS.toLong() + 1).toString()
            dao.put(WeightEntity(localDate = measurementOnly, centiKg = 8001))
            dao.put(SleepEntity(localDate = measurementOnly, minutes = 421))
        } }
        val seedMs = SystemClock.elapsedRealtime() - seedStart
        val storedFacts = db.useReaderConnection { connection -> connection.usePrepared("SELECT COUNT(*) FROM food_entries") { statement -> check(statement.step()); statement.getLong(0) } }
        assertEquals(FACTS.toLong(), storedFacts)
        val readStart = SystemClock.elapsedRealtime()
        val report = try { repository.readReport(first, last) } finally {
            Log.i("DietScaleCheck", "scope=S2-food-correctness-only api=${Build.VERSION.SDK_INT} facts=$FACTS days=${period.dayCount} seedMs=$seedMs readMs=${SystemClock.elapsedRealtime() - readStart} pssBeforeKiB=$pssBefore pssAfterKiB=${Debug.getPss()}; not-a-physical-device-performance-result")
        }
        assertEquals(period, report.period)
        assertEquals(completeDays, report.nutrition.n)
        assertEquals(goalDays, report.nutrition.q)
        assertEquals(1825, report.nutrition.partialDays)
        assertEquals(2L, report.nutrition.missingDays)
        assertEquals(3652L, report.recordedDays)
        assertTrue(report.series.size <= 600)
        assertEquals(period.dayCount, report.series.sumOf { it.calendarDays })
        assertEquals(completeDays, report.series.sumOf { it.nutritionCompleteDays })
        assertEquals(1825, report.series.sumOf { it.nutritionPartialDays })

        val mean = checkNotNull(report.nutrition.mean)
        val comparison = checkNotNull(report.nutrition.comparison)
        val numerators = longArrayOf(1, 2, 5, 13)
        val denominators = longArrayOf(3, 7, 11, 17)
        for (component in 0..3) {
            assertFraction(select(mean, component), completeFoodFacts * numerators[component], denominators[component] * completeDays)
            assertFraction(select(comparison.actualMean, component), goalFoodFacts * numerators[component], denominators[component] * goalDays)
            val all = FractionSum()
            val completed = FractionSum()
            for (point in report.series) {
                if (point.nutritionCompleteDays > 0) {
                    val value = select(checkNotNull(point.nutritionMean), component)
                    completed.add(value, point.nutritionCompleteDays.toLong())
                    all.add(value, point.nutritionCompleteDays.toLong())
                }
                if (point.nutritionPartialDays > 0) all.add(select(checkNotNull(point.partialNutritionMean), component), point.nutritionPartialDays.toLong())
            }
            completed.assertEquals(completeFoodFacts * numerators[component], denominators[component])
            all.assertEquals(FACTS * numerators[component], denominators[component])
        }
        assertFraction(comparison.targetMean.kcal, goalKcalSum, goalDays.toLong())
        assertFraction(comparison.targetMean.protein, goalKcalSum, 10L * goalDays)
        assertTrue(comparison.targetMean.fat.isZero)
        assertNull(comparison.percentages.fat)
        assertFraction(comparison.targetMean.carbs, goalKcalSum, 4L * goalDays)
        assertFraction(checkNotNull(comparison.percentages.kcal), goalFoodFacts * 100, 3 * goalKcalSum)
        assertEquals(2, report.weight.n)
        assertFraction(checkNotNull(report.weight.meanKg), 16_001, 200)
        assertEquals(2, report.sleep.n)
        assertEquals(211, report.sleep.roundedMeanMinutes)
        assertEquals(FACTS.toLong(), report.topFoods.single().count)

        val page = repository.pageReportDays(first, last, period.dayCount - 40, 40)
        assertEquals(40, page.days.size)
        assertEquals(last.minusDays(39), page.days.first().date)
        assertEquals(last, page.days.last().date)
        assertFalse(page.hasMore)
        val zero = page.days.single { it.date == first.plusDays(FOOD_DAYS.toLong()) }
        assertEquals(DayStatus.CONFIRMED_ZERO, zero.food.status)
        assertTrue(zero.food.nutrients.kcal.isZero)
        val measurements = page.days.single { it.date == first.plusDays(FOOD_DAYS.toLong() + 1) }
        assertEquals(DayStatus.MISSING, measurements.food.status)
        assertEquals(8001L, measurements.weightCentiKg)
        val missing = page.days.last()
        assertEquals(DayStatus.MISSING, missing.food.status)
        assertNull(missing.weightCentiKg)
        assertNull(missing.sleepMinutes)
        assertEquals(0L, report.moments.recordedDays)
    }

    private suspend fun seedFood(ordinary: Int, extra: Int) {
        val prefix = newId().substring(0, 24)
        val operation = newId()
        var inserted = 0
        var dayIndex = 0
        var ordinal = 0
        var date = first.toString()
        while (inserted < FACTS) {
            val chunk = minOf(1000, FACTS - inserted)
            db.useWriterConnection { connection -> connection.immediateTransaction {
                connection.usePrepared(INSERT) { statement ->
                    repeat(chunk) {
                        statement.bindText(1, prefix + inserted.toString(16).padStart(12, '0'))
                        statement.bindText(2, date)
                        statement.bindText(3, mealId)
                        statement.bindLong(4, ordinal.toLong())
                        statement.bindText(5, operation)
                        check(!statement.step())
                        statement.reset()
                        inserted++
                        ordinal++
                        if (ordinal == ordinary + if (dayIndex < extra) 1 else 0) {
                            dayIndex++
                            ordinal = 0
                            date = first.plusDays(dayIndex.toLong()).toString()
                        }
                    }
                }
            } }
        }
        assertEquals(FOOD_DAYS, dayIndex)
        assertEquals(0, ordinal)
    }

    private fun goal(kcal: Long) = MicroNutrients(kcal * 1_000_000, kcal * 100_000, 0, kcal * 250_000)
    private fun select(value: Nutrients, component: Int): Rational = when (component) { 0 -> value.kcal; 1 -> value.protein; 2 -> value.fat; else -> value.carbs }

    private fun assertFraction(actual: Rational, numerator: Long, denominator: Long) {
        assertEquals(BigInteger.valueOf(numerator).multiply(actual.denominator), actual.numerator.multiply(BigInteger.valueOf(denominator)))
    }

    /** Independent BigInteger reference arithmetic; no ReportEngine/Rational operators as oracle. */
    private class FractionSum {
        var p = BigInteger.ZERO
        var q = BigInteger.ONE
        fun add(value: Rational, count: Long) {
            val numerator = p.multiply(value.denominator).add(value.numerator.multiply(BigInteger.valueOf(count)).multiply(q))
            val denominator = q.multiply(value.denominator)
            val common = numerator.gcd(denominator)
            p = numerator.divide(common)
            q = denominator.divide(common)
        }
        fun assertEquals(numerator: Long, denominator: Long) {
            org.junit.Assert.assertEquals(BigInteger.valueOf(numerator).multiply(q), p.multiply(BigInteger.valueOf(denominator)))
        }
    }

    companion object {
        private const val FACTS = 100_000
        private const val FOOD_DAYS = 3650
        private const val FIRST_GOAL_DAY = 1825
        private const val SECOND_GOAL_DAY = 3000
        private const val INSERT = """INSERT INTO food_entries
            (id,localDate,mealId,ordinal,kind,snapshot_name,snapshot_unit,snapshot_basisMicros,snapshot_portionName,
             snapshot_kcalNumerator,snapshot_kcalDenominator,snapshot_proteinNumerator,snapshot_proteinDenominator,
             snapshot_fatNumerator,snapshot_fatDenominator,snapshot_carbsNumerator,snapshot_carbsDenominator,
             snapshot_snapshotFormatVersion,snapshot_calcContractVersion,snapshot_sourceKind,snapshot_nameKey,
             quantityMicros,revision,createdByOperationId)
            VALUES(?,?,?,?,'portion','Scale fraction fixture','PORTION',1000000,'порция',
                   '1','3','2','7','5','11','13','17',1,1,'PRODUCT','scale fraction fixture',1000000,1,?)"""
    }
}
