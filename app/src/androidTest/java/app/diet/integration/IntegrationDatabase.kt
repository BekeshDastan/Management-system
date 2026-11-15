package app.diet.integration

import android.content.Context
import androidx.room.useReaderConnection
import app.diet.data.DietDatabase
import app.diet.data.DietRepository
import app.diet.data.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Real app CE context; only this instance's unique test DB is written or removed. */
internal class IntegrationDatabase(val context: Context) {
    val name = "integration-${newId()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC)
    var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); private set
    var database = DietDatabase.open(context, name); private set
    var repository = DietRepository(context, scope, clock, database); private set

    suspend fun initialize() { repository.initialize() }
    suspend fun actualPath(): File = database.useReaderConnection { connection ->
        connection.usePrepared("PRAGMA database_list") { statement ->
            var actual: File? = null
            while (statement.step()) if (statement.getText(1) == "main") actual = File(statement.getText(2)).canonicalFile
            checkNotNull(actual)
        }
    }
    suspend fun pragmas(): Triple<Long, Long, String> = database.useReaderConnection { connection ->
        val foreignKeys = connection.usePrepared("PRAGMA foreign_keys") { check(it.step()); it.getLong(0) }
        val synchronous = connection.usePrepared("PRAGMA synchronous") { check(it.step()); it.getLong(0) }
        val journal = connection.usePrepared("PRAGMA journal_mode") { check(it.step()); it.getText(0) }
        Triple(foreignKeys, synchronous, journal)
    }
    suspend fun reopen() {
        scope.coroutineContext[Job]!!.cancelAndJoin(); database.close()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database = DietDatabase.open(context, name)
        repository = DietRepository(context, scope, clock, database)
        repository.initialize()
    }
    suspend fun close() {
        scope.coroutineContext[Job]!!.cancelAndJoin(); database.close()
        val file = File(context.noBackupFilesDir, "diet/db/$name").canonicalFile
        check(file.toPath().startsWith(context.noBackupFilesDir.canonicalFile.toPath()) && file.name == name)
        listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
    }
}
