package app.diet.domain.streaming

import app.diet.domain.*
import java.time.LocalDate
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StreamingReportsTest {
    private val first = LocalDate.of(2026, 11, 1)

    @Test fun streamingKeepsCompleteAndPartialSamplesSeparate() {
        val period = DatePeriod(first, first.plusDays(6))
        val fold = NutritionAccumulator(period)
        val goal = DailyGoal(first.plusDays(2), Nutrients(kcal = Rational.of(2000)))
        fold.add(NutritionDay(first, Nutrients(kcal = Rational.of(1000)), 1, completed = true))
        fold.add(NutritionDay(first.plusDays(1), Nutrients(kcal = Rational.of(9000)), 10))
        fold.add(NutritionDay(first.plusDays(2), completed = true, confirmedZero = true), goal)
        fold.add(NutritionDay(first.plusDays(3), Nutrients(kcal = Rational.of(2000)), 2, completed = true), goal)
        val result = fold.summary()
        assertEquals(7L, result.m)
        assertEquals(3, result.n)
        assertEquals(2, result.q)
        assertEquals(1, result.partialDays)
        assertEquals(3L, result.missingDays)
        assertEquals(Rational.of(1000), result.mean?.kcal)
        assertEquals(Rational.of(1000), result.comparison?.actualMean?.kcal)
        assertEquals(Rational.of(2000), result.comparison?.targetMean?.kcal)
        assertEquals(Rational.of(9000), fold.partialMean?.kcal)
    }

    @Test fun hundredThousandFactsFoldToExact3653DaySummaryWithoutACollection() {
        val start = LocalDate.of(2016, 1, 1)
        val period = DatePeriod(start, LocalDate.of(2025, 12, 31))
        assertEquals(3653L, period.dayCount)
        val fold = NutritionAccumulator(period)
        val ordinaryRows = 100_000 / 3653
        val extraRows = 100_000 % 3653
        repeat(3653) { index ->
            val rows = ordinaryRows + if (index < extraRows) 1 else 0
            // Each actual row had precisely 1/3 kcal before the day-level fold.
            val exactDay = Nutrients(kcal = Rational.of(rows.toLong(), 3))
            fold.add(NutritionDay(start.plusDays(index.toLong()), exactDay, rows, completed = true))
        }
        val result = fold.summary()
        assertEquals(3653, result.n)
        assertEquals(0L, result.missingDays)
        assertEquals(Rational.of(100_000, 3653L * 3), result.mean?.kcal)
        assertNull(result.comparison)
    }

    @Test fun independentMeasurementFoldsIncludeConfirmedSleepZero() {
        val period = DatePeriod(first, first.plusDays(6))
        val weight = WeightAccumulator(period)
        weight.add(WeightMeasurement(first, 8000))
        weight.add(WeightMeasurement(first.plusDays(3), 8001))
        val sleep = SleepAccumulator(period)
        sleep.add(SleepMeasurement(first.plusDays(1), 420))
        sleep.add(SleepMeasurement(first.plusDays(2), 0))
        sleep.add(SleepMeasurement(first.plusDays(5), 480))
        assertEquals("80,01", weight.summary().meanKg?.format(2))
        assertEquals("0,01", weight.summary().firstToLastChangeKg?.format(2))
        assertEquals(3, sleep.summary().n)
        assertEquals(300, sleep.summary().roundedMeanMinutes)
        assertNull(SleepAccumulator(period).summary().meanMinutes)
    }

    @Test fun duplicateOrderingAndCancellationNeverProduceAPartialSuccess() {
        val period = DatePeriod(first, first.plusDays(6))
        var cancelled = false
        val fold = NutritionAccumulator(period) { cancelled }
        fold.add(NutritionDay(first))
        assertThrows(IllegalArgumentException::class.java) { fold.add(NutritionDay(first)) }
        cancelled = true
        assertThrows(CancellationException::class.java) { fold.summary() }
        assertThrows(CancellationException::class.java) { fold.add(NutritionDay(first.plusDays(1))) }
    }
}
