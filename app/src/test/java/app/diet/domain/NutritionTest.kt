package app.diet.domain

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class NutritionTest {
    private fun values(kcal: String, protein: String = "0", fat: String = "0", carbs: String = "0") = Numbers.parseNutrients(kcal, protein, fat, carbs)
    private fun amount(value: String) = Numbers.parseQuantityMicros(value)
    private fun product(kcal: String, unit: FoodUnit = FoodUnit.GRAM) = NutritionSnapshot("Тест", unit, nutrientsPerBasis = values(kcal))

    @Test fun portionsRespectBasisAndDoNotInventDensities() {
        val snapshot = NutritionSnapshot("Тест", FoodUnit.GRAM, nutrientsPerBasis = values("150", "10", "4", "18"))
        assertEquals(values("120", "8", "3.2", "14.4"), snapshot.portion(amount("80")))
        assertEquals(Rational.of(100), product("40", FoodUnit.MILLILITRE).portion(amount("250")).kcal)
        assertEquals(Rational.of(180), product("120", FoodUnit.PORTION).portion(amount("1.5")).kcal)
    }

    @Test fun recipeUsesActualCookedYield() {
        val recipe = RecipeCalculation.calculate("Блюдо", listOf(RecipeIngredient(product("150"), amount("200")), RecipeIngredient(product("80"), amount("100"))), amount("250"))
        assertEquals(Rational.of(380), recipe.total.kcal)
        assertEquals(Rational.of(152), recipe.per100Grams.kcal)
        assertEquals(Rational.of(76), recipe.snapshot.portion(amount("50")).kcal)
    }

    @Test fun repeatingRecipeFractionSurvivesSnapshotAndSummation() {
        val recipe = RecipeCalculation.calculate("Треть", listOf(RecipeIngredient(product("100"), amount("1"))), amount("3"))
        assertEquals(Rational.of(100, 3), recipe.per100Grams.kcal)
        val part = recipe.snapshot.portion(amount("1"))
        assertEquals(Rational.of(1, 3), part.kcal)
        assertEquals("1,0", (part + part + part).kcal.format())
        val (p, q) = recipe.per100Grams.kcal.toStoredPair()
        assertEquals(Rational.of(100, 3), Rational.fromStored(p, q))
        val editedSource = product("1000")
        assertEquals(Rational.of(1000), editedSource.nutrientsPerBasis.kcal)
        assertEquals(Rational.of(2, 3), recipe.snapshot.portion(amount("2")).kcal)
    }

    @Test fun maximumRecipeUsesBigIntegerBeforeProductsOverflowLong() {
        val ingredient = RecipeIngredient(product("1000000", FoodUnit.PORTION), amount("1000000"))
        val recipe = RecipeCalculation.calculate("Большой", List(500) { ingredient }, amount("0.000001"))
        assertEquals(Rational.of(BigInteger.valueOf(5).multiply(BigInteger.TEN.pow(14))), recipe.total.kcal)
        assertEquals(Rational.of(BigInteger.valueOf(5).multiply(BigInteger.TEN.pow(22))), recipe.per100Grams.kcal)
        assertEquals(Rational.of(BigInteger.valueOf(5).multiply(BigInteger.TEN.pow(26))), recipe.snapshot.portion(amount("1000000")).kcal)
        assertThrows(IllegalArgumentException::class.java) { RecipeCalculation.calculate("Лишнее", List(501) { ingredient }, amount("1")) }
        assertThrows(IllegalArgumentException::class.java) { RecipeIngredient(recipe.snapshot, amount("1")) }
    }

    @Test fun quickPortionHasNoInventedWeightAndZeroTargetPercentIsMissing() {
        val quick = NutritionSnapshot.quickPortion("Обед", values("300", "0", "2", "4"))
        assertEquals(FoodUnit.PORTION, quick.unit)
        assertEquals(Numbers.MICRO_FACTOR, quick.basisMicros)
        assertEquals(values("300", "0", "2", "4"), quick.portion(Numbers.MICRO_FACTOR))
        val percentages = quick.nutrientsPerBasis.percentageOf(values("600", "0", "4", "8"))
        assertEquals("50,0", percentages.kcal?.format())
        assertNull(percentages.protein)
        assertEquals("0,1", (values("0.04") + values("0.04")).kcal.format())
    }
}
