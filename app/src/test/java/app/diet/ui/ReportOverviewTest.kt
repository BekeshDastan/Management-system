package app.diet.ui

import app.diet.data.ReportSeriesPoint
import app.diet.domain.DomainDates
import app.diet.domain.Nutrients
import app.diet.domain.Rational
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** The compact recorded-facts view must not change completed-day N/Q analytics. */
class ReportOverviewTest {
    private val first = LocalDate.of(2026, 11, 9)
    private fun nutrients(kcal: Long) = Nutrients(Rational.of(kcal), Rational.of(kcal, 10), Rational.of(kcal, 20), Rational.of(kcal, 5))
    private fun point(
        offset: Long = 0, days: Long = 1, completed: Nutrients? = null, n: Int = 0,
        partial: Nutrients? = null, p: Int = 0, goal: Nutrients? = null, goalDays: Int = 0,
        completeMeals: Map<String, Nutrients> = emptyMap(), partialMeals: Map<String, Nutrients> = emptyMap(),
    ) = ReportSeriesPoint(
        start = first.plusDays(offset), endInclusive = first.plusDays(offset + days - 1),
        nutritionMean = completed, nutritionCompleteDays = n,
        partialNutritionMean = partial, nutritionPartialDays = p,
        weightMeanKg = null, weightDays = 0, sleepMeanMinutes = null, sleepDays = 0,
        goalMean = goal, goalDays = goalDays, mealMeans = completeMeals, partialMealMeans = partialMeals,
    )

    @Test fun missingIsAbsentWhileConfirmedZeroContributesOneRecordedDay() {
        val missing = point()
        assertNull(ReportPresentation.recordedMean(missing))
        assertEquals(0, ReportPresentation.recordedDays(missing))
        val zero = point(1, completed = Nutrients.ZERO, n = 1)
        assertEquals(Nutrients.ZERO, ReportPresentation.recordedMean(zero))
        val partial = point(2, partial = nutrients(100), p = 1)
        val points = listOf(missing, zero, partial)
        assertEquals(2L, ReportPresentation.recordedDays(points))
        assertEquals(nutrients(100), ReportPresentation.recordedTotal(points))
        assertEquals(nutrients(50), ReportPresentation.recordedMean(points))
        // This view does not mutate the underlying "completed only" numbers.
        assertEquals(1, zero.nutritionCompleteDays)
        assertEquals(0, partial.nutritionCompleteDays)
        assertNull(partial.nutritionMean)
    }

    @Test fun aggregatedRecordedMeanAndMealSegmentsUseTheSameWeightedDenominator() {
        val bucket = point(days = 5, completed = nutrients(100), n = 2, partial = nutrients(400), p = 1,
            completeMeals = mapOf("breakfast" to nutrients(40), "lunch" to nutrients(60)),
            partialMeals = mapOf("breakfast" to nutrients(100), "dinner" to nutrients(300)))
        assertEquals(nutrients(600), ReportPresentation.recordedTotal(bucket))
        assertEquals(nutrients(200), ReportPresentation.recordedMean(bucket)) // (2*100 + 1*400)/3, not 250.
        val meals = ReportPresentation.recordedMealMeans(bucket)
        assertEquals(nutrients(60), meals.getValue("breakfast"))
        assertEquals(nutrients(40), meals.getValue("lunch"))
        assertEquals(nutrients(100), meals.getValue("dinner"))
        assertEquals(ReportPresentation.recordedMean(bucket), Nutrients.sum(meals.values))
        assertEquals(3, ReportPresentation.recordedDays(bucket))
        assertEquals(5L, bucket.calendarDays)
    }

    @Test fun goalRunsSpanEmptyFoodDatesAndPreserveHistoricalChangesAndUnknownGaps() {
        val constant = List(7) { point(it.toLong(), goal = nutrients(2000), goalDays = 1) }
        assertEquals(listOf(ReportGoalRun(0, 7, Rational.of(2000))), ReportPresentation.goalRuns(constant, "kcal"))
        val changed = listOf(
            point(goal = nutrients(2000), goalDays = 1),
            point(1, goal = nutrients(2000), goalDays = 1),
            point(2, goal = nutrients(1800), goalDays = 1),
            point(3),
            point(4, goal = nutrients(1800), goalDays = 1),
        )
        assertEquals(listOf(
            ReportGoalRun(0, 2, Rational.of(2000)),
            ReportGoalRun(2, 3, Rational.of(1800)),
            ReportGoalRun(4, 5, Rational.of(1800)),
        ), ReportPresentation.goalRuns(changed, "kcal"))
        assertNull(ReportPresentation.recordedMean(changed))
        assertEquals(nutrients(1900), ReportPresentation.calendarGoalMean(changed))
        assertEquals(listOf(
            ReportGoalRun(0, 2, Rational.of(200)),
            ReportGoalRun(2, 3, Rational.of(180)),
            ReportGoalRun(4, 5, Rational.of(180)),
        ), ReportPresentation.goalRuns(changed, "protein"))
    }

    @Test fun calendarWeekArrowsKeepSevenDaysAndReturnToClippedCurrentWeek() {
        val wednesday = LocalDate.of(2026, 11, 18)
        val current = app.diet.domain.DatePeriod(LocalDate.of(2026, 11, 16), wednesday)
        val previous = ReportPresentation.adjacentCalendarWeek(current, -1, wednesday)
        assertEquals(app.diet.domain.DatePeriod(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15)), previous)
        assertEquals(7L, previous!!.dayCount)
        assertEquals(current, ReportPresentation.adjacentCalendarWeek(previous, 1, wednesday))
        assertNull(ReportPresentation.adjacentCalendarWeek(current, 1, wednesday))
        assertNull(ReportPresentation.adjacentCalendarWeek(app.diet.domain.DatePeriod(DomainDates.MIN, DomainDates.MIN.plusDays(2)), -1, wednesday))
    }

    @Test fun intervalGoalMeanWeightsCalendarDaysAndCurrentWeekStartsOnMonday() {
        val points = listOf(
            point(days = 20, goal = nutrients(2000), goalDays = 20),
            point(20, days = 10, goal = nutrients(1700), goalDays = 10),
        )
        assertEquals(nutrients(1900), ReportPresentation.calendarGoalMean(points))
        assertEquals(LocalDate.of(2026, 11, 9), ReportPresentation.presetStart("week", LocalDate.of(2026, 11, 15)))
        assertEquals(LocalDate.of(2026, 11, 16), ReportPresentation.presetStart("week", LocalDate.of(2026, 11, 16)))
        assertEquals(DomainDates.MIN, ReportPresentation.presetStart("week", DomainDates.MIN))
    }
}
