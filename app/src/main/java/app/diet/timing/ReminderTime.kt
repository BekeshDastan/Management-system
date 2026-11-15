package app.diet.timing

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class ScheduledOccurrence(val date: LocalDate, val instant: Instant, val zone: String)

object ReminderTime {
    fun next(now: Instant, zone: ZoneId, minute: Int, daysMask: Int, claimedDate: LocalDate? = null): ScheduledOccurrence? {
        require(minute in 0..1439 && daysMask in 1..127)
        val today = now.atZone(zone).toLocalDate()
        if (today.year !in 1900..9999) return null
        for (offset in 0L..7L) {
            val date = today.plusDays(offset)
            if (date.year > 9999) return null
            if (date == claimedDate || daysMask and (1 shl (date.dayOfWeek.value - 1)) == 0) continue
            // Java shifts a gap forward and uses the earlier offset in an overlap.
            val instant = LocalDateTime.of(date, LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant()
            if (instant > now) return ScheduledOccurrence(date, instant, zone.id)
        }
        return null
    }
    fun eligible(now: Instant, scheduled: Instant, date: LocalDate, scheduledZone: String, zone: ZoneId): Boolean =
        scheduledZone == zone.id && now >= scheduled && now <= scheduled.plusSeconds(7200) && now.atZone(zone).toLocalDate() == date
}
