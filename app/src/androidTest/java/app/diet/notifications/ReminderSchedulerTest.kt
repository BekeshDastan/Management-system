package app.diet.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.room.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.diet.data.*
import app.diet.messages.ReminderMessages
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

/** Uses actual framework alarms/channels and notifications, with an isolated synthetic Room. */
@RunWith(AndroidJUnit4::class)
class ReminderSchedulerTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var db: DietDatabase
    private lateinit var root: File
    private lateinit var repo: DietRepository
    private lateinit var scheduler: ReminderScheduler
    private lateinit var spec: ReminderEntity
    private lateinit var now: Instant
    private lateinit var zone: ZoneId
    private var grantedForTest = false

    @Before fun setup(): Unit = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(base, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(base.packageName, Manifest.permission.POST_NOTIFICATIONS)
            grantedForTest = true
        }
        root = File(base.cacheDir, "notification-test-" + newId()).apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        zone = ZoneId.systemDefault()
        now = LocalDate.now(zone).atTime(12, 0).atZone(zone).toInstant()
        val clock = Clock.fixed(now, zone)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        db = DietDatabase.open(context, "fixture.db")
        repo = DietRepository(context, scope, clock, db)
        repo.initialize()
        scheduler = ReminderScheduler(context, repo, clock)
        scheduler.createChannels()
        assertTrue("Test environment must permit app notifications", scheduler.appAllowed())
        assertTrue("The synthetic test requires the food channel enabled", scheduler.channelAllowed("food"))
        spec = repo.reminderSpecs().single { it.kind == "food" }
    }

    @After fun cleanup(): Unit = runBlocking {
        if (::repo.isInitialized) {
            val current = repo.reminderSpecs().single { it.kind == "food" }
            repo.saveReminder(current.id, false, current.minuteOfDay, current.weekDaysMask, current.revision, Operation(newId()))
            scheduler.reconcile()
        }
        if (::scope.isInitialized) scope.cancel()
        if (::db.isInitialized) db.close()
        if (::root.isInitialized) root.deleteRecursively()
        if (grantedForTest && Build.VERSION.SDK_INT >= 33) {
            val base = ApplicationProvider.getApplicationContext<Context>()
            InstrumentationRegistry.getInstrumentation().uiAutomation.revokeRuntimePermission(base.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private suspend fun enable(): ReminderEntity {
        repo.saveReminder(spec.id, true, 11 * 60 + 59, 127, spec.revision, Operation(newId()))
        return repo.reminderSpecs().single { it.id == spec.id }.also { spec = it }
    }
    private fun alarm(item: ReminderEntity = spec, zoneName: String = zone.id) =
        Intent(context, ReminderReceiver::class.java).setAction(ReminderScheduler.ACTION)
            .setData(Uri.parse("diet://reminder/" + item.id)).putExtra("id", item.id)
            .putExtra("generation", item.generation).putExtra("date", now.atZone(zone).toLocalDate().toString())
            .putExtra("zone", zoneName).putExtra("instant", now.minusSeconds(60).toEpochMilli())
    private fun pending(): PendingIntent? = PendingIntent.getBroadcast(context, spec.id.hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(ReminderScheduler.ACTION).setData(Uri.parse("diet://reminder/" + spec.id)),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    private suspend fun occurrenceCount(): Long = db.useReaderConnection { connection ->
        connection.usePrepared("SELECT COUNT(*) FROM reminder_occurrences") { statement -> assertTrue(statement.step()); statement.getLong(0) }
    }
    private suspend fun posted() = withTimeout(3000) {
        val manager = context.getSystemService(NotificationManager::class.java)
        while (true) {
            val found = manager.activeNotifications.singleOrNull { it.id == 1001 }
            if (found != null) return@withTimeout found
            delay(50)
        }
        error("unreachable")
    }

    @Test fun registrationPostReplayAndDisableUseRealFrameworkServices(): Unit = runBlocking {
        enable()
        assertEquals(1, repo.pendingReminders().size)
        scheduler.reconcile()
        assertTrue(repo.pendingReminders().isEmpty())
        assertNotNull("An immutable scheduled PendingIntent exists", pending())
        scheduler.handleAlarm(alarm())
        val notification = posted().notification
        assertEquals(ReminderMessages.title("food"), notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(ReminderMessages.BODY, notification.extras.getString(Notification.EXTRA_TEXT))
        assertNotNull(notification.contentIntent)
        assertEquals(1L, occurrenceCount())
        scheduler.handleAlarm(alarm())
        assertEquals("Duplicate callbacks must not create a second occurrence", 1L, occurrenceCount())
        assertTrue("Next date is rearmed", pending() != null)
        repo.saveReminder(spec.id, false, spec.minuteOfDay, spec.weekDaysMask, spec.revision, Operation(newId()))
        scheduler.reconcile()
        assertNull(pending())
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 1001 })
    }

    @Test fun oldGenerationWrongZoneAndNewGenerationAfterClaimCannotDuplicate(): Unit = runBlocking {
        val old = enable()
        repo.saveReminder(old.id, true, old.minuteOfDay + 1, 127, old.revision, Operation(newId()))
        spec = repo.reminderSpecs().single { it.id == old.id }
        scheduler.handleAlarm(alarm(old))
        assertEquals(0L, occurrenceCount())
        scheduler.handleAlarm(alarm(zoneName = if (zone.id == "UTC") "Asia/Tokyo" else "UTC"))
        assertEquals(0L, occurrenceCount())
        scheduler.handleAlarm(alarm())
        posted()
        assertEquals(1L, occurrenceCount())
        repo.saveReminder(spec.id, true, spec.minuteOfDay, 127, spec.revision, Operation(newId()))
        spec = repo.reminderSpecs().single { it.id == spec.id }
        scheduler.handleAlarm(alarm())
        assertEquals("One claim per reminder/date survives generation changes", 1L, occurrenceCount())
    }
}
