package app.diet.ui

import app.diet.domain.DatePeriod
import app.diet.domain.DomainDates
import java.time.LocalDate

/** Calendar navigation uses inclusive periods and never silently includes future facts. */
internal object ReportPresentation {
    fun presetStart(preset: String, end: LocalDate): LocalDate = when (preset) {
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

    /** Equally-sized bar slots; taps outside the plot select the nearest end slot. */
    fun selectedBar(x: Float, width: Float, count: Int): Int? {
        if (count <= 0 || width <= 0 || !x.isFinite() || !width.isFinite()) return null
        return kotlin.math.floor((x / width * count).toDouble()).toInt().coerceIn(0, count - 1)
    }
}
