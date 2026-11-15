package app.diet.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.data.*
import app.diet.integration.IntegrationDatabase
import kotlinx.coroutines.delay
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
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class NavigationUxTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: IntegrationDatabase
    private lateinit var preferences: VisualPreferencesRepository
    private lateinit var preferenceFile: File
    private lateinit var host: UiHost
    private var visible by mutableStateOf(true)
    private var shown = false

    @Before fun setUp(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fixture = IntegrationDatabase(context)
        fixture.initialize()
        fixture.repository.completeOnboarding(Operation())
        preferenceFile = File(context.noBackupFilesDir, "diet/prefs/navigation-${newId()}.preferences_pb")
        check(preferenceFile.parentFile!!.isDirectory || preferenceFile.parentFile!!.mkdirs())
        preferences = VisualPreferencesRepository(PreferenceDataStoreFactory.create(scope = fixture.scope, produceFile = { preferenceFile }), fixture.scope)
        withTimeout(10_000) { preferences.state.first { it.ready } }
        host = UiHost(fixture.repository, preferences, SilentMedia(), fixture.scope)
    }

    @After fun tearDown(): Unit = runBlocking {
        if (shown) { compose.runOnIdle { visible = false }; compose.waitForIdle() }
        if (::fixture.isInitialized) fixture.close()
        if (::preferenceFile.isInitialized) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            check(preferenceFile.canonicalFile.toPath().startsWith(context.noBackupFilesDir.canonicalFile.toPath()))
            listOf(preferenceFile, File(preferenceFile.path + ".tmp")).forEach { it.delete() }
        }
    }

    private fun show(content: @Composable () -> Unit) {
        shown = true
        compose.setContent {
            if (visible) CompositionLocalProvider(LocalUi provides host, LocalReducedMotion provides true) {
                MaterialTheme { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    @Composable private fun LongPage(key: String, loading: Boolean) {
        ScreenLoad(loading)
        if (loading) Text("Загрузка $key", Modifier.testTag("loading-$key"))
        else repeat(80) { index -> Text("$key — строка $index", Modifier.fillMaxWidth().height(56.dp).testTag("$key-row-$index")) }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun assertPosition(tag: String, expected: Float) {
        compose.waitUntil(10_000) {
            val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            nodes.size == 1 && abs(nodes.single().boundsInRoot.top - expected) <= 3f
        }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test fun fourRootViewportsReturnToTheirOwnOffsetsAfterAsyncDetail() {
        var route by mutableStateOf("home")
        show {
            val states = rememberSaveableStateHolder()
            states.SaveableStateProvider(route) {
                RetainedViewport(route) {
                    var loading by remember { mutableStateOf(true) }
                    LaunchedEffect(route) { delay(80); loading = false }
                    LongPage(route, loading)
                }
            }
        }
        val points = linkedMapOf("home" to 22, "plans" to 31, "reports" to 43, "settings" to 55)
        val positions = mutableMapOf<String, Float>()
        points.forEach { (key, row) ->
            compose.runOnIdle { route = key }
            val tag = "$key-row-$row"
            awaitTag(tag)
            compose.onNodeWithTag(tag).performScrollTo()
            compose.waitForIdle()
            positions[key] = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
            compose.runOnIdle { route = "$key-detail" }
            awaitTag("$key-detail-row-0")
            compose.runOnIdle { route = key }
            awaitTag(tag)
            assertPosition(tag, positions.getValue(key))
        }
        points.forEach { (key, row) ->
            compose.runOnIdle { route = key }
            val tag = "$key-row-$row"
            awaitTag(tag)
            assertPosition(tag, positions.getValue(key))
        }
    }

    @Test fun sameScreenReloadDoesNotCommitClampedLoadingOffset() {
        var loading by mutableStateOf(false)
        show { RetainedViewport("reload") { LongPage("reload", loading) } }
        val tag = "reload-row-49"
        awaitTag(tag)
        compose.onNodeWithTag(tag).performScrollTo()
        compose.waitForIdle()
        val before = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { loading = true }
        awaitTag("loading-reload")
        compose.runOnIdle { loading = false }
        awaitTag(tag)
        assertPosition(tag, before)
    }

    @Test fun returningViewportStillRevealsWhenARealDragInterruptsOffsetRestoration() {
        var route by mutableStateOf("home")
        var loading by mutableStateOf(false)
        show {
            val states = rememberSaveableStateHolder()
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                states.SaveableStateProvider(route) {
                    if (route == "home") RetainedViewport("gesture-home") {
                        // A real returning screen may already have scrollable content while
                        // another reader is still loading. Keep that case deterministic here.
                        ScreenLoad(loading)
                        repeat(80) { index ->
                            Text("Строка $index", Modifier.fillMaxWidth().height(56.dp)
                                .background(Color.Magenta).testTag("gesture-row-$index"))
                        }
                    } else Text("Открытая деталь", Modifier.testTag("gesture-detail"))
                }
            }
        }
        awaitTag("gesture-row-45")
        compose.onNodeWithTag("gesture-row-45").performScrollTo()
        compose.waitForIdle()
        val viewportTag = "viewport-gesture-home"
        fun scrollOffset() = compose.onNodeWithTag(viewportTag).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("The first visit must save a nonzero offset", scrollOffset() > 0f)
        compose.runOnIdle { route = "detail" }
        awaitTag("gesture-detail")

        var fingerDown = false
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { loading = true; route = "home" }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            val viewport = compose.onNodeWithTag(viewportTag)
            val beforeDrag = scrollOffset()
            // Do not release the pointer: UserInput owns the scroll mutation while
            // loading finishes and the lower-priority saved-position scroll starts.
            fingerDown = true
            viewport.performTouchInput { down(center); moveBy(Offset(0f, -180f), delayMillis = 32) }
            compose.mainClock.advanceTimeByFrame()
            assertTrue("The actual gesture must move the returning list", scrollOffset() > beforeDrag)
            compose.runOnIdle { loading = false }
            compose.mainClock.advanceTimeBy(64)
            viewport.performTouchInput { up() }
            fingerDown = false
            compose.mainClock.advanceTimeBy(1_000)
        } finally {
            if (fingerDown) runCatching { compose.onNodeWithTag(viewportTag).performTouchInput { up() } }
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        // assertIsDisplayed accepts a node in an alpha=0 layer. Inspect pixels from
        // the actual root instead, so a forever-transparent viewport cannot pass.
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        var markerPixels = 0
        var sampledPixels = 0
        for (y in 0 until pixels.height step 12) for (x in 0 until pixels.width step 12) {
            val color = pixels[x, y]
            if (color.red > .85f && color.blue > .85f && color.green < .15f) markerPixels++
            sampledPixels++
        }
        assertTrue("The restored viewport must render its content, not stay transparent", markerPixels > sampledPixels / 5)
    }

    @Test fun sleepEditorReturnsToSourceAfterKeepingNewDraft() {
        shown = true
        compose.setContent { if (visible) DietApp(fixture.repository, preferences, SilentMedia()) }
        val button = "Записать сон →"
        compose.waitUntil(10_000) { compose.onAllNodesWithText(button).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(button).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Часы").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Часы").performScrollTo().performTextReplacement("7")
        compose.onNodeWithText("← Назад").performScrollTo().performClick()
        compose.onNodeWithText("Оставить черновик").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(button).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText(button).assertIsDisplayed()
        runBlocking {
            val draft = requireNotNull(fixture.repository.findOpenDraft("sleep", selectedLocalDate = host.date))
            val raw = requireNotNull(fixture.repository.freshDraft(draft.id))
            assertEquals("7", UiFormSession.restore(fixture.repository, fixture.scope, raw).data["hours"])
        }
    }

    @Test fun largeTextExitDialogKeepsAllActionsReachableAndInsideWindow() {
        var selected by mutableStateOf("")
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AdaptiveDialog("Выйти из редактора?", "Сохраните черновик, чтобы продолжить позже. В дневник он пока не добавляется.", {}) {
                    Action("Продолжить редактирование") { selected = "continue" }
                    Action("Оставить черновик", secondary = true) { selected = "keep" }
                    TextButton(onClick = { selected = "delete" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Удалить черновик и выйти") }
                }
            }
        }
        listOf("Продолжить редактирование" to "continue", "Оставить черновик" to "keep", "Удалить черновик и выйти" to "delete").forEach { (label, value) ->
            val action = compose.onNodeWithText(label)
            action.performScrollTo().assertIsDisplayed()
            val bounds = action.fetchSemanticsNode().boundsInRoot
            val window = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
            assertTrue("Action outside dialog window: $label", bounds.top >= window.top && bounds.bottom <= window.bottom && bounds.left >= window.left && bounds.right <= window.right)
            action.performClick()
            compose.runOnIdle { assertEquals(value, selected) }
        }
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
