package app.diet.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.diet.data.DietRepository
import app.diet.data.VisualPreferencesRepository
import kotlinx.coroutines.flow.collectLatest

@Composable
fun DietApp(repository: DietRepository, visualRepository: VisualPreferencesRepository, mediaActions: UiMediaActions) {
    val scope = rememberCoroutineScope()
    val h = remember(repository, visualRepository, mediaActions) { UiHost(repository, visualRepository, mediaActions, scope) }
    ReportDrawnWhen { h.state.ready && h.preferences.ready }
    LaunchedEffect(h) { repository.state.collectLatest { h.state = it } }
    LaunchedEffect(h) { visualRepository.state.collectLatest { h.preferences = it; if (it.ready && !h.viewLoaded) { h.view = it.viewContext; h.viewLoaded = true; it.viewContext["selectedDate"]?.let { saved -> runCatching { app.diet.domain.DomainDates.validateFactDate(app.diet.domain.DomainDates.parse(saved), java.time.LocalDate.now()) }.getOrNull()?.let { valid -> h.date = valid.toString() } } } } }
    LaunchedEffect(h) { mediaActions.state.collectLatest { m -> h.mediaState = m; val form = h.form; if (form != null && form.entity.id == m.draftId && !m.busy) form.mergePhotos(m.photos) } }
    LaunchedEffect(h.mediaState.openAction, h.state.ready, h.openingForm) { if (h.state.ready) h.mediaState.openAction?.let(h::openNotification) }
    LaunchedEffect(h) { if (!repository.state.value.ready) h.launch { repository.initialize() } }
    LaunchedEffect(h.message) { if (h.message != null) { kotlinx.coroutines.delay(5_000); h.message = null } }
    LaunchedEffect(h.date, h.state.ready) { if (h.state.ready) { try { repository.setVisibleDate(h.date); if (h.viewLoaded) h.context("selectedDate", h.date) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { h.error = app.diet.messages.userMessage(e, "Не удалось открыть выбранную дату") } } }
    BackHandler(enabled = h.route.name != "home" || h.form != null || h.canGoBack) { h.back() }
    CompositionLocalProvider(LocalUi provides h) {
        DietTheme(h.preferences, h.preferencePreview) {
            Surface(color = MaterialTheme.colorScheme.background) {
                if (!h.state.ready || !h.preferences.ready) {
                    Column(Modifier.fillMaxSize().padding(28.dp), Arrangement.Center, Alignment.CenterHorizontally) { Text("Diet", style = MaterialTheme.typography.displaySmall); Spacer(Modifier.height(24.dp)); CircularProgressIndicator(); ErrorNotice(h.error ?: h.state.error); PreferenceReadStatus(); if (h.error != null || h.state.error != null) Action("Повторить чтение дневника", onClick = { h.launch { repository.initialize() } }) }
                } else {
                    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
                        if (h.state.onboarding?.completed == true && h.route.name !in listOf("editor", "line-picker", "replace-picker", "template-picker", "appearance", "home-layout", "photo-detail")) DietNavigation()
                    }) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                            key(h.route) {
                                val list = rememberLazyListState()
                                LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxHeight().imePadding(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                            if (h.route.name != "home" || h.canGoBack) TextButton(onClick = { h.back() }, contentPadding = PaddingValues(0.dp), enabled = h.form?.busy != true) { Text("← Назад") }
                                            ErrorNotice(h.state.error ?: h.error)
                                            PreferenceReadStatus()
                                            if (h.state.error != null) Action("Повторить чтение сохранённых данных", secondary = true) { h.launch { repository.initialize() } }
                                            h.message?.let { message -> Surface(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) { Row(Modifier.padding(12.dp)) { Text(message, Modifier.weight(1f)); TextButton(onClick = { h.message = null }) { Text("Понятно") } } } }
                                            when (h.route.name) {
                                                "home" -> if (h.state.onboarding?.completed != true) { if (h.state.onboarding?.step == "goals") OnboardingGoals() else WelcomeScreen() } else HomeScreen()
                                                "onboarding-goals" -> OnboardingGoals()
                                                "editor" -> EditorScreen()
                                                "catalog", "add-food", "line-picker", "replace-picker" -> CatalogScreen(h.route.name)
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
                                            Spacer(Modifier.height(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                h.confirmation?.let { c -> AlertDialog(onDismissRequest = { h.confirmation = null }, title = { Text(c.title) }, text = { Text(c.message) }, confirmButton = { TextButton(onClick = { h.confirmation = null; c.action() }) { Text(c.confirmLabel) } }, dismissButton = { TextButton(onClick = { h.confirmation = null }) { Text("Отмена") } }) }
                if (h.pendingNavigation != null) AlertDialog(onDismissRequest = { h.cancelNavigation() }, title = { Text("Выйти из редактора?") }, text = { Text("Можно оставить постоянный черновик и вернуться к нему позже. Запись в дневник пока не добавляется.") }, confirmButton = { TextButton(onClick = { h.keepDraftAndLeave() }) { Text("Оставить черновик") } }, dismissButton = { Column { TextButton(onClick = { h.cancelNavigation() }) { Text("Продолжить") }; TextButton(onClick = { h.discardAndLeave() }) { Text("Удалить черновик и выйти") } } })
            }
        }
    }
}

@Composable internal fun ScreenHeading(title: String, description: String? = null) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() }); description?.let { Muted(it) } } }
@Composable private fun DietNavigation() {
    val h = LocalUi.current
    val density = LocalDensity.current
    NavigationBar(modifier = Modifier.heightIn(min = (80 + (density.fontScale.coerceAtLeast(1f) - 1) * 40).dp), containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        listOf("home" to ("◒" to "Дневник"), "plans" to ("▦" to "План"), "reports" to ("↗" to "Прогресс"), "settings" to ("···" to "Ещё")).forEach { (route, label) -> NavigationBarItem(h.route.name == route, { h.root(route) }, icon = { CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) { Text(label.first, modifier = Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleLarge) } }, label = { Text(label.second) }) }
    }
}
