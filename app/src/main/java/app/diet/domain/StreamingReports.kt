package app.diet.domain

import java.time.LocalDate
import java.util.concurrent.CancellationException

/** A bounded-memory report fold. The repository supplies one complete local day at a time. */
class NutritionAccumulator(private val period: DatePeriod, private val cancelled: () -> Boolean = { false }) {
    private var previous: LocalDate? = null
    private var completedCount = 0
    private var goalCount = 0
    private var partialCount = 0
    private var completeSum = Nutrients.ZERO
    private var partialSum = Nutrients.ZERO
    private var actualWithGoal = Nutrients.ZERO
    private var targetWithGoal = Nutrients.ZERO

    fun add(day: NutritionDay, goal: DailyGoal? = null) {
        reportCheckpoint(cancelled)
        val priorDate = previous
        require(day.date in period && (priorDate == null || day.date > priorDate)) { "Дата отчёта вне периода, повторена или не упорядочена" }
        require(goal == null || goal.effectiveFrom <= day.date) { "Цель ещё не действовала на дату" }
        previous = day.date
        if (day.status == DayStatus.PARTIAL) {
            partialCount = Math.addExact(partialCount, 1)
            partialSum += day.nutrients
        }
        if (!day.completed) return
        completedCount = Math.addExact(completedCount, 1)
        completeSum += day.nutrients
        if (goal != null) {
            goalCount = Math.addExact(goalCount, 1)
            actualWithGoal += day.nutrients
            targetWithGoal += goal.nutrients
        }
    }

    val partialMean: Nutrients? get() = if (partialCount == 0) null else partialSum / Rational.of(partialCount.toLong())

    fun summary(): NutritionSummary {
        reportCheckpoint(cancelled)
        val comparison = if (goalCount == 0) null else {
            val actual = actualWithGoal / Rational.of(goalCount.toLong())
            val target = targetWithGoal / Rational.of(goalCount.toLong())
            GoalComparison(actual, target, actual - target, actual.percentageOf(target))
        }
        return NutritionSummary(
            period.dayCount, completedCount, goalCount, partialCount,
            period.dayCount - completedCount - partialCount,
            if (completedCount == 0) null else completeSum / Rational.of(completedCount.toLong()), comparison,
        )
    }
}

class WeightAccumulator(private val period: DatePeriod, private val cancelled: () -> Boolean = { false }) {
    private var previous: LocalDate? = null
    private var count = 0
    private var sumCentiKg = 0L
    private var first: Long? = null
    private var last: Long? = null

    fun add(row: WeightMeasurement) {
        reportCheckpoint(cancelled)
        val priorDate = previous
        require(row.date in period && (priorDate == null || row.date > priorDate)) { "Дата веса вне периода, повторена или не упорядочена" }
        previous = row.date
        sumCentiKg = Math.addExact(sumCentiKg, row.centiKg)
        count = Math.addExact(count, 1)
        if (first == null) first = row.centiKg
        last = row.centiKg
    }

    fun summary(): WeightSummary {
        reportCheckpoint(cancelled)
        return WeightSummary(period.dayCount, count, if (count == 0) null else Rational.of(sumCentiKg, 100L * count), if (count < 2) null else Rational.of(last!! - first!!, 100))
    }
}

class SleepAccumulator(private val period: DatePeriod, private val cancelled: () -> Boolean = { false }) {
    private var previous: LocalDate? = null
    private var count = 0
    private var sumMinutes = 0L

    fun add(row: SleepMeasurement) {
        reportCheckpoint(cancelled)
        val priorDate = previous
        require(row.date in period && (priorDate == null || row.date > priorDate)) { "Дата сна вне периода, повторена или не упорядочена" }
        previous = row.date
        sumMinutes = Math.addExact(sumMinutes, row.minutes.toLong())
        count = Math.addExact(count, 1)
    }

    fun summary(): SleepSummary {
        reportCheckpoint(cancelled)
        return SleepSummary(period.dayCount, count, if (count == 0) null else Rational.of(sumMinutes, count.toLong()))
    }
}

private fun reportCheckpoint(cancelled: () -> Boolean) {
    Rational.checkCalculation()
    if (cancelled()) throw CancellationException("Расчёт отменён")
}
