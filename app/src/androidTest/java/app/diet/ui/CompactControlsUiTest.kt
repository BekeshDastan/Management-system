package app.diet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.VisualPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CompactControlsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun calendarCancelLeavesDateAndLargeTextConfirmRemainsReachable() {
        var value by mutableStateOf("2026-11-15")
        var changes = 0
        compose.setContent {
            DietTheme(VisualPreferences(ready = true, theme = "dark")) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Box(Modifier.width(320.dp)) {
                        DateChoice("Начало плана", value, LocalDate.of(1900, 1, 1), LocalDate.of(2027, 2, 13)) { value = it; changes++ }
                    }
                }
            }
        }
        compose.onNodeWithText("Начало плана").performClick()
        compose.onNodeWithText("Отмена").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("2026-11-15", value); assertEquals(0, changes) }
        compose.onNodeWithText("Начало плана").performClick()
        compose.onNodeWithText("Выбрать").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("2026-11-15", value); assertEquals(1, changes) }
        compose.onNodeWithText("Выбрать дату").assertDoesNotExist()
    }

    @Test fun fullWidthTabsKeepSelectionAndFitTheirContainer() {
        var current by mutableStateOf("food")
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    FullWidthTabs(listOf("food" to "Питание", "weight" to "Вес", "sleep" to "Сон"), current) { current = it }
                }
            }
        }
        compose.onNodeWithText("Питание").assertIsSelected()
        compose.onNodeWithText("Сон").performClick().assertIsSelected()
        compose.onNodeWithText("Питание").assertIsNotSelected()
        compose.runOnIdle { assertEquals("sleep", current) }
        val tabs = listOf("Питание", "Вес", "Сон").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        assertEquals(tabs[0].width, tabs[1].width, 1f)
        assertEquals(tabs[1].width, tabs[2].width, 1f)
        assertTrue(tabs.zipWithNext().all { (a, b) -> a.right <= b.left + 1f })
    }
}
