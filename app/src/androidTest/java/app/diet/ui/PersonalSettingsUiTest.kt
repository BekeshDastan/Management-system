package app.diet.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.*
import app.diet.integration.IntegrationDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PersonalSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: IntegrationDatabase
    private lateinit var preferences: VisualPreferencesRepository
    private lateinit var file: File
    private lateinit var host: UiHost
    private var visible by mutableStateOf(true)
    private var shown = false

    @Before fun setup(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fixture = IntegrationDatabase(context)
        fixture.initialize()
        fixture.repository.completeOnboarding(Operation())
        fixture.repository.saveWeight("2026-11-14", 7000, null, Operation())
        fixture.repository.saveWeight("2026-11-15", 7240, null, Operation())
        fixture.repository.setVisibleDate("2026-11-14")
        file = VisualPreferencesRepository.preferencesFile(context, "personal-settings-${newId()}.preferences_pb")
        preferences = VisualPreferencesRepository(PreferenceDataStoreFactory.create(scope = fixture.scope, produceFile = { file }), fixture.scope)
        withTimeout(10_000) { preferences.state.first { it.ready } }
        host = UiHost(fixture.repository, preferences, SilentMedia(), fixture.scope)
        host.date = "2026-11-14"
        host.root("settings")
    }

    @After fun cleanup(): Unit = runBlocking {
        if (shown) { compose.runOnIdle { visible = false }; compose.waitForIdle() }
        if (::fixture.isInitialized) fixture.close()
        if (::file.isInitialized) {
            check(file.name.startsWith("personal-settings-"))
            listOf(file, File(file.path + ".tmp")).forEach { check(!it.exists() || it.delete()) }
        }
    }

    private fun show() {
        shown = true
        compose.setContent {
            if (visible) {
                LaunchedEffect(Unit) { preferences.state.collect { host.preferences = it } }
                CompositionLocalProvider(LocalUi provides host, LocalReducedMotion provides true) {
                    MaterialTheme { Surface(Modifier.fillMaxSize()) { RetainedViewport(host.screenKey) { SettingsScreen() } } }
                }
            }
        }
    }
    private fun click(label: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithText(label)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick(); compose.waitForIdle()
    }

    @Test fun personalSummaryUsesLatestWeightWhileToolsStayCollapsedUntilRequested() {
        show()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("72,4 кг").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Мой вес").assertExists()
        compose.onNodeWithText("Мои калории и цели").assertExists()
        compose.onNodeWithText("Библиотека продуктов и рецептов  →").assertDoesNotExist()
        compose.onNodeWithText("Добавить виджет").assertDoesNotExist()
        assertEquals("2026-11-14", host.date)
        assertEquals(7000, fixture.repository.state.value.weights.single().centiKg)
        click("Настройки")
        compose.onNodeWithText("Библиотека продуктов и рецептов  →").assertExists()
        compose.onNodeWithText("Добавить виджет").assertExists()
    }

    @Test fun desiredWeightUsesItsOwnPreferenceAndNeverCreatesAMeasuredWeight() {
        show(); click("Желаемый вес")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Вес, кг").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Вес, кг").performScrollTo().performTextReplacement("67,35")
        click("Сохранить")
        compose.waitUntil(10_000) { preferences.state.value.desiredWeightCentiKg == 6735L }
        compose.onNodeWithText("67,35 кг").assertExists()
        runBlocking {
            assertEquals(7240, fixture.repository.latestWeight("2026-11-15")!!.centiKg)
            assertEquals(7000, fixture.repository.latestWeight("2026-11-14")!!.centiKg)
        }
        assertEquals("2026-11-14", host.date)
    }

    private class SilentMedia : UiMediaActions {
        override val state = MutableStateFlow(UiMediaState())
        override fun pickPhotos(draftId: String, date: String, freeSlots: Int) = Unit
        override fun cancelImport(draftId: String) = Unit
        override fun clearError() = Unit
        override fun requestNotifications() = Unit
        override fun openNotificationSettings() = Unit
        override fun consumeOpenAction(id: String) = Unit
        @Composable override fun Thumbnail(assetId: String, modifier: Modifier, description: String) = Unit
    }
}
