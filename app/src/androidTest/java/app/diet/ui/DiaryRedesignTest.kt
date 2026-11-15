package app.diet.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Exercises the collapsed list rather than accepting a 40-row subtotal as a meal total. */
@RunWith(AndroidJUnit4::class)
class DiaryRedesignTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repository: DietRepository
    private lateinit var preferences: VisualPreferencesRepository
    private lateinit var database: DietDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var databaseFile: File
    private lateinit var preferenceFile: File
    private var visible by mutableStateOf(true)
    private var contentSet = false

    @Before fun setup(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val name = "diary-ui-${newId()}.db"
        databaseFile = File(context.noBackupFilesDir, "diet/db/$name")
        database = DietDatabase.open(context, name)
        repository = DietRepository(context, scope, database = database)
        repository.initialize(); repository.completeOnboarding(Operation())
        preferenceFile = File(context.noBackupFilesDir, "diet/prefs/diary-ui-${newId()}.preferences_pb")
        check(preferenceFile.parentFile!!.isDirectory || preferenceFile.parentFile!!.mkdirs())
        preferences = VisualPreferencesRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { preferenceFile }), scope)
        withTimeout(10_000) { preferences.state.first { it.ready } }
        Unit
    }

    @After fun cleanup() {
        if (contentSet) { compose.runOnIdle { visible = false }; compose.waitForIdle() }
        if (::scope.isInitialized) runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        if (::database.isInitialized) database.close()
        if (::databaseFile.isInitialized) listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm"), File(databaseFile.path + "-journal")).forEach { it.delete() }
        if (::preferenceFile.isInitialized) listOf(preferenceFile, File(preferenceFile.path + ".tmp")).forEach { it.delete() }
    }

    @Test fun mealsStartCollapsedButSummaryCountsEveryRowAndExpandedListRemainsPaged() {
        runBlocking {
            val breakfast = repository.state.value.meals.first { it.name == "Завтрак" }.id
            repeat(43) { repository.addQuickFood(LocalDate.now().toString(), breakfast, "Синт %02d".format(it), MicroNutrients(1_000_000, 0, 0, 0), Operation()) }
        }
        show()
        val disclosure = "Состав Завтрак: 43 продукта"
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(disclosure).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Синт 00").assertCountEquals(0)
        compose.onNodeWithContentDescription(disclosure).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Синт 00").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Синт 00").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Синт 42").assertCountEquals(0)
        compose.onNodeWithText("Далее →").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Синт 42").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Синт 42").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(disclosure).assertExists()
    }

    @Test fun diaryHasWeekAndRecordingSeriesWithoutRemovedLargeBanners() {
        runBlocking {
            val breakfast = repository.state.value.meals.first { it.name == "Завтрак" }.id
            repository.addQuickFood(LocalDate.now().toString(), breakfast, "Сегодня", MicroNutrients(100_000_000, 0, 0, 0), Operation())
            repository.completeDay(LocalDate.now().toString(), true, false, Operation())
        }
        show()
        val mark = dateLabel(LocalDate.now().toString()) + ", питание заполнено"
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(mark).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(mark).assertExists()
        compose.onNodeWithText("Текущая серия записей еды · 1 день").assertExists()
        listOf("Маленькое напоминание", "1 · На старте", "Открыть инструменты дня", "СЕГОДНЯ В ЦИФРАХ").forEach { compose.onAllNodesWithText(it).assertCountEquals(0) }
    }

    private fun show() {
        contentSet = true
        compose.setContent { if (visible) DietApp(repository, preferences, NoMedia()) }
    }

    private class NoMedia : UiMediaActions {
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
