package app.diet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.diet.data.*
import app.diet.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable internal fun CatalogScreen(mode: String) {
    val h = LocalUi.current
    var query by rememberSaveable { mutableStateOf("") }; var category by rememberSaveable { mutableStateOf("") }; var filter by rememberSaveable { mutableStateOf("all") }; var page by rememberSaveable { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<CatalogItemEntity>>(emptyList()) }; var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, category, filter, page, h.state.dataRevision) {
        loading = true; error = null; delay(150)
        try { items = h.repository.queryCatalog(query, category.ifEmpty { null }, page * 40L, 40, favoritesOnly = filter == "favorite", recentOnly = filter == "recent", kind = if (mode == "line-picker" && h.form?.entity?.kind == "recipe") "product" else if (filter == "recipe") "recipe" else null) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e, "Не удалось открыть библиотеку") } finally { loading = false }
    }
    val picker = mode in setOf("line-picker", "replace-picker")
    ScreenHeading(if (mode == "replace-picker") "Выбрать новый продукт" else if (mode == "line-picker") "Добавить в состав" else if (mode == "add-food") "Что было вкусного?" else "Моя библиотека", if (mode == "replace-picker") "После выбора проверьте новый снимок и количество. Остальные записи не изменятся." else "Свои продукты и рецепты. Сохраняйте любимое и возвращайтесь к нему.")
    if (!picker && filter == "recipe") DecorativeFoodStrip()
    OutlinedTextField(query, { if (it.codePointCount(0, it.length) <= 100) { query = it; page = 0 } }, Modifier.fillMaxWidth(), label = { Text("Найти продукт или рецепт") }, singleLine = true, shape = MaterialTheme.shapes.small)
    Choices(listOf("all" to "Всё", "favorite" to "Избранное", "recent" to "Недавние", "recipe" to "Рецепты"), filter) { filter = it; page = 0 }
    if (h.state.categories.isNotEmpty()) Choices(listOf("" to "Все категории") + h.state.categories.map { it.id to it.name }, category) { category = it; page = 0 }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    ErrorNotice(error)
    if (!loading && error == null && items.isEmpty()) EmptyState("Пока ничего не найдено", "Можно добавить свой продукт по этикетке или составить рецепт.")
    items.forEach { item -> DietCard(item.name, (if (item.kind == "recipe") "Рецепт · " else "") + "На ${decimal(item.snapshot.basisMicros)} ${unitLabel(item.unit, item.portionName)}") {
        NutritionText(item.snapshot.toDomain().nutrientsPerBasis, true)
        when (mode) {
            "line-picker", "replace-picker" -> Action(if (mode == "replace-picker") "Использовать новый снимок" else "Добавить в состав", secondary = true) { val input = LineInput(item.snapshot, item.snapshot.basisMicros, item.id); if (mode == "replace-picker") h.form?.replaceEntry(input) else h.form?.addLine(input); h.pop() }
            else -> { Action("Выбрать порцию", secondary = true) { h.openForm("portion", item.id, h.route.id ?: h.defaultMeal) }
                Row { TextButton(onClick = { h.command("favorite", item.id, item.revision, mapOf("favorite" to (!item.favorite).toString())) }) { Text(if (item.favorite) "★ В избранном" else "☆ В избранное") }; TextButton(onClick = { h.openForm(item.kind, item.id) }) { Text("Изменить") } }
                TextButton(onClick = { h.openForm("quick-portions", item.id) }) { Text("Быстрые порции") }
                DeleteLink("Удалить из библиотеки") { h.confirm("Удалить ${item.name}?", "Записи дневника, рецептов, шаблонов и плана сохранят свои исторические снимки.", "Удалить") { h.command("delete-catalog", item.id, item.revision) } }
            }
        }
    } }
    PageControls(page, items.size == 40, loading) { page = it }
    if (!picker) { Action("＋ Свой продукт") { h.openForm("product") }; Action("＋ Свой рецепт", secondary = true) { h.openForm("recipe") }; Action("Быстрая запись КБЖУ", secondary = true) { h.openForm("quick", meal = h.route.id ?: h.defaultMeal) }; TextButton(onClick = { h.navigate("categories") }) { Text("Управлять категориями") } }
}

@Composable internal fun RoutinesScreen(mode: String) {
    val h = LocalUi.current; val plan = mode == "plans"
    var date by rememberSaveable { mutableStateOf(h.date) }; var week by rememberSaveable { mutableStateOf(false) }; var page by rememberSaveable { mutableIntStateOf(0) }
    var plans by remember { mutableStateOf<List<PlanEntity>>(emptyList()) }; var templates by remember { mutableStateOf<List<TemplateEntity>>(emptyList()) }; var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mode, date, week, page, h.state.dataRevision) {
        error = null; loading = true
        try { if (plan) { val start = DomainDates.validatePlanDate(DomainDates.parse(date), LocalDate.now()); val end = if (week) minOf(start.plusDays(6), DomainDates.latestPlanDate(LocalDate.now())) else start; plans = h.repository.queryPlans(start.toString(), end.toString(), page * 40L, 40) } else templates = h.repository.queryTemplates(page * 40L, 40) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e, "Не удалось прочитать записи") } finally { loading = false }
    }
    ScreenHeading(if (plan) "План питания" else "Шаблоны приёмов", if (plan) "Немного ясности на завтра. План и съеденная еда хранятся отдельно." else "Любимые сочетания — под рукой. В дневник они попадают после подтверждения.")
    Choices(listOf("plans" to "План", "templates" to "Шаблоны"), mode) { h.root(it) }
    if (!plan) DecorativeFoodStrip()
    if (plan) { OutlinedTextField(date, { date = it; page = 0 }, Modifier.fillMaxWidth(), label = { Text("Дата начала ГГГГ-ММ-ДД") }, singleLine = true); ToggleRow("Показать неделю", week) { week = it; page = 0 }; Action("＋ Запланировать приём") { h.openForm("plan", extra = mapOf("date" to date)) } }
    else Action("＋ Создать шаблон") { h.openForm("template") }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()); ErrorNotice(error)
    if (plan) {
        if (!loading && error == null && plans.isEmpty()) EmptyState("Здесь есть место для планов", "Составьте приём из продуктов или сохранённого шаблона. Автоматически еда не добавится.")
        plans.forEach { p -> DietCard(p.name, "${dateLabel(p.planDate)} · ${h.state.meals.find { it.id == p.mealId }?.name ?: "Приём"}") {
            TextButton(onClick = { h.openForm("plan", p.id) }) { Text("Открыть состав / изменить") }
            Action("Проверить и перенести в факт", secondary = true) { h.openForm("transfer", p.id) }
            TransferHistory(p.id)
            DeleteLink("Удалить план") { h.confirm("Удалить план?", "Ранее перенесённая еда и происхождение переносов останутся в дневнике.", "Удалить") { h.command("delete-plan", p.id, p.revision) } }
        } }
        PageControls(page, plans.size == 40, loading) { page = it }
    } else {
        if (!loading && error == null && templates.isEmpty()) EmptyState("Сохраните свой завтрак", "Создайте сочетание здесь или сохраните наполненный приём с главной.")
        templates.forEach { t -> DietCard(t.name) { Action("Добавить в дневник", secondary = true) { h.openForm("apply-template", t.id) }; TextButton(onClick = { h.openForm("template", t.id) }) { Text("Открыть состав / изменить") }; DeleteLink("Удалить шаблон") { h.confirm("Удалить шаблон?", "Фактическая еда и планы останутся прежними.", "Удалить") { h.command("delete-template", t.id, t.revision) } } } }
        PageControls(page, templates.size == 40, loading) { page = it }
    }
}

@Composable private fun TransferHistory(planId: String) { val h = LocalUi.current; var expanded by remember { mutableStateOf(false) }; var transfers by remember { mutableStateOf<List<TransferEntity>>(emptyList()) }; var page by remember { mutableIntStateOf(0) }; var error by remember { mutableStateOf<String?>(null) }; LaunchedEffect(planId, expanded, page) { if (expanded) try { transfers = h.repository.queryTransfers(planId, page * 40L, 40) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e) } }; TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Скрыть переносы ↑" else "История переносов ↓") }; if (expanded) { ErrorNotice(error); if (transfers.isEmpty()) Muted("Пока не переносили в факт"); transfers.forEach { Text("${dateLabel(it.localDate)} · ${h.state.meals.find { m -> m.id == it.mealId }?.name ?: "Приём"}", style = MaterialTheme.typography.bodyMedium) }; PageControls(page, transfers.size == 40, false) { page = it } } }

@Composable internal fun TemplatePickerScreen() {
    val h = LocalUi.current; val session = h.form ?: return
    var page by rememberSaveable { mutableIntStateOf(0) }; var items by remember { mutableStateOf<List<TemplateEntity>>(emptyList()) }; var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(page, h.state.dataRevision) { loading = true; error = null; try { items = h.repository.queryTemplates(page * 40L, 40) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e, "Не удалось открыть шаблоны") } finally { loading = false } }
    ScreenHeading("Добавить сочетание", "Выберите самостоятельные снимки сохранённого шаблона. Уже введённый состав останется.")
    ErrorNotice(error); if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    items.forEach { template -> DietCard(template.name) { Action("Добавить этот состав", secondary = true, enabled = session.editable && !loading) { session.busy = true; h.launch { try { val lines = h.repository.templateLines(template.id).map { LineInput(it.snapshot, it.quantityMicros, it.sourceItemId) }; require(session.data.lines.size + lines.size <= 500) { "В составе может быть не больше 500 строк. Ничего не добавлено." }; session.busy = false; session.addLines(lines); h.pop() } finally { session.busy = false } } } } }
    if (!loading && items.isEmpty()) EmptyState("Шаблонов на этой странице нет", "Сохраните сочетание в разделе шаблонов или с главной.")
    PageControls(page, items.size == 40, loading || session.busy) { page = it }
}
@Composable internal fun PageControls(page: Int, hasNext: Boolean, loading: Boolean, onPage: (Int) -> Unit) { if (page > 0 || hasNext) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { onPage(page - 1) }, enabled = page > 0 && !loading) { Text("← Ранее") }; Text("${page + 1}", Modifier.padding(16.dp)); TextButton(onClick = { onPage(page + 1) }, enabled = hasNext && !loading) { Text("Далее →") } } }
