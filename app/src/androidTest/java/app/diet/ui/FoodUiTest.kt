package app.diet.ui

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.*
import app.diet.domain.FoodUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FoodUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repository: DietRepository
    private lateinit var preferences: VisualPreferencesRepository
    private lateinit var db: DietDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var databaseFile: File
    private lateinit var preferenceFile: File
    private var visible by mutableStateOf(true)
    private var contentSet = false

    @Before fun setUp(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val name = "food-ui-${newId()}.db"
        databaseFile = File(context.noBackupFilesDir, "diet/db/$name")
        db = DietDatabase.open(context, name)
        repository = DietRepository(context, scope, database = db)
        repository.initialize(); repository.completeOnboarding(Operation())
        preferenceFile = File(context.noBackupFilesDir, "diet/prefs/food-ui-${newId()}.preferences_pb")
        check(preferenceFile.parentFile!!.isDirectory || preferenceFile.parentFile!!.mkdirs())
        preferences = VisualPreferencesRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { preferenceFile }), scope)
        withTimeout(10_000) { preferences.state.first { it.ready } }
        Unit
    }
    @After fun tearDown() {
        if (contentSet) { compose.runOnIdle { visible = false }; compose.waitForIdle() }
        if (::scope.isInitialized) runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        if (::db.isInitialized) db.close()
        if (::databaseFile.isInitialized) {
            check(databaseFile.name.startsWith("food-ui-"))
            listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm"), File(databaseFile.path + "-journal")).forEach { check(!it.exists() || it.delete()) }
        }
        if (::preferenceFile.isInitialized) {
            check(preferenceFile.name.startsWith("food-ui-"))
            listOf(preferenceFile, File(preferenceFile.path + ".tmp")).forEach { check(!it.exists() || it.delete()) }
        }
    }
    private suspend fun seed(name: String, kcal: Long = 100_000_000) {
        repository.saveProduct(null, name, FoodUnit.GRAM, null, MicroNutrients(kcal, 0, 0, 0), baseRevision = null, operation = Operation())
    }
    private fun show() {
        val media = FoodTestMedia()
        contentSet = true
        compose.setContent { if (visible) DietApp(repository, preferences, media) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("＋ Добавить еду").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun click(text: String) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Missing expected action: $text\n" + compose.onRoot(useUnmergedTree = true).printToString(), timeout)
        }
        val node = compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick(); compose.waitForIdle()
    }
    private fun field(label: String, value: String) {
        val node = compose.onNodeWithText(label)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performTextReplacement(value); compose.waitForIdle()
    }
    private fun actions(name: String) {
        val node = compose.onNodeWithContentDescription("Действия с $name")
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick(); compose.waitForIdle()
    }

    @Test fun createAndSearchStayVisibleAtTheBottomOfFortyFoodRows() {
        runBlocking { repeat(40) { seed("Продукт %02d".format(it)) } }
        show(); click("＋ Добавить еду")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Продукт 39").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Продукт 39").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("＋ Создать").assertIsDisplayed().performClick()
        compose.onNodeWithText("Свой продукт").assertIsDisplayed().performClick()
        field("Название", "Новый синтетический")
        field("Калории, ккал", "123"); field("Белки, г", "0"); field("Жиры, г", "0"); field("Углеводы, г", "0")
        compose.onNodeWithText("Сохранить").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { repository.state.value.catalog.any { it.name == "Новый синтетический" } }
        compose.onNodeWithText("Найти продукт или рецепт").assertIsDisplayed()
        field("Найти продукт или рецепт", "Новый синтетический")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Продукт 39").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasText("Новый синтетический") and !hasSetTextAction()).assertExists()
        assertTrue(repository.state.value.entries.isEmpty())
    }

    @Test fun multipleSelectionReviewsPortionsAndCommitsOneMealThenMovesOriginalEntry() {
        runBlocking { seed("Альфа", 100_000_000); seed("Бета", 200_000_000) }
        show(); click("＋ Добавить еду"); click("Выбрать несколько")
        click("Выбрать Альфа"); field("Количество, г", "50")
        field("Найти продукт или рецепт", "Бета")
        click("Выбрать Бета"); field("Количество, г", "75")
        assertTrue(repository.state.value.entries.isEmpty())
        compose.onNodeWithText("Готово (2)").assertIsDisplayed().performClick()
        compose.onNodeWithText("Добавить в дневник").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { repository.state.value.entries.size == 2 }
        val original = repository.state.value.entries.single { it.displayName() == "Бета" }
        assertEquals(setOf(50_000_000L, 75_000_000L), repository.state.value.entries.map { it.quantityMicros }.toSet())
        assertEquals(1, repository.state.value.entries.map { it.createdByOperationId }.distinct().size)
        assertEquals("200,0", app.diet.domain.Nutrients.sum(repository.state.value.entries.map { it.nutrients() }).kcal.format(1))
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Действия с Бета").fetchSemanticsNodes().isNotEmpty() }
        // The confirmation snackbar intentionally overlays the bottom content; wait for that
        // observable transient message before touching an action located underneath it.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Сохранено на устройстве").fetchSemanticsNodes().isEmpty() }
        actions("Бета")
        click("Перенести в другой приём")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Перенести еду").fetchSemanticsNodes().isNotEmpty() }
        click("Обед")
        compose.onNodeWithText("Переместить запись").assertIsDisplayed().performClick()
        val lunch = repository.state.value.meals.single { it.name == "Обед" }.id
        compose.waitUntil(10_000) { repository.state.value.entries.any { it.id == original.id && it.mealId == lunch } }
        val moved = repository.state.value.entries.single { it.id == original.id }
        assertEquals(original.snapshot, moved.snapshot)
        assertEquals(original.quantityMicros, moved.quantityMicros)
        assertEquals(2, repository.state.value.entries.size)
    }

    private class FoodTestMedia : UiMediaActions {
        override val state = MutableStateFlow(UiMediaState())
        override fun pickPhotos(draftId: String, date: String, freeSlots: Int) = error("Picker not used in food tests")
        override fun cancelImport(draftId: String) = Unit
        override fun clearError() = Unit
        override fun requestNotifications() = Unit
        override fun openNotificationSettings() = Unit
        override fun consumeOpenAction(id: String) = Unit
        @Composable override fun Thumbnail(assetId: String, modifier: Modifier, description: String) { Text(description, modifier) }
    }
}
