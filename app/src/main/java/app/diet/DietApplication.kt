package app.diet

import android.app.Application
import app.diet.catalog.StarterCatalog
import app.diet.data.DietRepository
import app.diet.data.VisualPreferencesRepository
import app.diet.media.PhotoEngine
import app.diet.notifications.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DietApplication : Application() {
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repository by lazy { DietRepository(this, backgroundScope) }
    val visualPreferences by lazy { VisualPreferencesRepository(this, backgroundScope) }
    val photos by lazy { PhotoEngine(this, repository, backgroundScope) }
    val reminders by lazy { ReminderScheduler(this, repository) }
    val mediaActions by lazy { AppMediaActions(this) }
    private val initialization = Mutex()
    private var prepared = false

    /** Receivers access only reminder tables, never seed or decode the catalog. */
    suspend fun prepareUi() = initialization.withLock {
        if (prepared) return@withLock
        repository.initialize()
        StarterCatalog.install(this, repository)
        photos.recoverAndClean()
        reminders.reconcile()
        mediaActions.refreshNotifications()
        backgroundScope.launch {
            repository.state.map { it.reminders }.distinctUntilChanged().collect {
                reminders.reconcile()
                mediaActions.refreshNotifications()
            }
        }
        prepared = true
    }
}
