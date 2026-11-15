package app.diet.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import app.diet.data.DietRepository
import app.diet.data.VisualPreferencesRepository
import kotlinx.coroutines.flow.collectLatest

@Composable
fun DietApp(repository: DietRepository, visualRepository: VisualPreferencesRepository, mediaActions: UiMediaActions) {
    val scope = rememberCoroutineScope()
    val screenStates = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val retainedKeys = rememberSaveable(saver = listSaver<java.util.LinkedHashSet<String>, String>(save = { it.toList() }, restore = { java.util.LinkedHashSet(it) })) { java.util.LinkedHashSet<String>() }
    val h = remember(repository, visualRepository, mediaActions) { UiHost(repository, visualRepository, mediaActions, scope) }
    ReportDrawnWhen { h.state.ready && h.preferences.ready }
    LaunchedEffect(h) { repository.state.collectLatest { h.state = it } }
    LaunchedEffect(h) { visualRepository.state.collectLatest { h.preferences = it; if (it.ready && !h.viewLoaded) { h.view = it.viewContext; h.viewLoaded = true; it.viewContext["selectedDate"]?.let { saved -> runCatching { app.diet.domain.DomainDates.validateFactDate(app.diet.domain.DomainDates.parse(saved), java.time.LocalDate.now()) }.getOrNull()?.let { valid -> h.date = valid.toString() } } } } }
    LaunchedEffect(h) { mediaActions.state.collectLatest { m -> h.mediaState = m; val form = h.form; if (form != null && form.entity.id == m.draftId && !m.busy) form.mergePhotos(m.photos) } }
    LaunchedEffect(h.mediaState.openAction, h.state.ready, h.openingForm) { if (h.state.ready) h.mediaState.openAction?.let(h::openNotification) }
    LaunchedEffect(h) { if (!repository.state.value.ready) h.launch { repository.initialize() } }
    LaunchedEffect(h.message) { h.message?.let { message -> snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Short); if (h.message == message) h.message = null } }
    LaunchedEffect(h.error) { h.error?.let { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long) } }
    LaunchedEffect(h.date, h.state.ready) { if (h.state.ready) { try { repository.setVisibleDate(h.date); if (h.viewLoaded) h.context("selectedDate", h.date) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { h.error = app.diet.messages.userMessage(e, "Не удалось открыть выбранную дату") } } }
    BackHandler(enabled = h.route.name != "home" || h.form != null || h.canGoBack) { h.back() }
    CompositionLocalProvider(LocalUi provides h) {
        DietTheme(h.preferences, h.preferencePreview) {
            val density = LocalDensity.current
            val keyboardHeight = WindowInsets.ime.getBottom(density)
            val compactKeyboard = keyboardHeight > 0 && LocalWindowInfo.current.containerSize.height - keyboardHeight - WindowInsets.statusBars.getTop(density) < with(density) { 160.dp.roundToPx() }
            Surface(color = MaterialTheme.colorScheme.background) {
                if (!h.state.ready || !h.preferences.ready) {
                    Column(Modifier.fillMaxSize().padding(28.dp), Arrangement.Center, Alignment.CenterHorizontally) { Text("Diet", style = MaterialTheme.typography.displaySmall); Spacer(Modifier.height(24.dp)); CircularProgressIndicator(); ErrorNotice(h.error ?: h.state.error); PreferenceReadStatus(); if (h.error != null || h.state.error != null) Action("Повторить чтение дневника", onClick = { h.launch { repository.initialize() } }) }
                } else {
                    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { Column { PendingDeletionNotice(h); SnackbarHost(snackbar) } }, bottomBar = {
                        // With very little space above the IME, let the focused field use it.
                        // The fixed action returns as soon as the user closes the keyboard.
                        if (h.route.name == "editor" && !compactKeyboard) Box(Modifier.navigationBarsPadding().imePadding()) { EditorActions() }
                        if (h.state.onboarding?.completed == true && h.route.name !in listOf("editor", "batch-picker", "line-picker", "replace-picker", "template-picker", "appearance", "home-layout", "photo-detail")) DietNavigation()
                    }) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (h.route.name in setOf("catalog", "add-food", "line-picker", "replace-picker", "batch-picker")) Box(Modifier.widthIn(max = 760.dp)) { CatalogTopBar(h.route.name) }
                            val screenKey = h.route.name + ":" + (if (h.route.name == "editor") h.form?.entity?.id else h.route.id).orEmpty()
                            DisposableEffect(screenKey) {
                                retainedKeys.remove(screenKey); retainedKeys.add(screenKey)
                                while (retainedKeys.size > 24) {
                                    val old = retainedKeys.firstOrNull { it !in setOf("home:", "plans:", "reports:", "settings:") && it != screenKey } ?: break
                                    retainedKeys.remove(old); screenStates.removeState(old)
                                }
                                onDispose { }
                            }
                            screenStates.SaveableStateProvider(screenKey) {
                                RetainedViewport(screenKey) {
                                            if (h.route.name !in setOf("home", "plans", "templates", "reports", "graph-weight", "graph-sleep", "graph-food", "settings")) TextButton(onClick = { h.back() }, contentPadding = PaddingValues(0.dp), enabled = h.form?.busy != true) { Text("← Назад") }
                                            ErrorNotice(h.state.error ?: h.error)
                                            PreferenceReadStatus()
                                            if (h.state.error != null) Action("Повторить чтение сохранённых данных", secondary = true) { h.launch { repository.initialize() } }
                                            when (h.route.name) {
                                                "home" -> if (h.state.onboarding?.completed != true) { if (h.state.onboarding?.step == "goals") OnboardingGoals() else WelcomeScreen() } else HomeScreen()
                                                "onboarding-goals" -> OnboardingGoals()
                                                "guide" -> GuidanceScreen()
                                                "editor" -> EditorScreen()
                                                "catalog", "add-food", "line-picker", "replace-picker", "batch-picker" -> CatalogScreen(h.route.name)
                                                "template-picker" -> TemplatePickerScreen()
                                                "calendar" -> CalendarScreen()
                                                "profile" -> ProfileScreen()
                                                "goals" -> GoalsScreen()
                                                "templates", "plans" -> RoutinesScreen(h.route.name)
                                                "reports", "graph-weight", "graph-sleep", "graph-food" -> ReportsScreen()
                                                "search", "photo-feed", "photo-detail" -> HistoryScreen()
                                                "appearance", "home-layout", "settings", "game", "meals", "categories", "reminders", "sources", "preference-resume", "drafts" -> SettingsScreen()
                                                else -> EmptyState("Этот экран недоступен", "Вернитесь в дневник.", "В дневник") { h.root("home") }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
                h.confirmation?.let { c ->
                    AdaptiveDialog(c.title, c.message, { h.confirmation = null }) {
                        Action(c.confirmLabel) { h.confirmation = null; c.action() }
                        Action("Отмена", secondary = true) { h.confirmation = null }
                    }
                }
                if (h.pendingNavigation != null) EditorExitDialog(h)

            }
        }
    }
}

@Composable internal fun ScreenHeading(title: String, description: String? = null) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() }); description?.let { Muted(it) } } }
@Composable private fun DietNavigation() {
    val h = LocalUi.current
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                listOf("home" to (Icons.AutoMirrored.Outlined.MenuBook to "Дневник"), "plans" to (Icons.Outlined.CalendarMonth to "План"),
                    "reports" to (Icons.Outlined.BarChart to "Прогресс"), "settings" to (Icons.Outlined.MoreHoriz to "Ещё")).forEach { (route, item) ->
                    val selected = h.activeRoot == route
                    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(Modifier.weight(1f).heightIn(min = 56.dp).selectable(selected, onClick = { h.root(route) }, role = Role.Tab).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(item.first, contentDescription = null, tint = color, modifier = Modifier.size(22.dp).clearAndSetSemantics { })
                        Text(item.second, color = color, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
