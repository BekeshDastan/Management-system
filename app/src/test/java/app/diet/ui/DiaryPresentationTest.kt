package app.diet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DiaryPresentationTest {
    @Test fun countsAndSeriesUseRussianNumberForms() {
        assertEquals("0 продуктов", foodPositionCount(0))
        assertEquals("1 продукт", foodPositionCount(1))
        assertEquals("2 продукта", foodPositionCount(2))
        assertEquals("11 продуктов", foodPositionCount(11))
        assertEquals("21 продукт", foodPositionCount(21))
        assertEquals("Текущая серия записей еды · 3 дня", recordingStreakLabel(3))
        assertEquals("Текущая серия записей еды · 12 дней", recordingStreakLabel(12))
        assertEquals("Текущая серия записей еды · 21 день", recordingStreakLabel(21))
    }
}
