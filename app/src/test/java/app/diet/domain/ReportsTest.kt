package app.diet.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CancellationException

class ReportsTest {
    private fun d(day: Int) = LocalDate.of(2026, 11, day)
    private fun nutrition(k: Long, p: Long = 0, f: Long = 0, c: Long = 0) = Nutrients(Rational.of(k), Rational.of(p), Rational.of(f), Rational.of(c))
    private val week = DatePeriod(d(8), d(14))
    private fun days() = listOf(
        NutritionDay(d(8), nutrition(2000, 100, 70, 240), 1, completed = true),
        NutritionDay(d(9), nutrition(850, 40, 25, 115), 1),
        NutritionDay(d(11), completed = true, confirmedZero = true),
        NutritionDay(d(12), nutrition(2200, 110, 80, 260), 1, completed = true),
        NutritionDay(d(13), nutrition(1200, 65, 40, 140), 1),
        NutritionDay(d(14), nutrition(1800, 90, 60, 220), 1, completed = true),
    )
    private fun goals() = listOf(DailyGoal(d(9), nutrition(2000, 100, 70, 250)), DailyGoal(d(13), nutrition(2100, 105, 75, 260)))

    @Test fun nutritionUsesOnlyCompletedDaysAndCommonGoalSample() {
        val report = ReportEngine.nutrition(week, days(), goals())
        assertEquals(7L, report.m)
        assertEquals(4, report.n)
        assertEquals(3, report.q)
        assertEquals(2, report.partialDays)
        assertEquals(1L, report.missingDays)
        assertEquals(nutrition(1500, 75) + Nutrients(fat = Rational.of(105, 2), carbs = Rational.of(180)), report.mean)
        val comparison = report.comparison!!
        assertEquals("1333,3", comparison.actualMean.kcal.format())
        assertEquals("2033,3", comparison.targetMean.kcal.format())
        assertEquals("-700,0", comparison.difference.kcal.format())
        assertEquals("66,7", comparison.actualMean.protein.format())
        assertEquals("101,7", comparison.targetMean.protein.format())
    }

    @Test fun weightAndSleepHaveIndependentSamplesAndZeroIsARecord() {
        val weights = listOf(WeightMeasurement(d(8), 8020), WeightMeasurement(d(10), 8000), WeightMeasurement(d(11), 7980), WeightMeasurement(d(13), 7990), WeightMeasurement(d(14), 8001))
        val sleep = listOf(SleepMeasurement(d(8), 420), SleepMeasurement(d(9), 480), SleepMeasurement(d(11), 0), SleepMeasurement(d(12), 421), SleepMeasurement(d(13), 450))
        assertEquals(5, ReportEngine.weights(week, weights).n)
        assertEquals("79,98", ReportEngine.weights(week, weights).meanKg?.format(2))
        assertEquals("-0,19", ReportEngine.weights(week, weights).firstToLastChangeKg?.format(2))
        assertEquals(5, ReportEngine.sleep(week, sleep).n)
        assertEquals(354, ReportEngine.sleep(week, sleep).roundedMeanMinutes)
        val short = DatePeriod(d(13), d(14))
        assertEquals("79,96", ReportEngine.weights(short, weights).meanKg?.format(2))
        assertEquals(450, ReportEngine.sleep(short, sleep).roundedMeanMinutes)
    }

    @Test fun partialAndMissingDaysDoNotBecomeNutritionZero() {
        val partial = ReportEngine.nutrition(DatePeriod(d(9), d(9)), days(), goals())
        assertEquals(0, partial.n)
        assertNull(partial.mean)
        assertNull(partial.comparison)
        val confirmedZero = ReportEngine.nutrition(DatePeriod(d(11), d(11)), days(), goals())
        assertEquals(1, confirmedZero.n)
        assertEquals(Nutrients.ZERO, confirmedZero.mean)
        assertEquals(1, confirmedZero.q)
        val empty = DatePeriod(d(1), d(7))
        assertNull(ReportEngine.nutrition(empty, days(), goals()).mean)
        assertNull(ReportEngine.weights(empty, emptyList()).meanKg)
        assertNull(ReportEngine.sleep(empty, emptyList()).meanMinutes)
    }

    @Test fun differencesAreComputedBeforeDisplayRounding() {
        val first = Rational.fromMicros(140_000)
        val second = Rational.fromMicros(60_000)
        assertEquals("0,1", (first - second).format())
        assertEquals("0,1", first.format())
        assertEquals("0,1", second.format())
        val period = DatePeriod(d(1), d(2))
        assertEquals("80,01", ReportEngine.weights(period, listOf(WeightMeasurement(d(1), 8000), WeightMeasurement(d(2), 8001))).meanKg?.format(2))
        assertEquals(421, ReportEngine.sleep(period, listOf(SleepMeasurement(d(1), 420), SleepMeasurement(d(2), 421))).roundedMeanMinutes)
    }

    @Test fun goalTimelineHasExactEffectiveDatesAndNoDuplicateStart() {
        val timeline = GoalTimeline(goals())
        assertNull(timeline.goalOn(d(8)))
        assertEquals(Rational.of(2000), timeline.goalOn(d(9))?.nutrients?.kcal)
        assertEquals(Rational.of(2100), timeline.goalOn(d(13))?.nutrients?.kcal)
        assertThrows(IllegalArgumentException::class.java) { GoalTimeline(goals() + goals().first()) }
        assertThrows(IllegalArgumentException::class.java) { DailyGoal(d(1), Nutrients.ZERO) }
    }

    @Test fun deletionOfLastFoodDoesNotConfirmAnEmptyDay() {
        val original = NutritionDay(d(1), nutrition(100), 1).confirmComplete()
        assertEquals(DayStatus.COMPLETE, original.status)
        assertEquals(DayStatus.COMPLETE, original.withFood(nutrition(200), 2).status)
        val deleted = original.withFood(Nutrients.ZERO, 0)
        assertEquals(DayStatus.MISSING, deleted.status)
        assertThrows(IllegalArgumentException::class.java) { deleted.confirmComplete() }
        val zero = deleted.confirmComplete(confirmEmpty = true)
        assertEquals(DayStatus.CONFIRMED_ZERO, zero.status)
        assertEquals(DayStatus.COMPLETE, zero.withFood(nutrition(200), 1).status)
        assertEquals(DayStatus.MISSING, zero.reopen().status)
    }

    @Test fun dateBoundariesAndPreviousPeriodAreExplicit() {
        assertEquals(DomainDates.MIN, DomainDates.parse("1900-01-01"))
        assertEquals(DomainDates.MAX, DomainDates.parse("9999-12-31"))
        assertEquals(LocalDate.of(2000, 2, 29), DomainDates.parse("2000-02-29"))
        assertThrows(IllegalArgumentException::class.java) { DomainDates.parse("1900-02-29") }
        assertThrows(IllegalArgumentException::class.java) { DomainDates.parse("2026-02-30") }
        assertThrows(IllegalArgumentException::class.java) { DomainDates.validateFactDate(d(15), d(14)) }
        assertThrows(IllegalArgumentException::class.java) { DomainDates.validateFactDate(d(1), DomainDates.MAX.plusDays(1)) }
        assertEquals(DomainDates.MAX, DomainDates.latestPlanDate(DomainDates.MAX.minusDays(1)))
        assertEquals(DomainDates.TOTAL_SUPPORTED_DAYS, DatePeriod(DomainDates.MIN, DomainDates.MAX).dayCount)
        assertEquals(2_958_464_000_000L, DomainDates.TOTAL_SUPPORTED_DAYS * Numbers.MAX_WEIGHT_CENTI_KG)
        assertEquals(177_504_881_536L, DomainDates.TOTAL_SUPPORTED_DAYS * Numbers.MAX_SLEEP_MINUTES)
        val clipped = DatePeriod(DomainDates.MIN.plusDays(2), DomainDates.MIN.plusDays(8)).previous()
        assertTrue(clipped.truncatedAtLowerBoundary)
        assertEquals(2L, clipped.period?.dayCount)
        assertNull(DatePeriod(DomainDates.MIN, DomainDates.MIN).previous().period)
        assertEquals(week, DatePeriod.lastDays(d(14), 7))
    }

    @Test fun duplicateInputAndCancellationAreErrorsNotMissingData() {
        assertThrows(IllegalArgumentException::class.java) { ReportEngine.nutrition(week, listOf(days()[0], days()[0])) }
        assertThrows(IllegalArgumentException::class.java) { ReportEngine.weights(week, listOf(WeightMeasurement(d(8), 8000), WeightMeasurement(d(8), 8000))) }
        assertThrows(CancellationException::class.java) { ReportEngine.nutrition(week, days(), cancelled = { true }) }
    }
}
