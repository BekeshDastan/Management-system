package app.diet.messages

import app.diet.data.DataFailure
import app.diet.media.PhotoFailure
import kotlinx.coroutines.CancellationException

/** User-facing failures never expose serializer internals, SQL, provider URIs or paths. */
fun userMessage(error: Throwable, fallback: String = "Не удалось выполнить действие. Повторите попытку."): String {
    if (error is CancellationException) throw error
    val message = error.message.orEmpty()
    if (error is DataFailure || error is PhotoFailure || error is IllegalArgumentException) {
        val localized = message.any { it in '\u0400'..'\u052f' }
        val technical = listOf("Serializer", "SQLite", "/data/", "content://", "file://", "app.diet.", "\n").any { it in message }
        if (localized && !technical && message.length <= 240) return message
    }
    if ("disk is full" in message.lowercase() || "SQLITE_FULL" in message) return "Недостаточно места на устройстве. Освободите место и повторите."
    return fallback
}
