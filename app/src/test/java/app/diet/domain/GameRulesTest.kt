package app.diet.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class GameRulesTest {
    @Test fun weightNamesHaveNoBoundaryGaps() {
        val expected = mapOf(1L to "Искра", 5999L to "Искра", 6000L to "Вихрь", 6999L to "Вихрь", 7000L to "Метеор", 7999L to "Метеор", 8000L to "Шторм", 8999L to "Шторм", 9000L to "Машина", 9500L to "Машина", 9501L to "Титан", 9999L to "Титан", 10000L to "Богатырь", 1_000_000L to "Богатырь")
        expected.forEach { (weight, name) -> assertEquals(name, GameRules.weightName(weight)) }
        assertNull(GameRules.weightName(null))
    }

    @Test fun sleepNamesNeverJudgeMissingOrZeroSleep() {
        assertNull(GameRules.sleepName(null))
        assertNull(GameRules.sleepName(0))
        mapOf(1 to "Короткий привал", 359 to "Короткий привал", 360 to "Ловец снов", 479 to "Ловец снов", 480 to "Соня", 599 to "Соня", 600 to "Хранитель подушки").forEach { (minutes, name) -> assertEquals(name, GameRules.sleepName(minutes)) }
    }

    @Test fun levelsRewardDistinctCompletedDatesAndHaveAnUpperLevel() {
        val names = mapOf(0L to "На старте", 1L to "Первый шаг", 2L to "Дневник в деле", 6L to "Дневник в деле", 7L to "В ритме", 13L to "В ритме", 14L to "Хранитель привычки", 29L to "Хранитель привычки", 30L to "Мастер дневника", 59L to "Мастер дневника", 60L to "Летописец", 61L to "Летописец")
        names.forEach { (count, name) -> assertEquals(name, GameRules.level(count).name) }
        assertEquals(1L, GameRules.level(6).daysToNext)
        assertNull(GameRules.level(60).nextThreshold)
        val date = LocalDate.of(2026, 11, 1)
        assertEquals("Первый шаг", GameRules.level(listOf(date, date, date)).name)
    }
}
