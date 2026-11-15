package app.diet.domain

import java.math.BigDecimal
import java.math.BigInteger

/** Input and presentation rules from numeric-contract v1. All stored values are integers. */
object Numbers {
    const val MICRO_FACTOR = 1_000_000L
    const val MAX_MICROS = 1_000_000_000_000L
    const val MAX_WEIGHT_CENTI_KG = 1_000_000L
    const val MAX_HEIGHT_CENTI_CM = 100_000L
    const val MAX_SLEEP_MINUTES = 59_999
    const val MAX_OPERATION_ROWS = 500
    const val MAX_RAW_CODE_POINTS = 64

    fun parseNutrientMicros(raw: String): Long = parseFixed(raw, 6, 0, MAX_MICROS)
    fun parseGoalKcalMicros(raw: String): Long = parseFixed(raw, 6, 1, MAX_MICROS)
    fun parseQuantityMicros(raw: String): Long = parseFixed(raw, 6, 1, MAX_MICROS)
    fun parseWeightCentiKg(raw: String): Long = parseFixed(raw, 2, 1, MAX_WEIGHT_CENTI_KG)
    fun parseHeightCentiCm(raw: String): Long = parseFixed(raw, 2, 1, MAX_HEIGHT_CENTI_CM)

    fun parseOptionalWeightCentiKg(raw: String): Long? = optionalRaw(raw)?.let(::parseWeightCentiKg)
    fun parseOptionalHeightCentiCm(raw: String): Long? = optionalRaw(raw)?.let(::parseHeightCentiCm)
    fun parseOptionalAge(raw: String): Int? = optionalRaw(raw)?.let { parseWhole(it, 1, 1_000).toInt() }

    fun parseSleepMinutes(hours: String, minutes: String, zeroConfirmed: Boolean = false): Int {
        val h = parseWhole(hours, 0, 999)
        val m = parseWhole(minutes, 0, 59)
        val total = Math.addExact(Math.multiplyExact(h, 60L), m).toInt()
        if (total == 0 && !zeroConfirmed) throw ZeroSleepConfirmationRequired()
        return total
    }

    fun parseNutrients(kcal: String, protein: String, fat: String, carbs: String, target: Boolean = false): Nutrients = Nutrients.fromMicros(
        if (target) parseGoalKcalMicros(kcal) else parseNutrientMicros(kcal),
        parseNutrientMicros(protein), parseNutrientMicros(fat), parseNutrientMicros(carbs),
    )

    fun validateQuantityMicros(value: Long): Long {
        require(value in 1..MAX_MICROS) { "Количество должно быть от 0,000001 до 1 000 000" }
        return value
    }

    fun validateOperationRows(count: Int) {
        require(count in 1..MAX_OPERATION_ROWS) { "В операции должно быть от 1 до 500 строк" }
    }

    fun formatQuantityMicros(value: Long, decimalSeparator: Char = ','): String {
        validateQuantityMicros(value)
        return BigDecimal.valueOf(value, 6).stripTrailingZeros().toPlainString().replace('.', decimalSeparator)
    }

    fun formatWeightCentiKg(value: Long, decimalSeparator: Char = ','): String {
        require(value in 1..MAX_WEIGHT_CENTI_KG) { "Некорректное измерение веса" }
        return Rational.of(value, 100).format(2, decimalSeparator)
    }

    fun formatSleepMinutes(minutes: Int): String {
        require(minutes in 0..MAX_SLEEP_MINUTES) { "Некорректная длительность сна" }
        return "${minutes / 60} ч ${minutes % 60} мин"
    }

    fun nextRevision(current: Long): Long {
        require(current >= 0) { "Некорректная версия записи" }
        return Math.addExact(current, 1)
    }

    /** BigInteger.intValueExact is API 31; keep the same checked conversion on minSdk 29. */
    fun checkedInt(value: BigInteger): Int {
        if (value < MIN_INT || value > MAX_INT) throw ArithmeticException("Целое значение выходит за границы Int")
        return value.toInt()
    }

    private fun checkedRaw(raw: String): String {
        require(raw.codePointCount(0, raw.length) <= MAX_RAW_CODE_POINTS) { "Числовое поле слишком длинное" }
        return TextRules.trimWhiteSpace(raw)
    }

    private fun optionalRaw(raw: String): String? = checkedRaw(raw).takeIf { it.isNotEmpty() }

    private fun parseWhole(raw: String, min: Long, max: Long): Long = parseFixed(raw, 0, min, max)

    private fun parseFixed(raw: String, places: Int, min: Long, max: Long): Long {
        val text = checkedRaw(raw)
        require(text.isNotEmpty()) { "Заполните поле" }
        require(text.matches(if (places == 0) WHOLE else DECIMAL)) { "Введите число без знака, пробелов и экспоненты" }
        val fractionLength = text.indexOfFirst { it == '.' || it == ',' }.let { if (it < 0) 0 else text.length - it - 1 }
        require(fractionLength <= places) { "Допустимо не больше $places знаков после разделителя" }
        val result = try {
            BigDecimal(text.replace(',', '.')).movePointRight(places).longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Число выходит за допустимые границы")
        }
        require(result in min..max) { "Число выходит за допустимые границы" }
        return result
    }

    private val WHOLE = Regex("[0-9]+")
    private val DECIMAL = Regex("[0-9]+([.,][0-9]+)?")
    private val MIN_INT = BigInteger.valueOf(Int.MIN_VALUE.toLong())
    private val MAX_INT = BigInteger.valueOf(Int.MAX_VALUE.toLong())
}

class ZeroSleepConfirmationRequired : IllegalArgumentException("Подтвердите отсутствие сна, чтобы сохранить 0 ч 0 мин")
