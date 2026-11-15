package app.diet.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import java.io.File

@Database(entities = [
        ReminderOccurrenceEntity::class,
        ProfileEntity::class,
        OnboardingEntity::class,
        MealEntity::class,
        CategoryEntity::class,
        CatalogItemEntity::class,
        RecipeIngredientEntity::class,
        RecentEntity::class,
        FoodEntryEntity::class,
        DayConfirmationEntity::class,
        GoalEntity::class,
        GoalTimelineEntity::class,
        WeightEntity::class,
        SleepEntity::class,
        MomentEntity::class,
        TemplateEntity::class,
        TemplateLineEntity::class,
        PlanEntity::class,
        PlanLineEntity::class,
        TransferEntity::class,
        QuickPortionGroupEntity::class,
        QuickPortionEntity::class,
        ReceiptEntity::class,
        OperationResultEntity::class,
        DraftEntity::class,
        ReminderEntity::class,
        ReminderOutboxEntity::class,
        MediaAssetEntity::class,
        PhotoEntity::class,
        DraftMediaEntity::class,
        MediaImportBatchEntity::class,
        MediaImportItemEntity::class,
        MediaReadLeaseEntity::class,
        MediaCleanupEntity::class
    ], version = 1, exportSchema = true)
abstract class DietDatabase : RoomDatabase() {
    internal abstract fun dao(): DietDao

    companion object {
        internal val TABLES = arrayOf("reminder_occurrences", "local_profile", "onboarding_state", "meals", "catalog_categories", "catalog_items", "recipe_ingredients", "catalog_recents", "food_entries", "food_day_confirmations", "goals", "goal_timeline_state", "weight_records", "sleep_records", "day_moments", "meal_templates", "template_lines", "meal_plans", "plan_lines", "plan_transfers", "quick_portion_groups", "quick_portion_values", "operation_receipts", "operation_results", "drafts", "reminder_specs", "reminder_schedule_outbox", "media_assets", "photos", "draft_media", "media_import_batches", "media_import_items", "media_read_leases", "media_cleanup")
        @Volatile private var instance: DietDatabase? = null
        fun get(context: Context): DietDatabase = instance ?: synchronized(this) {
            instance ?: open(context).also { instance = it }
        }
        /** A separate instance is for instrumentation only; production uses get(). */
        internal fun open(context: Context, databaseName: String = "diet.db"): DietDatabase {
            check(!context.isDeviceProtectedStorage) { "Diet требует credential-encrypted storage" }
            require(databaseName.matches(Regex("[a-zA-Z0-9_.-]+")))
            val root = File(context.noBackupFilesDir, "diet/db").canonicalFile
            check(root.isDirectory || root.mkdirs()) { "Не удалось создать каталог данных" }
            check(root.toPath().startsWith(context.noBackupFilesDir.canonicalFile.toPath()))
            val file = File(root, databaseName)
            return Room.databaseBuilder<DietDatabase>(context.applicationContext, file.absolutePath)
                .setDriver(VerifiedSQLiteDriver())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(Dispatchers.IO)
                .addCallback(object : Callback() {
                    override fun onOpen(connection: SQLiteConnection) {
                        verifyConnection(connection)
                        connection.prepare("PRAGMA foreign_key_check").use { check(!it.step()) { "Нарушены связи локальных данных" } }
                    }
                })
                .build()
        }
        internal fun verifyConnection(c: SQLiteConnection) {
            c.prepare("PRAGMA foreign_keys").use { check(it.step() && it.getLong(0) == 1L) { "SQLite foreign_keys не включён" } }
            c.prepare("PRAGMA synchronous").use { check(it.step() && it.getLong(0) == 2L) { "SQLite synchronous не FULL" } }
            c.prepare("PRAGMA journal_mode").use { check(it.step() && it.getText(0).equals("wal", true)) { "SQLite не в WAL" } }
        }
    }
}

/** Every opened connection is configured and read back; Room cannot silently use defaults. */
internal class VerifiedSQLiteDriver : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()
    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        try {
            connection.execSQL("PRAGMA journal_mode = WAL")
            connection.execSQL("PRAGMA synchronous = FULL")
            connection.execSQL("PRAGMA foreign_keys = ON")
            DietDatabase.verifyConnection(connection)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    // Room may configure NORMAL for WAL after driver.open(); keep the selected
                    // FULL policy on every pooled connection, including later writer opens.
                    val setting = Regex("(?is)^\\s*PRAGMA\\s+(?:main\\.)?synchronous\\s*=.*")
                    return connection.prepare(if (setting.matches(sql)) "PRAGMA synchronous = FULL" else sql)
                }
            }
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }
}
