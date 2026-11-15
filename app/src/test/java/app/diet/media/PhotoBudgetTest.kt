package app.diet.media

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PhotoBudgetTest {
    @Test fun documentedProfilesMatchExactBudget() {
        val sizes = mapOf(64 to 768, 128 to 1024, 256 to 1536, 512 to 2048)
        sizes.forEach { (heap, expected) ->
            val budget = PhotoBudget.forMemoryClass(heap, false)
            assertEquals(expected to expected, budget.candidates(4000, 4000, 0).first())
        }
        val low = PhotoBudget.forMemoryClass(128, true)
        assertEquals(24 * PhotoBudget.MIB, low.total)
        assertEquals(3 * PhotoBudget.MIB, low.cache)
        assertEquals(6 * PhotoBudget.MIB, low.codecReserve)
    }
    @Test fun liveUiAllocationsRemainInBudgetAndNoUpscale() {
        val budget = PhotoBudget.forMemoryClass(256, false)
        assertEquals(640 to 480, budget.candidates(640, 480, 0).first())
        assertTrue(budget.candidates(4000, 4000, 60 * PhotoBudget.MIB).isEmpty())
        assertEquals(1 to 256, budget.target(1, 32768, 256))
        assertEquals(1, budget.candidates(64, 64, 0).size)
    }
    @Test fun invalidImageBoundsFailBeforeAllocation() {
        val budget = PhotoBudget.forMemoryClass(128, false)
        assertTrue(runCatching { budget.target(32769, 1, 2048) }.isFailure)
        assertTrue(runCatching { budget.target(8001, 8000, 2048) }.isFailure)
        assertTrue(runCatching { budget.target(0, 100, 2048) }.isFailure)
        assertTrue(runCatching { PhotoBudget.forMemoryClass(16, false) }.isFailure)
    }
    @Test fun boundedOutputNeverWritesPastLimitAndRemembersNativeSwallowedError() {
        val raw = ByteArrayOutputStream(); val limited = LimitedOutput(raw, 8)
        limited.write(byteArrayOf(1, 2, 3, 4))
        limited.write(byteArrayOf(5, 6, 7, 8))
        assertTrue(runCatching { limited.write(9) }.exceptionOrNull() is PhotoFailure.OutputOverflow)
        assertEquals(8, raw.size()); assertEquals(8, limited.count.toInt()); assertTrue(limited.overflowed)
    }
}
