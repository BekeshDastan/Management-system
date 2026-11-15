package app.diet.ui

import app.diet.domain.DatePeriod
import app.diet.domain.DomainDates
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReportPresentationTest {
    @Test fun adjacentPeriodsRespectInclusiveLengthAndFutureBoundary() {
        val today = LocalDate.of(2026, 11, 15)
        val week = DatePeriod(today.minusDays(6), today)
        assertEquals(DatePeriod(today.minusDays(13), today.minusDays(7)), ReportPresentation.adjacent(week, -1, today))
        assertNull(ReportPresentation.adjacent(week, 1, today))
        val older = DatePeriod(today.minusDays(9), today.minusDays(3))
        assertEquals(week, ReportPresentation.adjacent(older, 1, today))
        assertNull(ReportPresentation.adjacent(DatePeriod(DomainDates.MIN, DomainDates.MIN.plusDays(6)), -1, today))
        val clipped = ReportPresentation.adjacent(DatePeriod(DomainDates.MIN.plusDays(2), DomainDates.MIN.plusDays(8)), -1, today)!!
        assertEquals(DomainDates.MIN, clipped.start)
        assertEquals(DomainDates.MIN.plusDays(1), clipped.endInclusive)
    }

    @Test fun calendarPresetsAndBarTapsKeepEndpointsReachable() {
        val leap = LocalDate.of(2024, 2, 29)
        assertEquals(LocalDate.of(2024, 2, 1), ReportPresentation.presetStart("month", leap))
        assertEquals(LocalDate.of(2024, 1, 1), ReportPresentation.presetStart("year", leap))
        assertEquals(DomainDates.MIN, ReportPresentation.presetStart("90", DomainDates.MIN.plusDays(4)))
        assertEquals(0, ReportPresentation.selectedBar(-8f, 300f, 600))
        assertEquals(599, ReportPresentation.selectedBar(300f, 300f, 600))
        assertEquals(300, ReportPresentation.selectedBar(150f, 300f, 600))
        assertNull(ReportPresentation.selectedBar(1f, 0f, 7))
        assertNull(ReportPresentation.selectedBar(Float.NaN, 300f, 7))
    }
}
