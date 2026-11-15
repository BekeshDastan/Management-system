package app.diet.ui

/** Public Russian form names; persistence kind codes stay internal. */
internal fun formLabel(kind: String): String = when (kind) {
    "profile", "onboarding-profile" -> "Немного о вас"
    "weight" -> "Утренний вес"
    "sleep" -> "Суммарный сон"
    "goal", "onboarding-goal" -> "Ручные цели"
    "product" -> "Свой продукт"
    "recipe" -> "Свой рецепт"
    "portion" -> "Добавить еду"
    "entry" -> "Исправить запись"
    "quick" -> "Быстрая запись"
    "template" -> "Шаблон приёма"
    "apply-template" -> "Добавить шаблон"
    "plan" -> "План приёма"
    "transfer" -> "Перенести в факт"
    "copy" -> "Скопировать приём"
    "moment" -> "Мой день"
    "meal" -> "Приём пищи"
    "category" -> "Категория"
    "quick-portions" -> "Быстрые порции"
    "reminder" -> "Напоминание"
    "command" -> "Подтверждение действия"
    else -> "Черновик"
}

internal fun recordedDateCount(count: Long): String {
    val word = when {
        count % 100 in 11L..14L -> "дат"
        count % 10 == 1L -> "дата"
        count % 10 in 2L..4L -> "даты"
        else -> "дат"
    }
    return "$count $word"
}

internal fun preferenceGroupLabel(group: String): String = when (group) {
    "theme" -> "Тема"
    "font" -> "Размер текста"
    "appearance" -> "Оформление"
    "home" -> "Главная"
    "gameNames" -> "Игровые названия"
    "gameLevels" -> "Уровни дневника"
    "report" -> "Отчёты"
    "view" -> "Выбранные даты и фильтры"
    else -> "Настройки"
}
