package app.diet.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import app.diet.DietApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ReminderScheduler.ACTION) runAsync(context) { (context.applicationContext as DietApplication).reminders.handleAlarm(intent) }
    }
}
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED))
            runAsync(context) { (context.applicationContext as DietApplication).reminders.reconcile() }
    }
}
private fun BroadcastReceiver.runAsync(context: Context, work: suspend () -> Unit) {
    val pending = goAsync()
    val app = context.applicationContext as DietApplication
    val lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Diet:reminder").apply { setReferenceCounted(false) }
    try { lock.acquire(10_000) } catch (_: SecurityException) { pending.finish(); return }
    app.backgroundScope.launch(Dispatchers.IO) {
        try { withTimeout(8_000) { work() } }
        catch (_: Exception) { /* Persistent outbox remains for the next trigger. */ }
        finally { try { if (lock.isHeld) lock.release() } finally { pending.finish() } }
    }
}
