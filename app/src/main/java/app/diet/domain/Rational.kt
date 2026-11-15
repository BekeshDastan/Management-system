package app.diet.domain

import java.math.BigInteger
import java.util.concurrent.CancellationException

/** Exact, reduced fraction. Nutrition calculations never pass through Double. */
class Rational private constructor(
    val numerator: BigInteger,
    val denominator: BigInteger,
) : Comparable<Rational> {
    operator fun plus(other: Rational): Rational {
        checkCalculation()
        val common = denominator.gcd(other.denominator)
        val leftFactor = other.denominator.divide(common)
        val rightFactor = denominator.divide(common)
        val left = multiplyChecked(numerator, leftFactor)
        val right = multiplyChecked(other.numerator, rightFactor)
        checkIntermediate(maxOf(left.abs().bitLength(), right.abs().bitLength()) + 1)
        return of(left.add(right), multiplyChecked(denominator, leftFactor))
    }

    operator fun minus(other: Rational): Rational = this + -other
    operator fun unaryMinus(): Rational = if (isZero) ZERO else of(numerator.negate(), denominator)

    operator fun times(other: Rational): Rational {
        checkCalculation()
        if (isZero || other.isZero) return ZERO
        val a = numerator.gcd(other.denominator)
        val b = other.numerator.gcd(denominator)
        return of(
            multiplyChecked(numerator.divide(a), other.numerator.divide(b)),
            multiplyChecked(denominator.divide(b), other.denominator.divide(a)),
        )
    }

    operator fun div(other: Rational): Rational {
        require(!other.isZero) { "Деление на ноль недопустимо" }
        return this * of(other.denominator, other.numerator)
    }

    val isZero: Boolean get() = numerator.signum() == 0
    val sign: Int get() = numerator.signum()

    override fun compareTo(other: Rational): Int {
        checkCalculation()
        if (sign != other.sign) return sign.compareTo(other.sign)
        val common = denominator.gcd(other.denominator)
        return multiplyChecked(numerator, other.denominator.divide(common))
            .compareTo(multiplyChecked(other.numerator, denominator.divide(common)))
    }

    /** HALF_UP is applied once, to the exact signed result. */
    fun roundedScaledInteger(scale: Int = 0): BigInteger {
        require(scale in 0..6) { "Точность отображения должна быть от 0 до 6" }
        val multiplier = BigInteger.TEN.pow(scale)
        val scaled = multiplyChecked(numerator.abs(), multiplier)
        val parts = scaled.divideAndRemainder(denominator)
        val rounded = if (parts[1].shiftLeft(1) >= denominator) parts[0] + BigInteger.ONE else parts[0]
        return if (sign < 0 && rounded.signum() != 0) rounded.negate() else rounded
    }

    fun format(scale: Int = 1, decimalSeparator: Char = ','): String {
        val rounded = roundedScaledInteger(scale)
        val negative = rounded.signum() < 0
        val digits = rounded.abs().toString().padStart(scale + 1, '0')
        val body = if (scale == 0) digits else digits.dropLast(scale) + decimalSeparator + digits.takeLast(scale)
        return if (negative) "-$body" else body
    }

    /** Canonical, bounded non-negative representation accepted by saved snapshots. */
    fun toStoredPair(): Pair<String, String> {
        requireStorableNutrient()
        return numerator.toString() to denominator.toString()
    }

    fun requireStorableNutrient(): Rational {
        require(sign >= 0) { "Пищевая ценность не может быть отрицательной" }
        require(numerator.toString().length <= STORED_DIGITS && denominator.toString().length <= STORED_DIGITS) {
            "Точный результат не помещается в формат снимка"
        }
        require(this <= MAX_STORED_VALUE) { "Слишком большое значение пищевой ценности" }
        return this
    }

    override fun equals(other: Any?): Boolean = other is Rational && numerator == other.numerator && denominator == other.denominator
    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()
    override fun toString(): String = "$numerator/$denominator"

    companion object {
        const val AGGREGATE_BITS = 262_144
        const val INTERMEDIATE_BITS = 1_048_576
        const val STORED_DIGITS = 128
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)
        private val MAX_STORED_VALUE = Rational(BigInteger.TEN.pow(30), BigInteger.ONE)

        fun of(numerator: Long, denominator: Long = 1): Rational = of(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator))

        fun of(numerator: BigInteger, denominator: BigInteger = BigInteger.ONE): Rational {
            checkCalculation()
            require(denominator.signum() != 0) { "Знаменатель не может быть нулём" }
            checkIntermediate(numerator.abs().bitLength())
            checkIntermediate(denominator.abs().bitLength())
            if (numerator.signum() == 0) return ZERO
            val common = numerator.gcd(denominator)
            val sign = if (denominator.signum() < 0) -1 else 1
            val p = numerator.divide(common).let { if (sign < 0) it.negate() else it }
            val q = denominator.divide(common).abs()
            if (p.abs().bitLength() > AGGREGATE_BITS || q.bitLength() > AGGREGATE_BITS) {
                throw CalculationComplexityException("Расчёт слишком сложный. Сузьте период или состав")
            }
            return Rational(p, q)
        }

        fun fromMicros(value: Long): Rational = of(value, Numbers.MICRO_FACTOR)

        /** Reject corrupt saved data instead of silently normalising it during a read. */
        fun fromStored(numerator: String, denominator: String): Rational {
            requireCanonicalUnsigned(numerator)
            requireCanonicalUnsigned(denominator)
            val p = BigInteger(numerator)
            val q = BigInteger(denominator)
            require(q.signum() > 0) { "Повреждённый знаменатель снимка" }
            require(p.gcd(q) == BigInteger.ONE && (p.signum() != 0 || q == BigInteger.ONE)) {
                "Повреждённая неканоническая дробь снимка"
            }
            return Rational(p, q).requireStorableNutrient()
        }

        private fun requireCanonicalUnsigned(raw: String) {
            require(raw.isNotEmpty() && raw.length <= STORED_DIGITS && raw.all { it in '0'..'9' }) {
                "Повреждённое целое снимка"
            }
            require(raw == "0" || raw.first() != '0') { "Неканоническая запись снимка" }
        }

        internal fun checkCalculation() {
            if (Thread.currentThread().isInterrupted) throw CancellationException("Расчёт отменён")
        }

        private fun checkIntermediate(bits: Int) {
            if (bits > INTERMEDIATE_BITS) throw CalculationComplexityException("Превышен защитный предел точного расчёта")
        }

        private fun multiplyChecked(a: BigInteger, b: BigInteger): BigInteger {
            checkCalculation()
            if (a.signum() == 0 || b.signum() == 0) return BigInteger.ZERO
            checkIntermediate(Math.addExact(a.abs().bitLength(), b.abs().bitLength()))
            return a.multiply(b)
        }
    }
}

class CalculationComplexityException(message: String) : IllegalArgumentException(message)
