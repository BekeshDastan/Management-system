package app.diet.ui

internal fun foodPositionCount(count: Long): String {
    val word = when { count % 100 in 11L..14L -> "продуктов"; count % 10 == 1L -> "продукт"; count % 10 in 2L..4L -> "продукта"; else -> "продуктов" }
    return "$count $word"
}

internal fun recordingStreakLabel(days: Long): String = when {
    days == 0L -> "Начните серию записей еды"
    days == 1L -> "Текущая серия записей еды · 1 день"
    days % 100 in 11L..14L -> "Текущая серия записей еды · $days дней"
    days % 10 == 1L -> "Текущая серия записей еды · $days день"
    days % 10 in 2L..4L -> "Текущая серия записей еды · $days дня"
    else -> "Текущая серия записей еды · $days дней"
}
