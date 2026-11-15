package app.diet.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.*
import app.diet.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ReportsUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repository: DietRepository
    private lateinit var preferences: VisualPreferencesRepository
    private lateinit var db: DietDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var databaseFile: File
    private lateinit var preferenceFile: File
    private lateinit var host: UiHost
    private var visible by mutableStateOf(true)

    @Before fun setup(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val name = "report-ui-${newId()}.db"
        databaseFile = File(context.noBackupFilesDir, "diet/db/$name")
        db = DietDatabase.open(context, name)
        repository = DietRepository(context, scope, database = db)
        repository.initialize()
        preferenceFile = File(context.noBackupFilesDir, "diet/prefs/report-ui-${newId()}.preferences_pb")
        preferenceFile.parentFile!!.mkdirs()
        preferences = VisualPreferencesRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { preferenceFile }), scope)
        withTimeout(10_000) { preferences.state.first { it.ready } }
        host = UiHost(repository, preferences, TestMediaActions(), scope)
    }

    @After fun cleanup() {
        compose.runOnIdle { visible = false }; compose.waitForIdle()
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        db.close()
        listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm"), preferenceFile, File(preferenceFile.path + ".tmp")).forEach { it.delete() }
    }

    private fun show() {
        compose.setContent { if (visible) CompositionLocalProvider(LocalUi provides host) {
            DietTheme(VisualPreferences(ready = true)) { LazyColumn { item { Column { ReportsScreen() } } } }
        } }
    }
    private fun click(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        val selectedNode = compose.onAllNodes(hasText(text) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected))
        val node = if (selectedNode.fetchSemanticsNodes().isNotEmpty()) selectedNode.onFirst() else compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
        compose.waitForIdle()
    }

    @Test fun fullWidthTabsShowRecordedTotalsAndFoodTableWithoutOldPanels() {
        runBlocking {
            val snapshot = SnapshotColumns.from(NutritionSnapshot("Тест", FoodUnit.GRAM, nutrientsPerBasis = MicroNutrients(100_000_000, 10_000_000, 0, 0).toDomain()))
            repository.addFood(today(), repository.state.value.meals.first().id, LineInput(snapshot, 100_000_000), Operation())
            repository.saveWeight(today(), 7240, null, Operation())
        }
        show()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("report-bars").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Точные даты и значения").assertDoesNotExist()
        compose.onNodeWithText("Нет измерений веса").assertDoesNotExist()
        // A centre tap chooses the middle of seven dates, which is intentionally missing.
        // The synthetic food is on the last day: tap its actual rightmost bar slot.
        compose.onNodeWithTag("report-bars").performScrollTo().performTouchInput { click(Offset(center.x * 1.86f, center.y)) }
        compose.waitForIdle()
        compose.onAllNodesWithText("100,0 ккал").onLast().performScrollTo().assertExists()
        compose.onNodeWithText("Показывать частичные дни").assertDoesNotExist()
        compose.onNodeWithText("Подробнее").assertDoesNotExist()
        compose.onNodeWithText("Полнота дневника").assertDoesNotExist()
        compose.onNodeWithText("Съеденная пища").performScrollTo().assertExists()
        compose.onNodeWithText("Продукт").assertExists()
        compose.onNodeWithText("Кол-во").assertExists()
        click("Вес")
        compose.onAllNodesWithText("72,40 кг").onFirst().assertExists()
        assertTrue(repository.state.value.confirmations.isEmpty())
        assertEquals(1, runBlocking { repository.queryHistory(today(), today()).size })
    }

    @Test fun cancellingCustomCalendarKeepsLastValidPeriod() {
        show()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("report-bars").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Изменить период отчёта").performScrollTo().performClick()
        compose.onNodeWithText("30 дней").performClick()
        compose.waitUntil(10_000) { host.view["report.length"] == "30" }
        val validStart = host.view["report.start"]
        val validEnd = host.view["report.end"]
        compose.onNodeWithContentDescription("Изменить период отчёта").performScrollTo().performClick()
        compose.onNodeWithText("Свой период").performClick()
        click("Начало периода")
        compose.onNodeWithText("Выбрать дату").assertExists()
        compose.onNode(hasText("Отмена") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        assertEquals(validStart, host.view["report.start"])
        assertEquals(validEnd, host.view["report.end"])
        compose.onNodeWithText("Показать период").assertExists()
        click("Отмена")
        compose.onNodeWithText("Показать период").assertDoesNotExist()
    }

    private class TestMediaActions : UiMediaActions {
        override val state = MutableStateFlow(UiMediaState())
        override fun pickPhotos(draftId: String, date: String, freeSlots: Int) = error("No picker in report test")
        override fun cancelImport(draftId: String) = Unit
        override fun clearError() = Unit
        override fun requestNotifications() = Unit
        override fun openNotificationSettings() = Unit
        override fun consumeOpenAction(id: String) = Unit
        @Composable override fun Thumbnail(assetId: String, modifier: Modifier, description: String) { Text(description, modifier) }
    }
}
