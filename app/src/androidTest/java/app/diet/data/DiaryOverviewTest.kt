package app.diet.data

import android.content.Context
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.domain.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiaryOverviewTest {
    private lateinit var database: DietDatabase
    private lateinit var file: File
    private val today = LocalDate.of(2026, 11, 15)
    private val meals = List(9) { MealEntity(name = "Приём $it", nameKey = "приём $it", ordinal = it.toLong(), archived = it == 8) }

    @Before fun setup(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "diary-overview-${newId()}.db"
        file = File(context.noBackupFilesDir, "diet/db/$name")
        database = DietDatabase.open(context, name)
        write { dao -> meals.forEach { dao.put(it) } }
    }

    @After fun cleanup() {
        if (::database.isInitialized) database.close()
        if (::file.isInitialized) listOf(file, File(file.path + "-wal"), File(file.path + "-shm")).forEach { it.delete() }
    }

    private suspend fun write(block: suspend (DietDao) -> Unit) = database.useWriterConnection { connection -> connection.immediateTransaction { block(database.dao()) } }
    private fun food(date: LocalDate, meal: Int = 0, ordinal: Long = 0) = FoodEntryEntity(localDate = date.toString(), mealId = meals[meal].id, ordinal = ordinal, kind = "quick", quickName = "Тест", quick = MicroNutrients(2_000_000, 0, 0, 0), createdByOperationId = newId())

    @Test fun fullMealTotalsIncludeEveryKeysetPageAndEveryCustomMealWithoutRoundingRows() = runBlocking {
        val third = SnapshotColumns.from(NutritionSnapshot("Треть", FoodUnit.GRAM, 100_000_000, Nutrients(Rational.of(100, 3), Rational.ZERO, Rational.ZERO, Rational.ZERO)))
        write { dao ->
            repeat(601) { index ->
                val base = food(today, index % meals.size, (index % 3).toLong())
                dao.put(if (index < 301) base.copy(kind = "portion", quickName = null, quick = null, snapshot = third, quantityMicros = 1_000_000) else base)
            }
            dao.put(food(today.minusDays(1))) // Must not leak into the selected day's sums.
        }
        val result = DiaryOverviewQueries.read(database, today, today)
        assertEquals(meals.map { it.id }.toSet(), result.meals.keys)
        assertEquals(601L, result.meals.values.sumOf { it.entryCount })
        assertEquals(Rational.of(301) / Rational.of(3) + Rational.of(600), result.meals.values.fold(Nutrients.ZERO) { n, meal -> n + meal.nutrients }.kcal)
        meals.forEachIndexed { mealIndex, meal ->
            val indices = (0 until 601).filter { it % meals.size == mealIndex }
            val expected = indices.count { it < 301 }.toLong().let { Rational.of(it) / Rational.of(3) } + Rational.of(indices.count { it >= 301 }.toLong() * 2)
            assertEquals(indices.size.toLong(), result.meals.getValue(meal.id).entryCount)
            assertEquals(expected, result.meals.getValue(meal.id).nutrients.kcal)
        }
    }

    @Test fun recordingSeriesIncludesExplicitZeroAndYesterdayWhileIgnoringWeightOnlyAndGaps() = runBlocking {
        write { dao ->
            dao.put(food(today.minusDays(1)))
            dao.put(DayConfirmationEntity(localDate = today.minusDays(2).toString())) // Explicit zero is a recorded date.
            dao.put(food(today.minusDays(3)))
            dao.put(WeightEntity(localDate = today.minusDays(4).toString(), centiKg = 7240))
            dao.put(food(today.minusDays(5)))
            dao.put(food(today.plusDays(1))) // Not part of today's series or week markers.
        }
        val current = DiaryOverviewQueries.read(database, today, today)
        assertEquals(3L, current.recordingStreak)
        assertFalse(current.week.last().recorded)
        assertTrue(current.week.single { it.date == today.minusDays(2) }.completed)
        assertTrue(current.week.single { it.date == today.minusDays(2) }.recorded)
        assertFalse(current.week.single { it.date == today.minusDays(4) }.recorded)
        val past = DiaryOverviewQueries.read(database, today.minusWeeks(2), today)
        assertEquals(3L, past.recordingStreak) // Browsing history never changes the actual current series.
        write { dao -> dao.put(food(today)) }
        assertEquals(4L, DiaryOverviewQueries.read(database, today, today).recordingStreak)
    }

    @Test fun completedCheckAndRecordedMarkerRemainDistinctAndMalformedQuickIsNotZero() = runBlocking {
        write { dao ->
            dao.put(food(today))
            dao.put(DayConfirmationEntity(localDate = today.minusDays(1).toString()))
        }
        val result = DiaryOverviewQueries.read(database, today, today)
        assertEquals(7, result.week.size)
        assertEquals(1, result.week.first().date.dayOfWeek.value)
        assertFalse(result.week.last().completed)
        assertTrue(result.week.last().recorded)
        assertTrue(result.week[result.week.lastIndex - 1].completed)
        assertTrue(result.meals.values.single().entryCount == 1L)
        write { dao -> dao.put(food(today).copy(quick = null)) }
        assertTrue(runCatching { DiaryOverviewQueries.read(database, today, today) }.exceptionOrNull() is IllegalArgumentException)
    }
}
