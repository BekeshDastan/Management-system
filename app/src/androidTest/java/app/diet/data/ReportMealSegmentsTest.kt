package app.diet.data

import android.content.Context
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Isolated synthetic records; validates bar segments against independent exact totals. */
@RunWith(AndroidJUnit4::class)
class ReportMealSegmentsTest {
    private lateinit var db: DietDatabase
    private lateinit var file: File
    private val meals = List(10) { MealEntity(name = "Приём $it", nameKey = "приём $it", ordinal = it.toLong(), archived = it == 0) }
    private val first = LocalDate.of(2021, 1, 1)

    @Before fun setup(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "report-segments-${newId()}.db"
        file = File(context.noBackupFilesDir, "diet/db/$name")
        db = DietDatabase.open(context, name)
        write { dao -> meals.forEach { dao.put(it) } }
    }

    @After fun cleanup() {
        if (::db.isInitialized) db.close()
        if (::file.isInitialized) listOf(file, File(file.path + "-wal"), File(file.path + "-shm")).forEach { it.delete() }
    }

    private suspend fun write(block: suspend (DietDao) -> Unit) = db.useWriterConnection { connection -> connection.immediateTransaction { block(db.dao()) } }
    private fun food(day: Int, meal: Int, kcal: Long): FoodEntryEntity = FoodEntryEntity(
        localDate = first.plusDays(day.toLong()).toString(), mealId = meals[meal].id, ordinal = meal.toLong(), kind = "quick",
        quickName = "Синтетическая запись", quickNameKey = "синтетическая запись", quick = MicroNutrients(kcal * Numbers.MICRO_FACTOR, 10_000_000, 5_000_000, 20_000_000), createdByOperationId = newId(),
    )

    @Test fun segmentsExcludePartialDaysAndKeepConfirmedZeroInMean() = runBlocking {
        write { dao ->
            dao.put(food(0, 0, 100)); dao.put(food(0, 1, 200))
            dao.put(DayConfirmationEntity(localDate = first.toString()))
            dao.put(DayConfirmationEntity(localDate = first.plusDays(1).toString()))
            dao.put(food(2, 0, 900))
            dao.put(GoalEntity(startDate = first.toString(), nutrients = MicroNutrients(2_000_000_000, 0, 0, 0)))
        }
        val r = ReportQueries.read(db, first, first.plusDays(3))
        assertEquals(2, r.nutrition.n); assertEquals(2, r.nutrition.q)
        assertEquals(1, r.nutrition.partialDays); assertEquals(1L, r.nutrition.missingDays)
        assertEquals(Rational.of(150), r.nutrition.mean!!.kcal)
        assertEquals(Rational.of(100), r.meals.single { it.id == meals[0].id }.completeTotal.kcal)
        assertEquals(Rational.of(900), r.meals.single { it.id == meals[0].id }.partialTotal.kcal)
        assertEquals("Приём 0", r.meals.single { it.id == meals[0].id }.name) // Archived label remains usable.
        assertEquals(r.series[0].nutritionMean, r.series[0].mealMeans.values.fold(Nutrients.ZERO) { sum, value -> sum + value })
        assertEquals(Nutrients.ZERO, r.series[1].nutritionMean)
        assertTrue(r.series[1].mealMeans.isEmpty())
        assertNull(r.series[2].nutritionMean)
        assertEquals(Rational.of(900), r.series[2].partialMealMeans.getValue(meals[0].id).kcal)
        assertNull(r.series[3].nutritionMean)
        assertNull(r.series[3].partialNutritionMean)
    }

    @Test fun moreThanSixMealsAreBoundedButNoNutritionIsDropped() = runBlocking {
        write { dao ->
            meals.indices.forEach { dao.put(food(0, it, (it + 1) * 10L)) }
            dao.put(DayConfirmationEntity(localDate = first.toString()))
        }
        val r = ReportQueries.read(db, first, first)
        assertEquals(7, r.meals.size)
        assertEquals(7, r.series.single().mealMeans.size)
        val other = r.meals.single { it.id == "__other_meals__" }
        assertEquals("Другие приёмы", other.name)
        assertEquals(Rational.of(340), other.completeTotal.kcal)
        val actual = r.meals.fold(Nutrients.ZERO) { sum, meal -> sum + meal.completeTotal }
        assertEquals(Nutrients(Rational.of(550), Rational.of(100), Rational.of(50), Rational.of(200)), actual)
        assertEquals(r.nutrition.mean, r.series.single().mealMeans.values.fold(Nutrients.ZERO) { sum, meal -> sum + meal })
    }

    @Test fun aggregatedStacksUseTheSameCompleteAndPartialDenominatorsAsBars() = runBlocking {
        write { dao ->
            dao.put(food(0, 0, 100)); dao.put(food(1, 1, 200))
            (0..2).forEach { dao.put(DayConfirmationEntity(localDate = first.plusDays(it.toLong()).toString())) }
            dao.put(food(3, 0, 90)); dao.put(food(4, 1, 60))
        }
        val r = ReportQueries.read(db, first, first.plusDays(1799))
        assertEquals(600, r.series.size)
        val full = r.series[0]
        assertTrue(full.aggregated); assertEquals(3, full.nutritionCompleteDays)
        assertEquals(Rational.of(100, 3), full.mealMeans.getValue(meals[0].id).kcal)
        assertEquals(Rational.of(200, 3), full.mealMeans.getValue(meals[1].id).kcal)
        assertEquals(full.nutritionMean, full.mealMeans.values.fold(Nutrients.ZERO) { sum, value -> sum + value })
        val partial = r.series[1]
        assertEquals(2, partial.nutritionPartialDays); assertNull(partial.nutritionMean)
        assertEquals(Rational.of(45), partial.partialMealMeans.getValue(meals[0].id).kcal)
        assertEquals(Rational.of(30), partial.partialMealMeans.getValue(meals[1].id).kcal)
        assertEquals(partial.partialNutritionMean, partial.partialMealMeans.values.fold(Nutrients.ZERO) { sum, value -> sum + value })
        assertEquals(3, r.nutrition.n)
        assertEquals(Rational.of(100), r.nutrition.mean!!.kcal)
    }
}
