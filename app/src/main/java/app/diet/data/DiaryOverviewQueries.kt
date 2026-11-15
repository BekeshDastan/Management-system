package app.diet.data

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteStatement
import app.diet.domain.DomainDates
import app.diet.domain.Nutrients
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class DiaryMealSummary(val entryCount: Long, val nutrients: Nutrients)
data class DiaryWeekDay(val date: LocalDate, val completed: Boolean, val recorded: Boolean)
data class DiaryOverview(val date: LocalDate, val meals: Map<String, DiaryMealSummary>, val week: List<DiaryWeekDay>, val recordingStreak: Long)

/** A single read snapshot. Every food row contributes, independently of the visible 40-row page. */
suspend fun DietRepository.readDiaryOverview(date: LocalDate, today: LocalDate = LocalDate.now()): DiaryOverview =
    DiaryOverviewQueries.read(database, date, today)

internal object DiaryOverviewQueries {
    private const val PAGE = 250

    suspend fun read(database: DietDatabase, date: LocalDate, today: LocalDate): DiaryOverview {
        DomainDates.validateFactDate(date, today)
        return withContext(Dispatchers.IO) { withTimeout(10_000) {
            val job = currentCoroutineContext()[Job]
            database.useReaderConnection { connection -> connection.deferredTransaction {
                val totals = linkedMapOf<String, DiaryMealSummary>()
                var after: Triple<String, Long, String>? = null
                val projection = "SELECT mealId,ordinal,id,kind,snapshot_name,snapshot_unit,snapshot_basisMicros,snapshot_portionName," +
                    "snapshot_kcalNumerator,snapshot_kcalDenominator,snapshot_proteinNumerator,snapshot_proteinDenominator," +
                    "snapshot_fatNumerator,snapshot_fatDenominator,snapshot_carbsNumerator,snapshot_carbsDenominator," +
                    "snapshot_snapshotFormatVersion,snapshot_calcContractVersion,snapshot_sourceKind,snapshot_nameKey," +
                    "quantityMicros,quick_kcal,quick_protein,quick_fat,quick_carbs FROM food_entries WHERE localDate=? "
                do {
                    val key = after
                    var loaded = 0
                    connection.usePrepared(projection + (if (key == null) "" else "AND (mealId,ordinal,id) > (?,?,?) ") + "ORDER BY mealId,ordinal,id LIMIT ?") { row ->
                        row.bindText(1, date.toString())
                        if (key == null) row.bindLong(2, PAGE.toLong())
                        else { row.bindText(2, key.first); row.bindLong(3, key.second); row.bindText(4, key.third); row.bindLong(5, PAGE.toLong()) }
                        while (row.step()) {
                            job?.ensureActive()
                            val meal = row.getText(0)
                            val old = totals[meal] ?: DiaryMealSummary(0, Nutrients.ZERO)
                            totals[meal] = DiaryMealSummary(Math.addExact(old.entryCount, 1), old.nutrients + nutrients(row))
                            after = Triple(meal, row.getLong(1), row.getText(2)); loaded++
                        }
                    }
                } while (loaded == PAGE)

                val first = date.minusDays((date.dayOfWeek.value - 1).toLong())
                val last = minOf(today, first.plusDays(6))
                val completed = mutableSetOf<String>()
                connection.usePrepared("SELECT localDate FROM food_day_confirmations WHERE localDate BETWEEN ? AND ?") { row ->
                    row.bindText(1, maxOf(DomainDates.MIN, first).toString()); row.bindText(2, last.toString())
                    while (row.step()) completed += row.getText(0)
                }
                val recorded = completed.toMutableSet()
                connection.usePrepared("SELECT DISTINCT localDate FROM food_entries WHERE localDate BETWEEN ? AND ?") { row ->
                    row.bindText(1, maxOf(DomainDates.MIN, first).toString()); row.bindText(2, last.toString())
                    while (row.step()) recorded += row.getText(0)
                }

                var streak = 0L
                var expected = today
                // Distinct dates are streamed. Unfinished today may continue yesterday's series.
                connection.usePrepared("SELECT localDate FROM (SELECT localDate FROM food_entries WHERE localDate<=? UNION SELECT localDate FROM food_day_confirmations WHERE localDate<=?) ORDER BY localDate DESC") { row ->
                    row.bindText(1, today.toString()); row.bindText(2, today.toString())
                    while (row.step()) {
                        job?.ensureActive()
                        val found = DomainDates.parse(row.getText(0))
                        if (streak == 0L && found == today.minusDays(1)) expected = found
                        if (found != expected) break
                        streak = Math.addExact(streak, 1); expected = expected.minusDays(1)
                    }
                }
                DiaryOverview(date, totals.toMap(), List(7) { offset ->
                    val day = first.plusDays(offset.toLong())
                    DiaryWeekDay(day, day.toString() in completed, day.toString() in recorded)
                }, streak)
            } }
        } }
    }

    private fun nutrients(row: SQLiteStatement): Nutrients = when (row.getText(3)) {
        "quick" -> {
            require((21..24).none(row::isNull)) { "Повреждена быстрая запись питания" }
            MicroNutrients(row.getLong(21), row.getLong(22), row.getLong(23), row.getLong(24)).toDomain()
        }
        "portion" -> {
            fun version(index: Int): Int { val n = row.getLong(index); require(n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()); return n.toInt() }
            SnapshotColumns(
                name = row.getText(4), unit = row.getText(5), basisMicros = row.getLong(6), portionName = if (row.isNull(7)) null else row.getText(7),
                kcalNumerator = row.getText(8), kcalDenominator = row.getText(9), proteinNumerator = row.getText(10), proteinDenominator = row.getText(11),
                fatNumerator = row.getText(12), fatDenominator = row.getText(13), carbsNumerator = row.getText(14), carbsDenominator = row.getText(15),
                snapshotFormatVersion = version(16), calcContractVersion = version(17), sourceKind = row.getText(18), nameKey = row.getText(19),
            ).toDomain().portion(row.getLong(20))
        }
        else -> throw IllegalArgumentException("Неизвестный вид записи питания")
    }
}
