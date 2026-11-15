package app.diet.domain

data class Nutrients(
    val kcal: Rational = Rational.ZERO,
    val protein: Rational = Rational.ZERO,
    val fat: Rational = Rational.ZERO,
    val carbs: Rational = Rational.ZERO,
) {
    operator fun plus(other: Nutrients) = Nutrients(kcal + other.kcal, protein + other.protein, fat + other.fat, carbs + other.carbs)
    operator fun minus(other: Nutrients) = Nutrients(kcal - other.kcal, protein - other.protein, fat - other.fat, carbs - other.carbs)
    operator fun times(factor: Rational) = Nutrients(kcal * factor, protein * factor, fat * factor, carbs * factor)
    operator fun div(divisor: Rational) = Nutrients(kcal / divisor, protein / divisor, fat / divisor, carbs / divisor)

    fun requireNonNegative(): Nutrients {
        require(components().all { it.sign >= 0 }) { "Пищевая ценность не может быть отрицательной" }
        return this
    }

    fun requireStorable(): Nutrients {
        components().forEach { it.requireStorableNutrient() }
        return this
    }

    fun components(): List<Rational> = listOf(kcal, protein, fat, carbs)

    fun percentageOf(goal: Nutrients): NutrientPercentages {
        fun percentage(actual: Rational, target: Rational) = if (target.sign > 0) actual * Rational.of(100) / target else null
        return NutrientPercentages(percentage(kcal, goal.kcal), percentage(protein, goal.protein), percentage(fat, goal.fat), percentage(carbs, goal.carbs))
    }

    companion object {
        val ZERO = Nutrients()
        fun fromMicros(kcal: Long, protein: Long, fat: Long, carbs: Long): Nutrients {
            require(listOf(kcal, protein, fat, carbs).all { it in 0..Numbers.MAX_MICROS }) { "Некорректная пищевая ценность" }
            return Nutrients(Rational.fromMicros(kcal), Rational.fromMicros(protein), Rational.fromMicros(fat), Rational.fromMicros(carbs))
        }
        fun sum(values: Iterable<Nutrients>): Nutrients = values.fold(ZERO) { result, value -> result + value }
    }
}

data class NutrientPercentages(val kcal: Rational?, val protein: Rational?, val fat: Rational?, val carbs: Rational?)

enum class FoodUnit { GRAM, MILLILITRE, PORTION }
enum class FoodSourceKind { PRODUCT, RECIPE, QUICK }

/** A self-contained immutable historical basis, independent from a mutable catalog. */
data class NutritionSnapshot(
    val name: String,
    val unit: FoodUnit,
    val basisMicros: Long = if (unit == FoodUnit.PORTION) Numbers.MICRO_FACTOR else 100 * Numbers.MICRO_FACTOR,
    val nutrientsPerBasis: Nutrients,
    val portionName: String? = if (unit == FoodUnit.PORTION) "порция" else null,
    val calcContractVersion: Int = 1,
    val sourceKind: FoodSourceKind = FoodSourceKind.PRODUCT,
) {
    init {
        require(name == TextRules.name(name)) { "Имя снимка должно быть нормализовано" }
        val expectedBasis = if (unit == FoodUnit.PORTION) Numbers.MICRO_FACTOR else 100 * Numbers.MICRO_FACTOR
        require(basisMicros == expectedBasis) { "База должна быть 100 г, 100 мл или 1 именованная порция" }
        if (unit == FoodUnit.PORTION) require(portionName != null && portionName == TextRules.name(portionName, 30)) { "Укажите название порции" }
        else require(portionName == null) { "Название порции не применяется к граммам или миллилитрам" }
        require(calcContractVersion == 1) { "Неизвестная версия числового снимка" }
        nutrientsPerBasis.requireStorable()
    }

    fun portion(quantityMicros: Long): Nutrients {
        Numbers.validateQuantityMicros(quantityMicros)
        return (nutrientsPerBasis * Rational.of(quantityMicros, basisMicros)).requireStorable()
    }

    companion object {
        fun quickPortion(name: String, wholePortion: Nutrients) = NutritionSnapshot(
            TextRules.name(name), FoodUnit.PORTION, nutrientsPerBasis = wholePortion, sourceKind = FoodSourceKind.QUICK,
        )
    }
}

data class RecipeIngredient(val snapshot: NutritionSnapshot, val quantityMicros: Long) {
    init {
        Numbers.validateQuantityMicros(quantityMicros)
        require(snapshot.sourceKind != FoodSourceKind.RECIPE) { "Вложенные рецепты не поддерживаются" }
    }
}

data class RecipeCalculation(val total: Nutrients, val snapshot: NutritionSnapshot, val yieldMicros: Long) {
    val per100Grams: Nutrients get() = snapshot.nutrientsPerBasis

    companion object {
        fun calculate(name: String, ingredients: List<RecipeIngredient>, yieldMicros: Long): RecipeCalculation {
            Numbers.validateOperationRows(ingredients.size)
            Numbers.validateQuantityMicros(yieldMicros)
            val total = Nutrients.sum(ingredients.map { it.snapshot.portion(it.quantityMicros) }).requireStorable()
            val per100 = (total * Rational.of(100 * Numbers.MICRO_FACTOR, yieldMicros)).requireStorable()
            return RecipeCalculation(total, NutritionSnapshot(TextRules.name(name), FoodUnit.GRAM, nutrientsPerBasis = per100, sourceKind = FoodSourceKind.RECIPE), yieldMicros)
        }
    }
}
