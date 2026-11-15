package app.diet.domain

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.util.concurrent.CancellationException

class RationalTest {
    @Test fun fractionsAreCanonicalAndAdditionIsExact() {
        assertEquals(Rational.of(1, 3), Rational.of(2, 6))
        assertEquals(Rational.ZERO, Rational.of(0, -999))
        val third = Rational.of(1, 3)
        assertEquals("0,3", third.format())
        assertEquals(Rational.ONE, third + third + third)
        assertEquals("1,0", (third + third + third).format())
    }

    @Test fun halfUpIsSignedAndDoesNotDisplayNegativeZero() {
        assertEquals("0,1", Rational.of(5, 100).format())
        assertEquals("-0,1", Rational.of(-5, 100).format())
        assertEquals("0,0", Rational.of(-1, 1_000).format())
        assertEquals("80,01", Rational.of(16_001, 200).format(2))
    }

    @Test fun crossCancellationAvoidsArtificialGrowth() {
        val p = BigInteger.ONE.shiftLeft(100_000)
        val first = Rational.of(p, BigInteger.valueOf(3))
        val inverse = Rational.of(BigInteger.valueOf(3), p)
        assertEquals(Rational.ONE, first * inverse)
        assertEquals(Rational.of(-1, 2), Rational.of(1, 3) / Rational.of(-2, 3))
    }

    @Test fun storedFractionRoundTripsAndRejectsCorruption() {
        val source = Rational.of(1, 3)
        assertEquals(source, Rational.fromStored(source.toStoredPair().first, source.toStoredPair().second))
        listOf("2" to "6", "0" to "5", "1" to "0", "-1" to "2", "01" to "2", "1" to "02", "1e3" to "1", "1".repeat(129) to "1").forEach { (p, q) ->
            assertThrows(IllegalArgumentException::class.java) { Rational.fromStored(p, q) }
        }
    }

    @Test fun storageLimitDoesNotRestrictReportAggregate() {
        val maximum = Rational.of(BigInteger.TEN.pow(30))
        assertEquals(maximum, maximum.requireStorableNutrient())
        val sum = maximum + maximum
        assertThrows(IllegalArgumentException::class.java) { sum.requireStorableNutrient() }
        assertEquals(Rational.of(BigInteger.TEN.pow(30).multiply(BigInteger.TWO)), sum)
    }

    @Test fun aggregateBitBoundaryIsExplicit() {
        val lastPermitted = BigInteger.ONE.shiftLeft(Rational.AGGREGATE_BITS - 1)
        assertEquals(Rational.AGGREGATE_BITS, Rational.of(lastPermitted).numerator.bitLength())
        assertThrows(CalculationComplexityException::class.java) { Rational.of(lastPermitted.shiftLeft(1)) }
        assertThrows(CalculationComplexityException::class.java) { Rational.of(BigInteger.ONE.shiftLeft(Rational.INTERMEDIATE_BITS + 1)) }
    }

    @Test fun cancellationDoesNotBecomeZero() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) { Rational.of(1, 3) + Rational.ONE }
        } finally { Thread.interrupted() }
    }
}
