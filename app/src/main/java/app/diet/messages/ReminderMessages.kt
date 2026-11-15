package app.diet.messages

object ReminderMessages {
    fun title(kind: String): String = when (kind) {
        "food" -> "Можно заполнить питание"
        "weight" -> "Можно записать утренний вес"
        "sleep" -> "Можно записать сон"
        else -> "Можно заполнить дневник"
    }
    const val BODY = "Пара минут для вашего дневника"
}
