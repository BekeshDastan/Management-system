package app.diet.widget

import app.diet.data.ReportDay
import app.diet.domain.Nutrients
import app.diet.domain.NutritionDay
import app.diet.domain.Rational
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WidgetSummaryTest {
    private val date = LocalDate.parse("2026-11-15")
    private fun summary(food: NutritionDay, goal: Nutrients? = null) =
        WidgetSummary.from(ReportDay(date, food, null, null, goal))

    @Test fun missingIsNotZeroAndConfirmedEmptyIsExplicit() {
        val missing = summary(NutritionDay(date))
        assertEquals("— ккал", missing.calories)
        assertEquals("Еда ещё не записана", missing.status)
        val zero = summary(NutritionDay(date, completed = true, confirmedZero = true))
        assertEquals("0,0 ккал", zero.calories)
        assertEquals("Подтверждён день без еды", zero.status)
    }

    @Test fun partialTotalUsesExactRoundingAndCanBecomeCompleteWithoutChangingAmount() {
        val food = NutritionDay(date, Nutrients(kcal = Rational.of(5009, 20)), 1)
        assertEquals("250,5 ккал", summary(food).calories)
        assertEquals("День заполнен частично", summary(food).status)
        assertEquals("День заполнен", summary(food.copy(completed = true)).status)
    }

    @Test fun effectiveGoalAndExplicitDateAreSeparateFromMissingFood() {
        val actual = summary(NutritionDay(date), Nutrients(kcal = Rational.of(1800)))
        assertEquals("Цель: 1800,0 ккал", actual.goal)
        assertEquals("15 ноября", actual.dateLabel)
        assertEquals("— ккал", actual.calories)
        assertEquals("Без цели", summary(NutritionDay(date)).goal)
    }

    @Test fun midnightUsesCivilDayNotTwentyFourHours() {
        val zone = ZoneId.of("Europe/Berlin")
        val spring = Instant.parse("2026-03-28T23:00:00Z").atZone(zone)
        val autumn = Instant.parse("2026-10-24T22:00:00Z").atZone(zone)
        assertEquals(Instant.parse("2026-03-29T22:00:00Z").toEpochMilli(), nextWidgetDateChange(spring))
        assertEquals(Instant.parse("2026-10-25T23:00:00Z").toEpochMilli(), nextWidgetDateChange(autumn))
    }
}
