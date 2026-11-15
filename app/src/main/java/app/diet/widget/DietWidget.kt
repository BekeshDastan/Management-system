package app.diet.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.UserManager
import android.view.View
import android.widget.RemoteViews
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import app.diet.DietApplication
import app.diet.MainActivity
import app.diet.R
import app.diet.data.DietRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** No widget data file: each render reads current CE Room data and sends bounded text only. */
class DietWidgetController(
    context: Context,
    private val repository: DietRepository,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private val manager = AppWidgetManager.getInstance(context)
    private val component = ComponentName(context, DietWidgetProvider::class.java)
    private val refreshMutex = Mutex()
    private var observer: Job? = null

    fun hasWidgets(): Boolean = manager.getAppWidgetIds(component).isNotEmpty()

    /** Starts only while a widget exists; it does not seed catalog or mutate the selected diary date. */
    @Synchronized fun startObserving() {
        if (!hasWidgets() || observer?.isActive == true) return
        observer = scope.launch(Dispatchers.IO) {
            repository.database.invalidationTracker.createFlow(
                "food_entries", "food_day_confirmations", "goals", "onboarding_state",
                emitInitialState = true,
            ).conflate().collect { refresh() }
        }
    }

    @Synchronized fun stopObserving() { observer?.cancel(); observer = null }

    suspend fun refresh() = refreshMutex.withLock {
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) { cancelDateAlarm(); return@withLock }
        // Never access credential-encrypted records while the user is locked after boot.
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return@withLock
        try {
            withTimeout(5_000) {
                val onboarded = repository.database.useReaderConnection { c ->
                    c.deferredTransaction { repository.database.dao().allOnboarding().singleOrNull()?.completed == true }
                }
                if (!onboarded) {
                    ids.forEach { updatePlaceholder(it, "Начнём дневник?", "Откройте Diet для первого запуска") }
                } else {
                    var date = LocalDate.now()
                    var day = repository.pageReportDays(date, date, 0, 1).days.single()
                    // A query can cross midnight or a timezone change. Read again, never relabel an old result.
                    if (date != LocalDate.now()) {
                        date = LocalDate.now()
                        day = repository.pageReportDays(date, date, 0, 1).days.single()
                    }
                    val summary = WidgetSummary.from(day)
                    ids.forEach { id ->
                        if (isKeyguard(id)) updatePlaceholder(id, "Откройте Diet", "Дневник доступен после разблокировки")
                        else manager.updateAppWidget(id, render(id, summary))
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            ids.forEach { updatePlaceholder(it, "Обновим дневник", "Нажмите ↻ или откройте Diet") }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            ids.forEach { updatePlaceholder(it, "Не удалось обновить", "Нажмите ↻ или откройте Diet") }
        } finally { scheduleDateAlarm() }
    }

    private fun isKeyguard(id: Int): Boolean =
        manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            .and(AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD) != 0

    internal fun render(id: Int, summary: WidgetSummary): RemoteViews = baseViews(id).apply {
        setTextViewText(R.id.widget_date, summary.dateLabel)
        setTextViewText(R.id.widget_value, summary.calories)
        setTextViewText(R.id.widget_goal, summary.goal)
        setTextViewText(R.id.widget_status, summary.status)
        setContentDescription(R.id.widget_root, "Diet, ${summary.dateLabel}. ${summary.calories}. ${summary.goal}. ${summary.status}")
    }

    private fun updatePlaceholder(id: Int, title: String, detail: String) {
        manager.updateAppWidget(id, baseViews(id).apply {
            setTextViewText(R.id.widget_date, "Дневник")
            setTextViewText(R.id.widget_value, title)
            setTextViewText(R.id.widget_goal, detail)
            setTextViewText(R.id.widget_status, "")
            setContentDescription(R.id.widget_root, "Diet. $title. $detail")
        })
    }

    private fun baseViews(id: Int) = RemoteViews(context.packageName, R.layout.diet_widget).apply {
        setOnClickPendingIntent(R.id.widget_summary, openIntent(context, id, null))
        setOnClickPendingIntent(R.id.widget_food, openIntent(context, id, "food"))
        setOnClickPendingIntent(R.id.widget_weight, openIntent(context, id, "weight"))
        setOnClickPendingIntent(R.id.widget_sleep, openIntent(context, id, "sleep"))
        setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context))
        setViewVisibility(R.id.widget_actions, View.VISIBLE)
    }

    private fun scheduleDateAlarm() {
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (!hasWidgets()) { alarm.cancel(refreshIntent(context)); return }
        // Non-wakeup and inexact: no exact-alarm permission, battery exception or foreground service.
        alarm.setWindow(AlarmManager.RTC, nextWidgetDateChange(ZonedDateTime.now(ZoneId.systemDefault())), 15 * 60_000L, refreshIntent(context))
    }

    fun cancelDateAlarm() = context.getSystemService(AlarmManager::class.java).cancel(refreshIntent(context))

    companion object {
        const val ACTION_OPEN = "app.diet.OPEN_WIDGET"
        const val ACTION_REFRESH = "app.diet.widget.REFRESH"
        const val EXTRA_FORM = "open_form"

        fun requestPin(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, DietWidgetProvider::class.java), null, null)
        }

        internal fun openIntent(context: Context, id: Int, kind: String?): PendingIntent {
            require(kind == null || kind in setOf("food", "weight", "sleep"))
            val intent = Intent(context, MainActivity::class.java).apply {
                action = if (kind == null) Intent.ACTION_MAIN else ACTION_OPEN
                // Date is deliberately absent: the existing UI dispatcher chooses the local date at tap time.
                data = Uri.parse("diet-widget://open/$id/${kind ?: "home"}")
                if (kind != null) putExtra(EXTRA_FORM, kind)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, DietWidgetProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class DietWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_ENABLED,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
            AppWidgetManager.ACTION_APPWIDGET_RESTORED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            DietWidgetController.ACTION_REFRESH -> updateAsync(context)
            AppWidgetManager.ACTION_APPWIDGET_DISABLED -> {
                val controller = (context.applicationContext as DietApplication).widgets
                controller.stopObserving()
                controller.cancelDateAlarm()
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun updateAsync(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext as DietApplication
        val lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Diet:widget").apply { setReferenceCounted(false) }
        try { lock.acquire(9_000L) }
        catch (_: SecurityException) { pending.finish(); return }
        app.backgroundScope.launch(Dispatchers.IO) {
            try {
                withTimeout(7_000L) { app.widgets.refresh(); app.widgets.startObserving() }
            } catch (_: Exception) { /* Next app/launcher refresh retries; no stored snapshot is overwritten. */ }
            finally { try { if (lock.isHeld) lock.release() } finally { pending.finish() } }
        }
    }
}
