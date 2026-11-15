package app.diet.ui

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.*
import app.diet.domain.FoodUnit
import app.diet.domain.Numbers
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.serialization.encodeToString

/** Isolated synthetic files. Never deletes/edits the application's own diary or preferences. */
@RunWith(AndroidJUnit4::class)
class DietUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repository: DietRepository
    private lateinit var preferences: VisualPreferencesRepository
    private lateinit var db: DietDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var databaseFile: File
    private lateinit var preferenceFile: File
    private var contentVisible by mutableStateOf(true)
    private var contentSet = false

    @Before fun setUp(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val name = "ui-${newId()}.db"
        databaseFile = File(context.noBackupFilesDir, "diet/db/$name")
        db = DietDatabase.open(context, name)
        repository = DietRepository(context, scope, database = db)
        repository.initialize()
        preferenceFile = File(context.noBackupFilesDir, "diet/prefs/ui-${newId()}.preferences_pb")
        check(preferenceFile.parentFile?.isDirectory == true || preferenceFile.parentFile?.mkdirs() == true)
        preferences = VisualPreferencesRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { preferenceFile }), scope)
        withTimeout(10_000) { preferences.state.first { it.ready } }
        Unit
    }

    @After fun tearDown() {
        if (contentSet) { compose.runOnIdle { contentVisible = false }; compose.waitForIdle() }
        if (::scope.isInitialized) runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        if (::db.isInitialized) db.close()
        if (::databaseFile.isInitialized) listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm")).forEach { it.delete() }
        if (::preferenceFile.isInitialized) listOf(preferenceFile, File(preferenceFile.path + ".tmp")).forEach { it.delete() }
    }

    private fun show(large: Boolean = false) {
        contentSet = true
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else density.fontScale)) { if (contentVisible) DietApp(repository, preferences, TestMediaActions()) }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Начать").fetchSemanticsNodes().isNotEmpty() || repository.state.value.onboarding?.completed == true }
    }
    private fun click(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().performClick(); compose.waitForIdle() }
    private fun field(label: String, value: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithText(label).performScrollTo().performTextReplacement(value) }

    @Test fun onboardingFoodWeightSleepAndReportUseRealPersistentRecords() {
        runBlocking { repository.saveProduct(null, "Синтетический продукт", FoodUnit.GRAM, null, MicroNutrients(150_000_000, 10_000_000, 4_000_000, 18_000_000), baseRevision = null, operation = Operation()) }
        show()
        click("Начать"); field("Как к вам обращаться", "Тест"); click("Далее")
        compose.waitUntil(10_000) { repository.state.value.onboarding?.step == "goals" }
        click("Начать без целей")
        compose.waitUntil(10_000) { repository.state.value.onboarding?.completed == true }
        click("＋ Добавить еду")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Синтетический продукт").fetchSemanticsNodes().isNotEmpty() }
        click("Выбрать порцию"); field("Количество, г", "80"); click("Добавить в дневник")
        compose.waitUntil(10_000) { repository.state.value.entries.size == 1 }
        assertEquals("120", repository.state.value.entries.single().nutrients().kcal.format(0))
        click("Записать вес →"); field("Вес, кг", "72,4"); click("Сохранить")
        compose.waitUntil(10_000) { repository.state.value.weights.size == 1 }
        click("Записать сон →"); field("Часы", "7"); field("Минуты", "30"); click("Сохранить")
        compose.waitUntil(10_000) { repository.state.value.sleeps.size == 1 }
        assertEquals(7240, repository.state.value.weights.single().centiKg)
        assertEquals(450, repository.state.value.sleeps.single().minutes)
        compose.onNodeWithText("Прогресс").performClick()
        compose.onNodeWithText("Мой отчёт").assertExists()
        click("+  Питание")
        compose.onNodeWithText("Среднее по N=0 заполненным дням", substring = true).assertExists()
        // A partial food day must not silently enter the completed-day mean.
        assertTrue(repository.state.value.confirmations.isEmpty())
    }

    @Test fun emptyReportAndLargeTextKeepPrimaryActionsReachable() {
        runBlocking { repository.completeOnboarding(Operation()) }
        show(large = true)
        compose.onNodeWithText("Прогресс").performClick()
        click("+  Вес")
        compose.onNodeWithText("Нет измерений веса").assertExists()
        click("+  Сон")
        compose.onNodeWithText("Нет записей сна").assertExists()
        compose.onNodeWithText("Дневник").performClick()
        click("＋ Добавить еду")
        click("Быстрая запись КБЖУ")
        field("Калории, ккал", "200"); field("Белки, г", "0"); field("Жиры, г", "0"); field("Углеводы, г", "0")
        compose.onNodeWithText("Добавить в дневник").performScrollTo().assertIsEnabled()
        assertTrue(repository.state.value.entries.isEmpty())
    }

    @Test fun rawDraftSurvivesInvalidInputAndRetryFindsReceiptBeforeClosedDraft() = runBlocking {
        val session = UiFormSession.create(repository, scope, "weight", fields = mapOf("date" to today(), "weight" to "72,"))
        session.checkpoint()
        session.stopDebounce()
        val stored = requireNotNull(repository.freshDraft(session.entity.id))
        val restored = UiFormSession.restore(repository, scope, stored)
        assertEquals("72,", restored.data["weight"])
        assertNotNull(formValidation(restored))
        restored.field("weight", "72,4")
        val operation = restored.freeze()
        repository.saveWeight(today(), Numbers.parseWeightCentiKg(restored.data["weight"]).toInt(), null, operation)
        val retry = restored.freeze()
        assertEquals(operation.id, retry.id)
        val replayed = repository.saveWeight(today(), 7240, null, retry)
        assertTrue(replayed.replayed)
        assertEquals(1, repository.state.value.weights.size)
    }

    @Test fun lostCheckpointAcknowledgementAdoptsLatestPayloadWithoutChangingOperation() = runBlocking {
        val session = UiFormSession.create(repository, scope, "weight", fields = mapOf("date" to today(), "weight" to "72"))
        session.stopDebounce(); session.field("weight", "72,4"); session.stopDebounce()
        val originalId = session.data.operationId
        // The durable write finished, but the old UI never received its revision.
        repository.checkpointDraft(session.entity.copy(rawPayload = DraftJson.encodeToString(session.data), operationId = originalId), null)
        val operation = session.freeze()
        assertEquals(originalId, operation.id)
        repository.saveWeight(today(), 7240, null, operation)
        assertEquals(7240, repository.state.value.weights.single().centiKg)
    }

    @Test fun submittedPayloadIsLockedUntilSerializedAbsenceProof() = runBlocking {
        val session = UiFormSession.create(repository, scope, "weight", fields = mapOf("date" to today(), "weight" to "72,4"))
        session.freeze()
        val id = session.data.operationId
        session.field("weight", "99")
        assertEquals("72,4", session.data["weight"])
        assertEquals(id, session.data.operationId)
        assertTrue(DraftJson.decodeFromString<UiFormData>(requireNotNull(repository.freshDraft(session.entity.id)).rawPayload).submissionPending)
        assertNull(session.recover())
        session.notCommitted(); session.field("weight", "73")
        assertEquals("73", session.data["weight"])
        assertNotEquals(id, session.data.operationId)
        session.stopDebounce()
    }

    @Test fun preferenceRetryRecoversBeforeTouchingCommittedDraft() = runBlocking {
        val initial = PreferenceDraft(newId(), "theme", preferences.state.value.revisions["theme"] ?: 1, rawFields = mapOf("theme" to "dark"))
        val session = UiPreferenceSession(preferences, scope, initial, false)
        session.checkpoint()
        preferences.apply("theme", session.fields, initial.baseRevision, session.token, session.draft.id, session.draft.draftRevision)
        session.apply() // The UI lost the first completion and still holds an OPEN-looking revision.
        val state = withTimeout(10_000) { preferences.state.first { it.theme == "dark" } }
        assertEquals(initial.baseRevision + 1, state.revisions["theme"])
        assertTrue(state.drafts.none { it.group == "theme" })
    }

    @Test fun preferenceCheckpointLostAcknowledgementAdoptsMatchingTokenAndFields() = runBlocking {
        val initial = PreferenceDraft(newId(), "theme", preferences.state.value.revisions["theme"] ?: 1, rawFields = mapOf("theme" to "dark"))
        val session = UiPreferenceSession(preferences, scope, initial, false)
        preferences.checkpoint(initial.copy(rawFields = session.fields + mapOf("__operation" to session.token, "__pending" to "false")), null)
        session.apply()
        val state = withTimeout(10_000) { preferences.state.first { it.theme == "dark" } }
        assertEquals(initial.baseRevision + 1, state.revisions["theme"])
    }

    @Test fun numericPasteOver64CodePointsKeepsPriorValueCheckpointAndOperation() = runBlocking {
        val session = UiFormSession.create(repository, scope, "weight", fields = mapOf("date" to today(), "weight" to "72,4"))
        session.checkpoint(); session.stopDebounce()
        val operation = session.data.operationId
        session.numericField("weight", "9".repeat(65))
        assertEquals("72,4", session.data["weight"])
        assertEquals(operation, session.data.operationId)
        session.numericField("weight", "😀".repeat(65))
        session.checkpoint()
        assertEquals("72,4", DraftJson.decodeFromString<UiFormData>(requireNotNull(repository.freshDraft(session.entity.id)).rawPayload)["weight"])
        assertTrue(repository.state.value.weights.isEmpty())
    }

    @Test fun calorieDisplayKeepsOneDecimalAndBalanceHandlesZeroMacroGoal() {
        contentSet = true
        compose.setContent { if (contentVisible) DietTheme(VisualPreferences(ready = true)) { NutritionText(app.diet.domain.Nutrients(kcal = app.diet.domain.Rational.of(8, 100))) } }
        compose.onNodeWithText("0,1 ккал").assertExists()
        assertEquals("Выше цели 2,0 г", nutritionBalance(app.diet.domain.Rational.of(2), app.diet.domain.Rational.ZERO, "г"))
        assertEquals("Осталось 0,0 г", nutritionBalance(app.diet.domain.Rational.ZERO, app.diet.domain.Rational.ZERO, "г"))
    }

    @Test fun hidingEveryHomeBlockKeepsDayCompletionReachableAndExplicitZeroConfirmation() {
        runBlocking {
            repository.completeOnboarding(Operation())
            val order = VisualPreferences().homeOrder.joinToString(",")
            preferences.apply("home", mapOf("order" to order, "hidden" to order), preferences.state.value.revisions["home"] ?: 1, newId())
            withTimeout(10_000) { preferences.state.first { it.hiddenHome.size == 7 } }
        }
        show()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Отметить питание заполненным") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        click("Отметить питание заполненным")
        compose.onNodeWithText("Еды за день не было?").assertExists()
        assertTrue(repository.state.value.confirmations.isEmpty())
        compose.onNodeWithText("Подтвердить отсутствие еды").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { repository.state.value.completedDayCount == 1L }
        assertTrue(repository.state.value.entries.isEmpty())
    }

    @Test fun oldEntryReplacementPreservesDateMealOrdinalOutsideVisibleWindow() = runBlocking {
        val oldDate = java.time.LocalDate.now().minusDays(1).toString()
        val meal = repository.state.value.meals.first().id
        repository.saveProduct(null, "Исходный тестовый", FoodUnit.GRAM, null, MicroNutrients(150_000_000, 0, 0, 0), baseRevision = null, operation = Operation())
        val product = repository.queryCatalog("Исходный тестовый").single()
        repository.addFood(oldDate, meal, LineInput(product.snapshot, 80_000_000, product.id), Operation())
        val entry = repository.queryHistory(oldDate, oldDate).single()
        val host = UiHost(repository, preferences, TestMediaActions(), scope)
        host.openForm("entry", entry.id)
        withTimeout(10_000) { while (host.form == null) delay(20) }
        val editor = requireNotNull(host.form)
        assertEquals(entry.id, editor.entity.targetEntityId)
        assertEquals(entry.revision, editor.entity.baseRevision)
        val replacement = SnapshotColumns.from(app.diet.domain.NutritionSnapshot("Другой тестовый", FoodUnit.GRAM, nutrientsPerBasis = MicroNutrients(200_000_000, 0, 0, 0).toDomain()))
        editor.replaceEntry(LineInput(replacement, 80_000_000))
        commitUiForm(host, editor, editor.freeze())
        val actual = requireNotNull(repository.loadEntry(entry.id))
        assertEquals("160,0", actual.nutrients().kcal.format(1))
        assertEquals(oldDate, actual.localDate)
        assertEquals(entry.mealId, actual.mealId)
        assertEquals(entry.ordinal, actual.ordinal)
        assertEquals("Другой тестовый", actual.displayName())
    }

    @Test fun historicalEntryEditorExposesDeletionWhenCurrentStateHasNoSuchEntry() {
        val host = runBlocking {
            val date = java.time.LocalDate.now().minusDays(1).toString()
            val meal = repository.state.value.meals.first().id
            repository.addQuickFood(date, meal, "Историческая тестовая", MicroNutrients(100_000_000, 0, 0, 0), Operation())
            val entry = repository.queryHistory(date, date).single()
            assertTrue(repository.state.value.entries.none { it.id == entry.id })
            UiHost(repository, preferences, TestMediaActions(), scope).also { h -> h.openForm("entry", entry.id); withTimeout(10_000) { while (h.form == null) delay(20) } }
        }
        contentSet = true
        compose.setContent { if (contentVisible) CompositionLocalProvider(LocalUi provides host) { DietTheme(VisualPreferences(ready = true)) { LazyColumn { item { Column { EditorScreen() } } } } } }
        compose.onNodeWithText("Удалить запись").performScrollTo().assertExists()
        compose.onNodeWithText("Явно сменить продукт / рецепт").performScrollTo().assertIsEnabled()
    }

    @Test fun occupiedGoalDateNeedsExplicitReplacementAndCurrentRevision() = runBlocking {
        val date = today(); repository.saveGoal(date, MicroNutrients(2_000_000_000, 0, 0, 0), null, repository.state.value.goalTimelineRevision, Operation())
        val host = UiHost(repository, preferences, TestMediaActions(), scope)
        host.openForm("goal"); withTimeout(10_000) { while (host.form == null) delay(20) }
        val form = requireNotNull(host.form); form.fields(mapOf("kcal" to "1800", "protein" to "0", "fat" to "0", "carbs" to "0"))
        assertTrue(goalConfirmationError(form, host.state)!!.contains("замену"))
        refreshGoalPreview(host, form)
        assertEquals("На этой дате уже есть цель", host.confirmation?.title)
        assertEquals("", form.data["goalBaseRevision"])
        requireNotNull(host.confirmation).action()
        assertNull(goalConfirmationError(form, host.state))
        assertEquals("1", form.data["goalBaseRevision"])
        commitUiForm(host, form, form.freeze())
        assertEquals(1, repository.state.value.goals.size)
        assertEquals(1_800_000_000L, repository.state.value.goals.single().nutrients.kcal)
    }

    @Test fun insertingGoalAfterPreviewInvalidatesItWithoutOverwritingChain() = runBlocking {
        val host = UiHost(repository, preferences, TestMediaActions(), scope)
        host.openForm("goal"); withTimeout(10_000) { while (host.form == null) delay(20) }
        val form = requireNotNull(host.form); form.fields(mapOf("kcal" to "1800", "protein" to "0", "fat" to "0", "carbs" to "0"))
        repository.saveGoal(java.time.LocalDate.now().minusDays(1).toString(), MicroNutrients(2_000_000_000, 0, 0, 0), null, repository.state.value.goalTimelineRevision, Operation())
        assertNotNull(goalConfirmationError(form, repository.state.value))
        val failure = runCatching { commitUiForm(host, form, form.freeze()) }.exceptionOrNull()
        assertTrue(failure is DataFailure.Conflict)
        assertEquals(1, repository.state.value.goals.size)
        assertNull(repository.recover(form.data.operationId))
    }

    @Test fun deferredOnboardingGoalBecomesSeparateManualCommandAfterAbsenceProof() = runBlocking {
        val partial = UiFormSession.create(repository, scope, "onboarding-goal", fields = mapOf("date" to today(), "initialToday" to today(), "kcal" to "1800", "protein" to "0", "fat" to "0", "carbs" to "0", "timeline" to repository.state.value.goalTimelineRevision.toString()))
        val oldOperation = partial.data.operationId; val draft = partial.checkpoint(); partial.stopDebounce()
        repository.completeOnboarding(Operation())
        val host = UiHost(repository, preferences, TestMediaActions(), scope); host.resumeId(draft.id)
        withTimeout(10_000) { while (host.form == null || host.form?.editable != true) delay(20) }
        assertNotNull(goalConfirmationError(requireNotNull(host.form), host.state))
        host.convertDeferredGoal()
        withTimeout(10_000) { while (host.form?.data?.get("manualAfterOnboarding") != "true" || host.form?.busy == true) delay(20) }
        val form = requireNotNull(host.form); assertNotEquals(oldOperation, form.data.operationId)
        refreshGoalPreview(host, form); assertNull(goalConfirmationError(form, host.state))
        commitUiForm(host, form, form.freeze())
        assertTrue(repository.state.value.onboarding!!.completed)
        assertEquals(1, repository.state.value.goals.size)
        assertNull(repository.recover(oldOperation))
        assertEquals("deferred-manual-goal", repository.recover(form.data.operationId)!!.first.kind)
    }

    @Test fun onboardingGoalCrossingMidnightRequiresExplicitDateConfirmation() = runBlocking {
        val date = today(); val next = java.time.LocalDate.parse(date).plusDays(1)
        val form = UiFormSession.create(repository, scope, "onboarding-goal", fields = mapOf("date" to date, "initialToday" to date, "goalPreviewDate" to date, "goalReplacementConfirmed" to "true", "timeline" to repository.state.value.goalTimelineRevision.toString()))
        assertTrue(goalConfirmationError(form, repository.state.value, next)!!.contains("дата"))
        form.field("dateConfirmedToday", next.toString())
        assertNull(goalConfirmationError(form, repository.state.value, next))
        assertEquals(date, form.data["date"])
        form.stopDebounce()
    }

    @Test fun repeatedPlanTransferPausesBeforeCreatingSecondFoodSet() = runBlocking {
        repository.completeOnboarding(Operation()); val meal = repository.state.value.meals.first().id
        val snapshot = SnapshotColumns.from(app.diet.domain.NutritionSnapshot("Тестовый снимок", FoodUnit.GRAM, nutrientsPerBasis = MicroNutrients(100_000_000, 0, 0, 0).toDomain()))
        val result = repository.savePlan(null, "Повторяемый план", today(), meal, listOf(LineInput(snapshot, 100_000_000)), null, Operation())
        val plan = requireNotNull(repository.loadPlan(result.entityIds.first()))
        repository.transferPlan(plan.id, plan.revision, today(), meal, Operation())
        val host = UiHost(repository, preferences, TestMediaActions(), scope); host.openForm("transfer", plan.id)
        withTimeout(10_000) { while (host.form == null) delay(20) }; host.saveForm()
        withTimeout(10_000) { while (host.confirmation == null) delay(20) }
        assertEquals("Этот план уже переносили", host.confirmation!!.title)
        assertEquals(1, repository.queryHistory(today(), today()).size)
        host.confirmation!!.action()
        withTimeout(10_000) { while (host.form != null) delay(20) }
        assertEquals(2, repository.queryHistory(today(), today()).size)
    }

    @Test fun nutritionChartSelectsMacrosAndPartialVisibilityNeverChangesFactsOrMeanN() {
        val report = runBlocking {
            val meal = repository.state.value.meals.first().id
            val snapshot = SnapshotColumns.from(app.diet.domain.NutritionSnapshot("Тестовый состав", FoodUnit.GRAM, nutrientsPerBasis = MicroNutrients(100_000_000, 10_000_000, 5_000_000, 20_000_000).toDomain()))
            repository.addFood(today(), meal, LineInput(snapshot, 100_000_000), Operation())
            repository.readReport(java.time.LocalDate.now(), java.time.LocalDate.now())
        }
        val host = UiHost(repository, preferences, TestMediaActions(), scope)
        contentSet = true
        compose.setContent { if (contentVisible) CompositionLocalProvider(LocalUi provides host) { DietTheme(VisualPreferences(ready = true)) { LazyColumn { item { Column { AccessibleChart(report, "food") } } } } } }
        click("Белки")
        compose.onNodeWithText("Частично: 10,0 г", substring = true).performScrollTo().assertExists()
        click("Показывать частичные дни")
        assertEquals("false", host.view["report.partial"])
        assertEquals("protein", host.view["report.component"])
        runBlocking { val after = repository.readReport(java.time.LocalDate.now(), java.time.LocalDate.now()); assertEquals(0, after.nutrition.n); assertEquals(1, after.nutrition.partialDays); assertEquals(1, repository.queryHistory(today(), today()).size) }
    }

    @Test fun photoMealDefaultsOnlyNewPhotosAndDoesNotReassignExistingOnes() = runBlocking {
        val meals = repository.state.value.meals
        val existing = UiPhoto(assetId = newId(), mealId = meals[1].id, caption = "Существующая подпись")
        val form = UiFormSession.create(repository, scope, "moment", fields = mapOf("date" to today(), "photoDefaultMeal" to meals[0].id), photos = listOf(existing))
        val nextAsset = newId(); form.mergePhotos(listOf(existing.input(), PhotoInput(assetId = nextAsset)))
        assertEquals(existing, form.data.photos.first())
        assertEquals(meals[0].id, form.data.photos.last().mealId)
        assertEquals(2, form.data.photos.size)
        form.stopDebounce()
    }

    @Test fun notificationAvailabilityIsPerKindRatherThanAnyBlockedChannel() {
        val state = UiMediaState(notificationEnabled = true, notificationChannels = mapOf("food" to true, "weight" to false, "sleep" to true))
        assertTrue(notificationAvailability(state, "food").startsWith("Android разрешает"))
        assertTrue(notificationAvailability(state, "sleep").startsWith("Android разрешает"))
        assertTrue(notificationAvailability(state, "weight").startsWith("Канал этого вида выключен"))
        assertTrue(notificationAvailability(state.copy(notificationEnabled = false), "food").startsWith("Показ уведомлений приложения запрещён"))
    }

    @Test fun notificationDateSwitchLoadsExistingMeasurementBeforeOpeningEditor() = runBlocking {
        repository.completeOnboarding(Operation()); val previousDate = java.time.LocalDate.now().minusDays(1).toString()
        repository.saveWeight(today(), 7240, null, Operation()); repository.setVisibleDate(previousDate)
        val host = UiHost(repository, preferences, TestMediaActions(), scope)
        assertTrue(host.state.weights.isEmpty())
        host.openNotification(UiOpenAction(newId(), "weight", today()))
        withTimeout(10_000) { while (host.form == null) delay(20) }
        val form = requireNotNull(host.form)
        assertEquals(today(), form.data["date"])
        assertEquals("72,4", form.data["weight"])
        assertEquals(1L, form.entity.baseRevision)
        form.numericField("weight", "72,5"); commitUiForm(host, form, form.freeze())
        assertEquals(7250, repository.state.value.weights.single().centiKg)
        assertEquals(2L, repository.state.value.weights.single().revision)
    }

    @Test fun photoRemovalHoldsSaveAndPersistsIntentBeforeDetachingStagedReference() = runBlocking {
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val queuedScope = CoroutineScope(SupervisorJob() + object : CoroutineDispatcher() { override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) } })
        try {
            val assetId = newId(); val photo = UiPhoto(assetId = assetId, caption = "Синтетический кадр")
            val form = UiFormSession.create(repository, queuedScope, "moment", fields = mapOf("date" to today(), "note" to "Записка сохранится"), photos = listOf(photo))
            val draft = form.checkpoint()
            repository.metadataTransaction { dao -> dao.put(MediaAssetEntity(id = assetId, objectKey = "$assetId.jpg", width = 10, height = 10, byteCount = 100, sha256 = "0".repeat(64), state = "READY", createdAtMillis = 0)); dao.put(DraftMediaEntity(draftId = draft.id, assetId = assetId, role = "staged", ordinal = 0)) }
            val host = UiHost(repository, preferences, TestMediaActions(), queuedScope); host.form = form
            host.removeMomentPhoto(form, assetId)
            assertTrue(form.busy); assertFalse(form.editable)
            host.saveForm() // Must not enqueue a fact commit ahead of removal I/O.
            assertTrue(repository.state.value.moments.isEmpty())
            withTimeout(10_000) { while (form.busy) { queue.poll()?.run(); delay(5) } }
            assertFalse(form.uncertain); assertTrue(form.data.photos.isEmpty())
            val saved = DraftJson.decodeFromString<UiFormData>(repository.freshDraft(draft.id)!!.rawPayload)
            assertEquals("Записка сохранится", saved["note"]); assertTrue(saved.photos.isEmpty()); assertTrue(assetId in saved.removedAssets)
            assertTrue(repository.media.draftAssets(draft.id).isEmpty()); assertTrue(repository.state.value.moments.isEmpty())
            form.stopDebounce()
        } finally { queuedScope.cancel() }
    }

    @Test fun restoredPhotoRemovalIntentCleansSurvivingRefWithoutReaddingStagedPhoto() = runBlocking {
        val assetId = newId()
        val form = UiFormSession.create(repository, scope, "moment", fields = mapOf("date" to today(), "note" to "Осталась заметка"), photos = listOf(UiPhoto(assetId = assetId)))
        form.recordPhotoRemovalIntent(assetId); val draft = form.checkpoint(); form.stopDebounce()
        repository.metadataTransaction { dao -> dao.put(MediaAssetEntity(id = assetId, objectKey = "$assetId.jpg", width = 10, height = 10, byteCount = 100, sha256 = "0".repeat(64), state = "READY", createdAtMillis = 0)); dao.put(DraftMediaEntity(draftId = draft.id, assetId = assetId, role = "staged", ordinal = 0)) }
        // Simulate process death after the accepted raw intent but before detach.
        val host = UiHost(repository, preferences, TestMediaActions(), scope); host.resumeId(draft.id)
        withTimeout(10_000) { while (host.form == null || host.form?.editable != true) delay(20) }
        assertTrue(repository.media.draftAssets(draft.id).isEmpty())
        assertTrue(host.form!!.data.photos.isEmpty()); assertEquals("Осталась заметка", host.form!!.data["note"])
        host.form!!.stopDebounce()
    }

    @Test fun deletingViewedPhotoReturnsToFeedWithoutSubstitutingIdenticalRemainingPhoto() = runBlocking {
        repository.completeOnboarding(Operation())
        val moment = MomentEntity(localDate = today()); val removedId = newId(); val keptId = newId(); val assetA = newId(); val assetB = newId()
        // Metadata navigation fixture; byte decoding/integrity are separately tested by PhotoEngine.
        repository.metadataTransaction { dao ->
            dao.put(moment)
            listOf(assetA, assetB).forEach { id -> dao.put(MediaAssetEntity(id = id, objectKey = "$id.jpg", width = 10, height = 10, byteCount = 100, sha256 = "0".repeat(64), state = "READY", integrity = "VERIFIED", createdAtMillis = 0)) }
            dao.put(PhotoEntity(removedId, moment.id, assetA, "Удаляемый кадр", ordinal = 0)); dao.put(PhotoEntity(keptId, moment.id, assetB, "Оставшийся кадр", ordinal = 1))
        }
        val host = UiHost(repository, preferences, TestMediaActions(), scope)
        host.push(UiRoute("photo-feed")); host.push(UiRoute("photo-detail", removedId)); host.openForm("moment")
        withTimeout(10_000) { while (host.form == null || host.form?.editable != true) delay(20) }
        host.removeMomentPhoto(host.form!!, assetA)
        withTimeout(10_000) { while (host.form?.busy == true) delay(20) }
        host.saveForm(); withTimeout(10_000) { while (host.form != null) delay(20) }
        assertEquals("photo-feed", host.route.name)
        assertEquals("Фото удалено. Остальные моменты сохранены.", host.message)
        assertNull(repository.loadPhoto(removedId)); assertEquals(keptId, repository.queryPhotos(today(), today()).single().id)
        assertEquals("Оставшийся кадр", repository.loadPhoto(keptId)!!.caption)
    }

    private class TestMediaActions : UiMediaActions {
        override val state = MutableStateFlow(UiMediaState())
        override fun pickPhotos(draftId: String, date: String, freeSlots: Int) = error("Photo picker is outside this synthetic UI test")
        override fun cancelImport(draftId: String) = Unit
        override fun clearError() = Unit
        override fun requestNotifications() = Unit
        override fun openNotificationSettings() = Unit
        override fun consumeOpenAction(id: String) = Unit
        @Composable override fun Thumbnail(assetId: String, modifier: Modifier, description: String) { Text(description, modifier) }
    }
}
