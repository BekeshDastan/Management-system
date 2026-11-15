package app.diet.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.CancellationException

object DomainDates {
    val MIN: LocalDate = LocalDate.of(1900, 1, 1)
    val MAX: LocalDate = LocalDate.of(9999, 12, 31)
    const val TOTAL_SUPPORTED_DAYS = 2_958_464L

    fun validate(date: LocalDate): LocalDate {
        require(date in MIN..MAX) { "Дата должна быть от 1900-01-01 до 9999-12-31" }
        return date
    }

    fun parse(raw: String): LocalDate {
        require(raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "Некорректная календарная дата" }
        val date = try { LocalDate.parse(raw) } catch (_: java.time.DateTimeException) {
            throw IllegalArgumentException("Некорректная календарная дата")
        }
        require(date.toString() == raw) { "Неканоническая дата" }
        return validate(date)
    }

    fun validateFactDate(date: LocalDate, today: LocalDate): LocalDate {
        validate(today)
        validate(date)
        require(date <= today) { "Фактическую запись нельзя добавить в будущее" }
        return date
    }

    fun latestPlanDate(today: LocalDate): LocalDate {
        validate(today)
        return if (today > MAX.minusDays(90)) MAX else today.plusDays(90)
    }

    fun validatePlanDate(date: LocalDate, today: LocalDate): LocalDate {
        validate(date)
        require(date <= latestPlanDate(today)) { "План доступен не дальше чем на 90 дней вперёд" }
        return date
    }
}

data class DatePeriod(val start: LocalDate, val endInclusive: LocalDate) {
    init {
        DomainDates.validate(start)
        DomainDates.validate(endInclusive)
        require(start <= endInclusive) { "Начало периода должно быть не позже конца" }
    }

    val dayCount: Long get() = ChronoUnit.DAYS.between(start, endInclusive) + 1
    operator fun contains(date: LocalDate): Boolean = date >= start && date <= endInclusive

    fun previous(): PreviousPeriod {
        if (start == DomainDates.MIN) return PreviousPeriod(null, true, dayCount)
        val previousEnd = start.minusDays(1)
        val requestedStart = start.minusDays(dayCount)
        val clippedStart = maxOf(DomainDates.MIN, requestedStart)
        return PreviousPeriod(DatePeriod(clippedStart, previousEnd), clippedStart != requestedStart, dayCount)
    }

    companion object {
        fun lastDays(endInclusive: LocalDate, count: Long): DatePeriod {
            require(count in 1..DomainDates.TOTAL_SUPPORTED_DAYS) { "Некорректная длина периода" }
            return DatePeriod(endInclusive.minusDays(count - 1), endInclusive)
        }
    }
}

data class PreviousPeriod(val period: DatePeriod?, val truncatedAtLowerBoundary: Boolean, val requestedDayCount: Long)
enum class DayStatus { MISSING, PARTIAL, COMPLETE, CONFIRMED_ZERO }

data class NutritionDay(
    val date: LocalDate,
    val nutrients: Nutrients = Nutrients.ZERO,
    val entryCount: Int = 0,
    val completed: Boolean = false,
    val confirmedZero: Boolean = false,
) {
    init {
        DomainDates.validate(date)
        require(entryCount >= 0) { "Некорректное число записей" }
        nutrients.requireNonNegative()
        require(entryCount != 0 || nutrients == Nutrients.ZERO) { "Нет строк для ненулевого итога" }
        require(!confirmedZero || (completed && entryCount == 0)) { "Нулевой день требует отдельного подтверждения" }
        require(!completed || entryCount > 0 || confirmedZero) { "Пустой заполненный день требует подтверждения" }
    }

    val status: DayStatus get() = when {
        confirmedZero -> DayStatus.CONFIRMED_ZERO
        completed -> DayStatus.COMPLETE
        entryCount > 0 -> DayStatus.PARTIAL
        else -> DayStatus.MISSING
    }

    /** Updating food preserves completion; deleting the last row cannot create a zero day. */
    fun withFood(total: Nutrients, rowCount: Int): NutritionDay {
        require(rowCount >= 0)
        return if (rowCount == 0) copy(nutrients = Nutrients.ZERO, entryCount = 0, completed = false, confirmedZero = false)
        else copy(nutrients = total, entryCount = rowCount, confirmedZero = false)
    }

    fun confirmComplete(confirmEmpty: Boolean = false): NutritionDay {
        require(entryCount > 0 || confirmEmpty) { "Подтвердите отсутствие еды за день" }
        return copy(completed = true, confirmedZero = entryCount == 0)
    }

    fun reopen(): NutritionDay = copy(completed = false, confirmedZero = false)
}

data class DailyGoal(val effectiveFrom: LocalDate, val nutrients: Nutrients) {
    init {
        DomainDates.validate(effectiveFrom)
        require(nutrients.kcal.sign > 0) { "Калорийная цель должна быть больше нуля" }
        nutrients.requireNonNegative()
        nutrients.components().forEach { value ->
            require(value <= Rational.of(1_000_000)) { "Цель превышает допустимый ввод" }
            require((value * Rational.of(Numbers.MICRO_FACTOR)).denominator == java.math.BigInteger.ONE) { "У цели допустимы не больше 6 десятичных знаков" }
        }
    }
}

class GoalTimeline(goals: Iterable<DailyGoal>) {
    private val entries = goals.sortedBy { it.effectiveFrom }.toList()

    init {
        require(entries.zipWithNext().none { (a, b) -> a.effectiveFrom == b.effectiveFrom }) { "На дату начала должна быть одна цель" }
    }

    fun goalOn(date: LocalDate): DailyGoal? {
        DomainDates.validate(date)
        val index = entries.binarySearchBy(date) { it.effectiveFrom }
        val actual = if (index >= 0) index else -index - 2
        return entries.getOrNull(actual)
    }
}

data class GoalComparison(
    val actualMean: Nutrients,
    val targetMean: Nutrients,
    val difference: Nutrients,
    val percentages: NutrientPercentages,
)

data class NutritionSummary(
    val m: Long,
    val n: Int,
    val q: Int,
    val partialDays: Int,
    val missingDays: Long,
    val mean: Nutrients?,
    val comparison: GoalComparison?,
)

data class WeightMeasurement(val date: LocalDate, val centiKg: Long) {
    init {
        DomainDates.validate(date)
        require(centiKg in 1..Numbers.MAX_WEIGHT_CENTI_KG) { "Некорректный вес" }
    }
}

/** Existing zero-minute records have already passed an explicit confirmation. */
data class SleepMeasurement(val date: LocalDate, val minutes: Int) {
    init {
        DomainDates.validate(date)
        require(minutes in 0..Numbers.MAX_SLEEP_MINUTES) { "Некорректная длительность сна" }
    }
}

data class WeightSummary(val m: Long, val n: Int, val meanKg: Rational?, val firstToLastChangeKg: Rational?)
data class SleepSummary(val m: Long, val n: Int, val meanMinutes: Rational?) {
    val roundedMeanMinutes: Int? get() = meanMinutes?.roundedScaledInteger()?.let(Numbers::checkedInt)
}

/** Stream rows in strictly increasing, unique-date order (the repository uses date indexes). */
object ReportEngine {
    fun nutrition(
        period: DatePeriod,
        days: Iterable<NutritionDay>,
        goals: Iterable<DailyGoal> = emptyList(),
        cancelled: () -> Boolean = { false },
    ): NutritionSummary {
        val timeline = GoalTimeline(goals)
        var n = 0
        var q = 0
        var partial = 0
        var sum = Nutrients.ZERO
        var actualWithGoal = Nutrients.ZERO
        var targetWithGoal = Nutrients.ZERO
        var previousDate: LocalDate? = null
        for (day in days) {
            ensureNotCancelled(cancelled)
            require(previousDate == null || day.date > previousDate) { "Даты отчёта должны быть уникальны и упорядочены" }
            previousDate = day.date
            if (day.date > period.endInclusive) break
            if (day.date !in period) continue
            if (day.status == DayStatus.PARTIAL) partial++
            if (!day.completed) continue
            n++
            sum += day.nutrients
            timeline.goalOn(day.date)?.let { goal ->
                q++
                actualWithGoal += day.nutrients
                targetWithGoal += goal.nutrients
            }
        }
        val comparison = if (q == 0) null else {
            val actual = actualWithGoal / Rational.of(q.toLong())
            val target = targetWithGoal / Rational.of(q.toLong())
            GoalComparison(actual, target, actual - target, actual.percentageOf(target))
        }
        return NutritionSummary(period.dayCount, n, q, partial, period.dayCount - n - partial, if (n == 0) null else sum / Rational.of(n.toLong()), comparison)
    }

    fun weights(period: DatePeriod, measurements: Iterable<WeightMeasurement>, cancelled: () -> Boolean = { false }): WeightSummary {
        var sum = 0L
        var n = 0
        var first: Long? = null
        var last: Long? = null
        var previousDate: LocalDate? = null
        for (row in measurements) {
            ensureNotCancelled(cancelled)
            require(previousDate == null || row.date > previousDate) { "Даты веса должны быть уникальны и упорядочены" }
            previousDate = row.date
            if (row.date > period.endInclusive) break
            if (row.date !in period) continue
            sum = Math.addExact(sum, row.centiKg)
            n++
            if (first == null) first = row.centiKg
            last = row.centiKg
        }
        return WeightSummary(period.dayCount, n, if (n == 0) null else Rational.of(sum, 100L * n), if (n < 2) null else Rational.of(last!! - first!!, 100))
    }

    fun sleep(period: DatePeriod, measurements: Iterable<SleepMeasurement>, cancelled: () -> Boolean = { false }): SleepSummary {
        var sum = 0L
        var n = 0
        var previousDate: LocalDate? = null
        for (row in measurements) {
            ensureNotCancelled(cancelled)
            require(previousDate == null || row.date > previousDate) { "Даты сна должны быть уникальны и упорядочены" }
            previousDate = row.date
            if (row.date > period.endInclusive) break
            if (row.date !in period) continue
            sum = Math.addExact(sum, row.minutes.toLong())
            n++
        }
        return SleepSummary(period.dayCount, n, if (n == 0) null else Rational.of(sum, n.toLong()))
    }

    private fun ensureNotCancelled(cancelled: () -> Boolean) {
        Rational.checkCalculation()
        if (cancelled()) throw CancellationException("Расчёт отменён")
    }
}
