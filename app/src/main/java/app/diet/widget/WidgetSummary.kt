package app.diet.widget

import app.diet.data.ReportDay
import app.diet.domain.DayStatus
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Explicit date keeps a delayed launcher refresh from labelling yesterday's totals as today. */
internal data class WidgetSummary(
    val date: LocalDate,
    val calories: String,
    val goal: String,
    val status: String,
) {
    val dateLabel: String get() = date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")))

    companion object {
        fun from(day: ReportDay): WidgetSummary = WidgetSummary(
            date = day.date,
            calories = if (day.food.status == DayStatus.MISSING) "— ккал" else "${day.food.nutrients.kcal.format(1)} ккал",
            goal = day.goal?.let { "Цель: ${it.kcal.format(1)} ккал" } ?: "Без цели",
            status = when (day.food.status) {
                DayStatus.MISSING -> "Еда ещё не записана"
                DayStatus.PARTIAL -> "День заполнен частично"
                DayStatus.COMPLETE -> "День заполнен"
                DayStatus.CONFIRMED_ZERO -> "Подтверждён день без еды"
            },
        )
    }
}

/** Next local midnight follows the current zone, including DST and short/long civil days. */
internal fun nextWidgetDateChange(now: ZonedDateTime): Long =
    now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
