package app.diet.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.diet.MainActivity
import app.diet.R
import app.diet.data.DietRepository
import app.diet.data.ReminderEntity
import app.diet.timing.ReminderTime
import app.diet.messages.ReminderMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReminderScheduler(private val context: Context, private val repository: DietRepository, private val clock: Clock = Clock.systemDefaultZone()) {
    private val gate = Mutex()
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    @Volatile private var lastRegistrationError = false

    fun createChannels() {
        listOf("food" to "Питание", "weight" to "Утренний вес", "sleep" to "Сон").forEach { (kind, name) ->
            notifications.createNotificationChannel(NotificationChannel(channel(kind), name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Мягкое напоминание заполнить дневник"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
        }
    }
    fun appAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun channelAllowed(kind: String): Boolean {
        val settings = notifications.getNotificationChannel(channel(kind)) ?: return false
        return appAllowed() && settings.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun status(): Pair<Boolean, String> {
        createChannels()
        if (!appAllowed()) return false to "Уведомления запрещены Android. Дневник работает без них."
        val blocked = listOf("food" to "питания", "weight" to "веса", "sleep" to "сна").filter { !channelAllowed(it.first) }.map { it.second }
        if (blocked.isNotEmpty()) return true to ("В настройках Android отключены каналы: " + blocked.joinToString())
        if (lastRegistrationError) return true to "Не удалось запланировать. Откройте приложение для повторной попытки."
        return true to "Доступны мягкие напоминания. Android может задержать их при энергосбережении."
    }

    suspend fun reconcile() = withContext(Dispatchers.IO) { gate.withLock { reconcileLocked() } }
    private suspend fun reconcileLocked() {
        createChannels()
        lastRegistrationError = false
        val pending = repository.pendingReminders()
        for (spec in repository.reminderSpecs()) {
            val old = alarmIntent(spec, PendingIntent.FLAG_NO_CREATE)
            if (old != null) { alarms.cancel(old); old.cancel() }
            if (!spec.enabled) {
                notifications.cancel(notificationId(spec.kind))
                pending.filter { it.reminderSpecId == spec.id && it.generation <= spec.generation }.forEach { repository.acknowledgeReminder(it.id) }
                continue
            }
            if (!channelAllowed(spec.kind)) continue
            try {
                val next = ReminderTime.next(clock.instant(), ZoneId.systemDefault(), spec.minuteOfDay, spec.weekDaysMask) ?: continue
                val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION).setData(uri(spec.id))
                    .putExtra("id", spec.id).putExtra("generation", spec.generation)
                    .putExtra("date", next.date.toString()).putExtra("zone", next.zone).putExtra("instant", next.instant.toEpochMilli())
                val operation = PendingIntent.getBroadcast(context, spec.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                alarms.setWindow(AlarmManager.RTC_WAKEUP, next.instant.toEpochMilli(), 15 * 60_000L, operation)
                pending.filter { it.reminderSpecId == spec.id && it.generation <= spec.generation }.forEach { repository.acknowledgeReminder(it.id) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { lastRegistrationError = true }
        }
    }

    suspend fun handleAlarm(intent: Intent) = withContext(Dispatchers.IO) {
        gate.withLock {
            if (intent.action != ACTION) return@withLock
            val id = intent.getStringExtra("id") ?: return@withLock
            val spec = repository.reminderSpecs().find { it.id == id } ?: return@withLock
            try {
                val date = LocalDate.parse(intent.getStringExtra("date") ?: return@withLock)
                val zone = intent.getStringExtra("zone") ?: return@withLock
                val expected = Instant.ofEpochMilli(intent.getLongExtra("instant", Long.MIN_VALUE))
                val generation = intent.getLongExtra("generation", -1)
                if (spec.generation != generation || !spec.enabled || !ReminderTime.eligible(clock.instant(), expected, date, zone, ZoneId.systemDefault()) || !channelAllowed(spec.kind)) return@withLock
                // Try to arm the next date BEFORE claiming/posting this one.
                reconcileLocked()
                val occurrence = repository.claimReminder(spec.id, date.toString(), generation) ?: return@withLock
                try {
                    val latest = repository.reminderSpecs().find { it.id == spec.id }
                    if (latest == null || !latest.enabled || latest.generation != generation) repository.finishReminderOccurrence(occurrence.id, "SPEC_CHANGED")
                    else if (!channelAllowed(spec.kind)) repository.finishReminderOccurrence(occurrence.id, "PERMISSION_CHANGED")
                    else {
                        val open = Intent(context, MainActivity::class.java).setAction("app.diet.OPEN_REMINDER")
                            .setData(Uri.parse("diet://open/" + spec.kind)).putExtra("open_form", spec.kind)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        val content = PendingIntent.getActivity(context, notificationId(spec.kind), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val title = ReminderMessages.title(spec.kind)
                        val message = NotificationCompat.Builder(context, channel(spec.kind))
                            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(ReminderMessages.BODY)
                            .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(true)
                            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_REMINDER).build()
                        notifications.notify(notificationId(spec.kind), message)
                        repository.finishReminderOccurrence(occurrence.id)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { repository.finishReminderOccurrence(occurrence.id, "POST_FAILED") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Invalid or obsolete own alarm cannot create a fact. */ }
            finally { reconcileLocked() }
        }
    }
    private fun alarmIntent(spec: ReminderEntity, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, spec.id.hashCode(), Intent(context, ReminderReceiver::class.java).setAction(ACTION).setData(uri(spec.id)), flags or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val ACTION = "app.diet.REMINDER"
        fun channel(kind: String) = "diet.reminder." + kind
        private fun uri(id: String) = Uri.Builder().scheme("diet").authority("reminder").appendPath(id).build()
        private fun notificationId(kind: String) = when(kind) { "food" -> 1001; "weight" -> 1002; else -> 1003 }
    }
}
