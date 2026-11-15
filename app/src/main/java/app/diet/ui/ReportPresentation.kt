package app.diet.ui

import app.diet.domain.DatePeriod
import app.diet.domain.DomainDates
import app.diet.domain.Nutrients
import app.diet.domain.Rational
import app.diet.data.ReportSeriesPoint
import java.time.LocalDate

/** Calendar navigation uses inclusive periods and never silently includes future facts. */
internal object ReportPresentation {
    fun presetStart(preset: String, end: LocalDate): LocalDate = when (preset) {
        "week" -> maxOf(DomainDates.MIN, end.minusDays(end.dayOfWeek.value.toLong() - 1))
        "month" -> end.withDayOfMonth(1)
        "year" -> end.withDayOfYear(1)
        "7", "30", "90" -> maxOf(DomainDates.MIN, end.minusDays(preset.toLong() - 1))
        else -> error("Выберите период отчёта")
    }

    fun adjacent(period: DatePeriod, direction: Int, today: LocalDate): DatePeriod? {
        require(direction == -1 || direction == 1)
        DomainDates.validateFactDate(period.endInclusive, today)
        return if (direction < 0) {
            if (period.start == DomainDates.MIN) null else {
                val end = period.start.minusDays(1)
                DatePeriod(maxOf(DomainDates.MIN, end.minusDays(period.dayCount - 1)), end)
            }
        } else {
            if (period.endInclusive == today) null else {
                val end = minOf(today, period.endInclusive.plusDays(period.dayCount))
                DatePeriod(maxOf(DomainDates.MIN, end.minusDays(period.dayCount - 1)), end)
            }
        }
    }

    /** Calendar-week arrows keep Monday–Sunday even when this week ends at today. */
    fun adjacentCalendarWeek(period: DatePeriod, direction: Int, today: LocalDate): DatePeriod? {
        require(direction == -1 || direction == 1)
        DomainDates.validateFactDate(period.endInclusive, today)
        val monday = maxOf(DomainDates.MIN, period.start.minusDays(period.start.dayOfWeek.value.toLong() - 1))
        return if (direction < 0) {
            if (monday == DomainDates.MIN) null
            else DatePeriod(maxOf(DomainDates.MIN, monday.minusDays(7)), monday.minusDays(1))
        } else {
            if (monday > DomainDates.MAX.minusDays(7)) null
            else {
                val next = monday.plusDays(7)
                if (next > today) null else DatePeriod(next, minOf(today, next.plusDays(6)))
            }
        }
    }

    /** Equally-sized bar slots; taps outside the plot select the nearest end slot. */
    fun selectedBar(x: Float, width: Float, count: Int): Int? {
        if (count <= 0 || width <= 0 || !x.isFinite() || !width.isFinite()) return null
        return kotlin.math.floor((x / width * count).toDouble()).toInt().coerceIn(0, count - 1)
    }

    /** Recorded totals include partial facts; the existing completed-day N/Q stays unchanged. */
    fun recordedTotal(point: ReportSeriesPoint): Nutrients =
        (point.nutritionMean?.times(Rational.of(point.nutritionCompleteDays.toLong())) ?: Nutrients.ZERO) +
            (point.partialNutritionMean?.times(Rational.of(point.nutritionPartialDays.toLong())) ?: Nutrients.ZERO)

    fun recordedDays(point: ReportSeriesPoint): Int = Math.addExact(point.nutritionCompleteDays, point.nutritionPartialDays)

    fun recordedMean(point: ReportSeriesPoint): Nutrients? = recordedDays(point).takeIf { it > 0 }?.let {
        recordedTotal(point) / Rational.of(it.toLong())
    }

    fun recordedMealMeans(point: ReportSeriesPoint): Map<String, Nutrients> {
        val count = recordedDays(point)
        if (count == 0) return emptyMap()
        val complete = Rational.of(point.nutritionCompleteDays.toLong())
        val partial = Rational.of(point.nutritionPartialDays.toLong())
        val divisor = Rational.of(count.toLong())
        return (point.mealMeans.keys + point.partialMealMeans.keys).associateWith { id ->
            ((point.mealMeans[id] ?: Nutrients.ZERO) * complete + (point.partialMealMeans[id] ?: Nutrients.ZERO) * partial) / divisor
        }
    }

    fun recordedTotal(points: List<ReportSeriesPoint>): Nutrients = Nutrients.sum(points.map(::recordedTotal))
    fun recordedDays(points: List<ReportSeriesPoint>): Long = points.fold(0L) { sum, point -> Math.addExact(sum, recordedDays(point).toLong()) }
    fun recordedMean(points: List<ReportSeriesPoint>): Nutrients? = recordedDays(points).takeIf { it > 0 }?.let { recordedTotal(points) / Rational.of(it) }

    /** A bucket goal is a calendar mean, never the completed-day Q comparison. */
    fun calendarGoalMean(points: List<ReportSeriesPoint>): Nutrients? {
        val count = points.fold(0L) { sum, point -> Math.addExact(sum, point.goalDays.toLong()) }
        if (count == 0L) return null
        return Nutrients.sum(points.map { point -> point.goalMean?.times(Rational.of(point.goalDays.toLong())) ?: Nutrients.ZERO }) / Rational.of(count)
    }

    /** Equal adjacent targets form one full-width run. Missing goals leave a real gap. */
    fun goalRuns(points: List<ReportSeriesPoint>, component: String): List<ReportGoalRun> {
        val runs = mutableListOf<ReportGoalRun>()
        points.forEachIndexed { index, point ->
            val value = nutrient(point.goalMean, component)
            if (value != null && point.goalDays > 0) {
                val prior = runs.lastOrNull()
                if (prior != null && prior.endExclusive == index && prior.value == value) runs[runs.lastIndex] = prior.copy(endExclusive = index + 1)
                else runs += ReportGoalRun(index, index + 1, value)
            }
        }
        return runs
    }

    fun nutrient(value: Nutrients?, component: String): Rational? = when (component) {
        "protein" -> value?.protein
        "fat" -> value?.fat
        "carbs" -> value?.carbs
        else -> value?.kcal
    }

}


internal data class ReportGoalRun(val start: Int, val endExclusive: Int, val value: Rational)
