package app.diet.domain

import java.time.LocalDate

data class GameLevel(val number: Int, val name: String, val threshold: Long, val nextThreshold: Long?, val daysToNext: Long?)

object GameRules {
    private val thresholds = listOf(0L to "На старте", 1L to "Первый шаг", 2L to "Дневник в деле", 7L to "В ритме", 14L to "Хранитель привычки", 30L to "Мастер дневника", 60L to "Летописец")

    fun weightName(centiKg: Long?): String? {
        if (centiKg == null) return null
        require(centiKg in 1..Numbers.MAX_WEIGHT_CENTI_KG) { "Некорректный вес" }
        return when {
            centiKg < 6_000 -> "Искра"
            centiKg < 7_000 -> "Вихрь"
            centiKg < 8_000 -> "Метеор"
            centiKg < 9_000 -> "Шторм"
            centiKg <= 9_500 -> "Машина"
            centiKg < 10_000 -> "Титан"
            else -> "Богатырь"
        }
    }

    fun sleepName(minutes: Int?): String? {
        if (minutes == null || minutes == 0) return null
        require(minutes in 1..Numbers.MAX_SLEEP_MINUTES) { "Некорректная длительность сна" }
        return when {
            minutes < 360 -> "Короткий привал"
            minutes < 480 -> "Ловец снов"
            minutes < 600 -> "Соня"
            else -> "Хранитель подушки"
        }
    }

    /** The count comes from distinct completed local dates, never quantities or clicks. */
    fun level(completedDayCount: Long): GameLevel {
        require(completedDayCount in 0..DomainDates.TOTAL_SUPPORTED_DAYS) { "Некорректное число заполненных дат" }
        val index = thresholds.indexOfLast { completedDayCount >= it.first }
        val current = thresholds[index]
        val next = thresholds.getOrNull(index + 1)?.first
        return GameLevel(index + 1, current.second, current.first, next, next?.minus(completedDayCount))
    }

    fun level(completedDates: Collection<LocalDate>): GameLevel {
        completedDates.forEach(DomainDates::validate)
        return level(completedDates.distinct().size.toLong())
    }
}
