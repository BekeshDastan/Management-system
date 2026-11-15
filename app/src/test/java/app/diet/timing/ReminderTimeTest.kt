package app.diet.timing

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReminderTimeTest {
    @Test fun springGapMovesForwardByGap() {
        val next = ReminderTime.next(Instant.parse("2026-03-28T22:00:00Z"), ZoneId.of("Europe/Berlin"), 150, 64)!!
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), next.instant)
        assertEquals(LocalDate.parse("2026-03-29"), next.date)
    }
    @Test fun fallOverlapUsesFirstInstant() {
        val next = ReminderTime.next(Instant.parse("2026-10-24T22:00:00Z"), ZoneId.of("Europe/Berlin"), 150, 64)!!
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), next.instant)
    }
    @Test fun exactNowIsNeverScheduledAsFutureAndClaimedDayIsSkipped() {
        val now = Instant.parse("2026-11-16T08:00:00Z")
        assertEquals(LocalDate.parse("2026-11-17"), ReminderTime.next(now, ZoneId.of("UTC"), 480, 127)!!.date)
        assertEquals(LocalDate.parse("2026-11-17"), ReminderTime.next(now.minusSeconds(1), ZoneId.of("UTC"), 480, 127, LocalDate.parse("2026-11-16"))!!.date)
    }
    @Test fun weekMaskAndFractionalTimezoneAreRespected() {
        val next = ReminderTime.next(Instant.parse("2026-11-16T00:00:00Z"), ZoneId.of("Asia/Kathmandu"), 480, 1)!!
        assertEquals(Instant.parse("2026-11-16T02:15:00Z"), next.instant)
    }
    @Test fun callbackChecksGraceDateAndZone() {
        val time = Instant.parse("2026-11-16T08:00:00Z")
        val date = LocalDate.parse("2026-11-16"); val zone = ZoneId.of("UTC")
        assertFalse(ReminderTime.eligible(time.minusSeconds(1), time, date, "UTC", zone))
        assertTrue(ReminderTime.eligible(time.plusSeconds(7200), time, date, "UTC", zone))
        assertFalse(ReminderTime.eligible(time.plusSeconds(7201), time, date, "UTC", zone))
        assertFalse(ReminderTime.eligible(time, time, date.minusDays(1), "UTC", zone))
        assertFalse(ReminderTime.eligible(time, time, date, "Europe/Berlin", zone))
    }
    @Test fun invalidSystemDateDoesNotScheduleAnotherDate() {
        assertNull(ReminderTime.next(Instant.parse("+10000-01-01T00:00:00Z"), ZoneId.of("UTC"), 480, 127))
    }
}
