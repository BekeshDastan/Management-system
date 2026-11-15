package app.diet.domain

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class NumbersTest {
    @Test fun microBoundaryAndBothDecimalSeparators() {
        assertEquals(1L, Numbers.parseQuantityMicros("0,000001"))
        assertEquals(Numbers.MAX_MICROS, Numbers.parseQuantityMicros("1000000"))
        assertEquals(1_250_000L, Numbers.parseQuantityMicros("1,25"))
        assertEquals(Numbers.parseQuantityMicros("1,25"), Numbers.parseQuantityMicros("1.25"))
        assertEquals(0L, Numbers.parseNutrientMicros("0"))
        assertEquals("0,000001", Numbers.formatQuantityMicros(1))
        assertEquals("1,25", Numbers.formatQuantityMicros(1_250_000))
    }

    @Test fun rawScaleAndMalformedInputAreNeverRoundedOrCoerced() {
        listOf("0", "0,0000001", "1,0000000", "1000000,000001", "1e3", "+1", "−0", "-0", "1 000", "1,2.3", "1,", ".1", "NaN", "Infinity", "", "١", "1\u0000").forEach { raw ->
            assertThrows("raw=$raw", IllegalArgumentException::class.java) { Numbers.parseQuantityMicros(raw) }
        }
        assertThrows(IllegalArgumentException::class.java) { Numbers.parseWeightCentiKg("80,120") }
    }

    @Test fun goalKcalMustBePositiveAndMacrosMayBeZero() {
        assertThrows(IllegalArgumentException::class.java) { Numbers.parseGoalKcalMicros("0") }
        assertEquals(1L, Numbers.parseGoalKcalMicros("0.000001"))
        assertEquals(Rational.ZERO, Numbers.parseNutrients("1", "0", "0", "0", target = true).protein)
        assertThrows(IllegalArgumentException::class.java) { Numbers.parseNutrients("1", "", "0", "0") }
    }

    @Test fun weightProfileAndRawLengthBoundaries() {
        assertEquals(1L, Numbers.parseWeightCentiKg("0,01"))
        assertEquals(1_000_000L, Numbers.parseWeightCentiKg("10000.00"))
        assertThrows(IllegalArgumentException::class.java) { Numbers.parseWeightCentiKg("10000.01") }
        assertNull(Numbers.parseOptionalAge("\u0085\u00A0"))
        assertNull(Numbers.parseOptionalHeightCentiCm(""))
        assertEquals(1_000, Numbers.parseOptionalAge("1000"))
        assertEquals(100_000L, Numbers.parseHeightCentiCm("1000"))
        assertThrows(IllegalArgumentException::class.java) { Numbers.parseOptionalAge("1001") }
        assertThrows(IllegalArgumentException::class.java) { Numbers.parseHeightCentiCm("1000.01") }
        assertEquals(0L, Numbers.parseNutrientMicros("0".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { Numbers.parseNutrientMicros("0".repeat(65)) }
        assertThrows(IllegalArgumentException::class.java) { Numbers.parseNutrientMicros(" ".repeat(65)) }
    }

    @Test fun sleepIsIntegerDurationAndZeroRequiresConfirmation() {
        assertEquals(59_999, Numbers.parseSleepMinutes("999", "59"))
        assertEquals(0, Numbers.parseSleepMinutes("0", "0", zeroConfirmed = true))
        assertThrows(ZeroSleepConfirmationRequired::class.java) { Numbers.parseSleepMinutes("0", "0") }
        listOf("1000" to "0", "1.5" to "0", "1" to "60", "1" to "", "" to "0").forEach { (h, m) ->
            assertThrows(IllegalArgumentException::class.java) { Numbers.parseSleepMinutes(h, m) }
        }
        assertEquals("7 ч 1 мин", Numbers.formatSleepMinutes(421))
    }

    @Test fun revisionOverflowAndRowBoundDoNotWrap() {
        assertEquals(1L, Numbers.nextRevision(0))
        assertThrows(ArithmeticException::class.java) { Numbers.nextRevision(Long.MAX_VALUE) }
        Numbers.validateOperationRows(500)
        assertThrows(IllegalArgumentException::class.java) { Numbers.validateOperationRows(0) }
        assertThrows(IllegalArgumentException::class.java) { Numbers.validateOperationRows(501) }
    }

    @Test fun portableBigIntegerConversionChecksBothIntBoundaries() {
        val minimum = BigInteger.valueOf(Int.MIN_VALUE.toLong())
        val maximum = BigInteger.valueOf(Int.MAX_VALUE.toLong())
        assertEquals(Int.MIN_VALUE, Numbers.checkedInt(minimum))
        assertEquals(Int.MAX_VALUE, Numbers.checkedInt(maximum))
        assertThrows(ArithmeticException::class.java) { Numbers.checkedInt(minimum - BigInteger.ONE) }
        assertThrows(ArithmeticException::class.java) { Numbers.checkedInt(maximum + BigInteger.ONE) }
        assertEquals(59_999, SleepSummary(1, 1, Rational.of(59_999)).roundedMeanMinutes)
    }
}
