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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import androidx.compose.ui.Alignment

private fun UiHost.catalogContext(mode: String): Map<String, String> =
    runCatching { DraftJson.decodeFromString<Map<String, String>>(view["catalog.$mode"] ?: "{}") }.getOrDefault(emptyMap())

private fun UiHost.catalogField(mode: String, key: String, value: String) {
    val next = catalogContext(mode) + (key to value) + (if (key == "page") emptyMap() else mapOf("page" to "0"))
    resetScroll()
    context("catalog.$mode", DraftJson.encodeToString(next))
}

/** Fixed outside the scrolling content so creating or finding food never requires a long scroll. */
@Composable internal fun CatalogTopBar(mode: String) {
    val h = LocalUi.current
    val values = h.catalogContext(mode)
    val picker = mode in setOf("line-picker", "replace-picker", "batch-picker")
    var createMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(values["query"].orEmpty(), { if (it.codePointCount(0, it.length) <= 100) h.catalogField(mode, "query", it) },
                Modifier.fillMaxWidth(), label = { Text("Найти продукт или рецепт") }, singleLine = true,
                shape = MaterialTheme.shapes.small)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (mode == "batch-picker") {
                    TextButton(onClick = { h.pop() }) { Text("← К приёму пищи") }
                    Button(onClick = { h.pop() }, enabled = h.form?.data?.lines?.isNotEmpty() == true) { Text("Готово (${h.form?.data?.lines?.size ?: 0})") }
                } else if (!picker) {
                    Box {
                        TextButton(onClick = { createMenu = true }) { Text("＋ Создать") }
                        DropdownMenu(createMenu, { createMenu = false }) {
                            DropdownMenuItem(text = { Text("Свой продукт") }, onClick = { createMenu = false; h.openForm("product") })
                            DropdownMenuItem(text = { Text("Свой рецепт") }, onClick = { createMenu = false; h.openForm("recipe") })
                            DropdownMenuItem(text = { Text("Быстрая запись КБЖУ") }, onClick = { createMenu = false; h.openForm("quick", meal = h.route.id ?: h.defaultMeal) })
                        }
                    }
                    TextButton(onClick = { h.openForm("batch-food", meal = h.route.id ?: h.defaultMeal) }) { Text("Выбрать несколько") }
                } else TextButton(onClick = { h.back() }) { Text("← Назад") }
            }
            if (mode == "batch-picker" && (h.form?.data?.lines?.size ?: 0) >= 500) Muted("Выбрано 500 позиций. Уберите одну, чтобы выбрать другую.")
        }
    }
}

@Composable internal fun CatalogScreen(mode: String) {
    val h = LocalUi.current
    val values = h.catalogContext(mode)
    val query = values["query"].orEmpty()
    val category = values["category"].orEmpty()
    val filter = values["filter"] ?: "all"
    val page = values["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    var items by remember { mutableStateOf<List<CatalogItemEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, category, filter, page, h.state.dataRevision) {
        loading = true; error = null; delay(150)
        try {
            items = h.repository.queryCatalog(query, category.ifEmpty { null }, page * 40L, 40,
                favoritesOnly = filter == "favorite", recentOnly = filter == "recent",
                kind = if (mode == "line-picker" && h.form?.entity?.kind == "recipe") "product" else if (filter == "recipe") "recipe" else null)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = app.diet.messages.userMessage(e, "Не удалось открыть библиотеку") }
        finally { loading = false }
    }
    ScreenLoad(loading)
    val picker = mode in setOf("line-picker", "replace-picker", "batch-picker")
    ScreenHeading(when (mode) { "replace-picker" -> "Выбрать новый продукт"; "line-picker" -> "Добавить в состав"; "batch-picker" -> "Соберите свой приём"; "add-food" -> "Что было вкусного?"; else -> "Моя библиотека" },
        when (mode) { "replace-picker" -> "Выбранный продукт заменит только эту запись после сохранения."; "batch-picker" -> "Отметьте продукты и порции. В дневник они попадут вместе после подтверждения."; else -> "Избранное и недавнее — в начале списка." })
    Choices(listOf("all" to "Всё", "favorite" to "Избранное", "recent" to "Недавние", "recipe" to "Рецепты"), filter) { h.catalogField(mode, "filter", it) }
    if (h.state.categories.isNotEmpty()) CollapsiblePanel("Категории", h.state.categories.find { it.id == category }?.name ?: "Все категории") {
        Choices(listOf("" to "Все категории") + h.state.categories.map { it.id to it.name }, category) { h.catalogField(mode, "category", it) }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    ErrorNotice(error)
    if (picker) ErrorNotice(h.form?.error)
    if (!loading && error == null && items.isEmpty()) EmptyState("Пока ничего не найдено", "Попробуйте другое название или создайте свой продукт через кнопку сверху.")
    items.forEach { item -> key(item.id) {
        DietCard {
            val selected = h.form?.data?.lines?.firstOrNull { it.sourceId == item.id }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Muted("${if (item.favorite) "★ · " else ""}${if (item.kind == "recipe") "Рецепт · " else ""}${item.snapshot.toDomain().nutrientsPerBasis.kcal.format(1)} ккал на ${decimal(item.snapshot.basisMicros)} ${unitLabel(item.unit, item.portionName)}")
                }
                if (!picker) CatalogItemMenu(item)
            }
            when (mode) {
                "batch-picker" -> {
                    val session = h.form
                    if (session != null) {
                        ToggleRow("Выбрать ${item.name}", selected != null, session.editable && (selected != null || session.data.lines.size < 500)) { checked ->
                            if (checked) session.addLine(LineInput(item.snapshot, item.snapshot.basisMicros, item.id))
                            else session.data.lines.filter { it.sourceId == item.id }.forEach { session.removeLine(it.id) }
                        }
                        if (selected != null) QuantityField(session, selected)
                    }
                }
                "line-picker", "replace-picker" -> Action(if (mode == "replace-picker") "Использовать новый снимок" else "Добавить в состав", secondary = true) {
                    val input = LineInput(item.snapshot, item.snapshot.basisMicros, item.id)
                    if (mode == "replace-picker") h.form?.replaceEntry(input) else h.form?.addLine(input)
                    h.pop()
                }
                else -> Action("Выбрать порцию", secondary = true) { h.openForm("portion", item.id, h.route.id ?: h.defaultMeal) }
            }
        }
    } }
    PageControls(page, items.size == 40, loading) { h.catalogField(mode, "page", it.toString()) }
    if (!picker) TextButton(onClick = { h.navigate("categories") }) { Text("Управлять категориями") }
}

@Composable private fun CatalogItemMenu(item: CatalogItemEntity) {
    val h = LocalUi.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("Ещё") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(if (item.favorite) "Убрать из избранного" else "В избранное") }, onClick = { expanded = false; h.command("favorite", item.id, item.revision, mapOf("favorite" to (!item.favorite).toString())) })
            DropdownMenuItem(text = { Text("Изменить") }, onClick = { expanded = false; h.openForm(item.kind, item.id) })
            DropdownMenuItem(text = { Text("Быстрые порции") }, onClick = { expanded = false; h.openForm("quick-portions", item.id) })
            DropdownMenuItem(text = { Text("Удалить из библиотеки") }, onClick = { expanded = false; h.confirm("Удалить ${item.name}?", "Исторические записи сохранятся. Удаление можно отменить в появившейся плашке.", "Удалить") { h.command("delete-catalog", item.id, item.revision) } })
        }
    }
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
    ScreenLoad(loading)
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
    ScreenLoad(loading)
    ScreenHeading("Добавить сочетание", "Выберите самостоятельные снимки сохранённого шаблона. Уже введённый состав останется.")
    ErrorNotice(error); if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    items.forEach { template -> DietCard(template.name) { Action("Добавить этот состав", secondary = true, enabled = session.editable && !loading) { session.busy = true; h.launch { try { val lines = h.repository.templateLines(template.id).map { LineInput(it.snapshot, it.quantityMicros, it.sourceItemId) }; require(session.data.lines.size + lines.size <= 500) { "В составе может быть не больше 500 строк. Ничего не добавлено." }; session.busy = false; session.addLines(lines); h.pop() } finally { session.busy = false } } } } }
    if (!loading && items.isEmpty()) EmptyState("Шаблонов на этой странице нет", "Сохраните сочетание в разделе шаблонов или с главной.")
    PageControls(page, items.size == 40, loading || session.busy) { page = it }
}
@Composable internal fun PageControls(page: Int, hasNext: Boolean, loading: Boolean, onPage: (Int) -> Unit) { if (page > 0 || hasNext) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { onPage(page - 1) }, enabled = page > 0 && !loading) { Text("← Ранее") }; Text("${page + 1}", Modifier.padding(16.dp)); TextButton(onClick = { onPage(page + 1) }, enabled = hasNext && !loading) { Text("Далее →") } } }
