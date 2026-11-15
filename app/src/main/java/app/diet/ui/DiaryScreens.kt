package app.diet.ui

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.diet.data.*
import app.diet.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

internal fun dateLabel(value: String): String = runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))) }.getOrDefault(value)

@Composable internal fun WelcomeScreen() {
    val h = LocalUi.current
    CoverArt("5", title = "Ваш день.\nВаш ритм.")
    Eyebrow("Личный дневник питания")
    ScreenHeading("Поближе к себе.", "Питание, вес, сон и маленькие наблюдения в одном спокойном месте.")
    DietCard { Text("Без аккаунта. В вашем темпе.", style = MaterialTheme.typography.titleLarge); Muted("Основные функции работают офлайн. Данные остаются на телефоне. Дневник можно заполнять без целей и необязательных сведений.") }
    Action("Начать") { h.openForm("onboarding-profile") }
    h.state.drafts.filter { it.kind.startsWith("onboarding") }.forEach { d -> Action("Продолжить первый запуск", secondary = true) { h.resume(d) } }
}

@Composable internal fun OnboardingGoals() {
    val h = LocalUi.current
    Eyebrow("Последний шаг")
    ScreenHeading("Цели — по желанию.", "Укажите привычные значения самостоятельно или начните с наблюдений.")
    DietCard("Никаких догадок") { Muted("Приложение пока не предлагает расчёт КБЖУ. Пропущенная цель не мешает заполнять питание, вес и сон.") }
    Action("Указать ручные цели") { h.openForm("onboarding-goal") }
    Action("Начать без целей", secondary = true) { h.form = UiFormSession.create(h.repository, h.scope, "onboarding-skip"); h.push(UiRoute("editor")); h.saveForm() }
    h.state.drafts.filter { it.kind == "onboarding-goal" }.forEach { d -> Action("Вернуться к черновику цели", secondary = true) { h.resume(d) } }
}

@Composable internal fun HomeScreen() {
    val h = LocalUi.current; val state = h.state
    var day by remember(h.date) { mutableStateOf<ReportDay?>(null) }
    var overview by remember(h.date) { mutableStateOf<DiaryOverview?>(null) }
    var dayError by remember(h.date) { mutableStateOf<String?>(null) }
    ReportDrawnWhen { day != null && dayError == null }
    ScreenLoad((day == null || overview == null) && dayError == null)
    LaunchedEffect(h.date, state.dataRevision) {
        dayError = null
        try {
            val selected = LocalDate.parse(h.date)
            day = h.repository.pageReportDays(selected, selected, 0, 1).days.single()
            overview = h.repository.readDiaryOverview(selected)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { dayError = app.diet.messages.userMessage(e, "Не удалось посчитать выбранный день") }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (h.date == today()) "Сегодня" else dateLabel(h.date), style = MaterialTheme.typography.headlineSmall)
            if (h.date == today()) Muted(LocalDate.parse(h.date).format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.forLanguageTag("ru"))))
        }
        IconButton(onClick = { h.navigate("search") }, modifier = Modifier.semantics { contentDescription = "Поиск по дневнику" }) { Icon(Icons.Outlined.Search, contentDescription = null) }
        IconButton(onClick = { h.navigate("calendar") }, modifier = Modifier.semantics { contentDescription = "Открыть календарь" }) { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) }
    }
    DiaryWeekStrip(overview)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, tint = Color(0xFFFFB35C), modifier = Modifier.size(22.dp))
        Text(recordingStreakLabel(overview?.recordingStreak ?: 0), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ErrorNotice(dayError)
    NutritionHero(day)
    Action("＋ Добавить еду") { h.navigate("add-food") }
    DayCompletionControl(day?.food)
    state.drafts.take(3).forEach { draft ->
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Черновик ждёт вас", style = MaterialTheme.typography.labelLarge); Muted("${formLabel(draft.kind)} · ${draft.selectedLocalDate?.let(::dateLabel).orEmpty()}") }
                TextButton(onClick = { h.resume(draft) }) { Text("Продолжить") }
            }
        }
    }
    if (state.drafts.isNotEmpty()) TextButton(onClick = { h.navigate("drafts") }) { Text("Все черновики →") }
    h.preferences.homeOrder.filterNot { it in h.preferences.hiddenHome }.forEach { block -> when (block) {
        "meals" -> MealCards(overview)
        "journal" -> {
            val moment = state.moments.find { it.localDate == h.date }; val photos = state.photos.filter { it.momentId == moment?.id }
            DietCard(modifier = Modifier.returnAnchor("moment"), title = "Мой день") {
                moment?.note?.takeIf(String::isNotBlank)?.let { Text(it) }
                moment?.mood?.let { Pill(when (it) { "great" -> "✦ Отлично"; "good" -> "☀ Хорошо"; "tired" -> "☾ Усталость"; else -> "◌ Обычно" }) }
                photos.take(1).forEach { h.media.Thumbnail(it.assetId, Modifier.fillMaxWidth().height(120.dp), it.caption.ifBlank { "Фото дня" }) }
                TextButton(onClick = { h.openForm("moment") }) { Text(if (moment == null) "Добавить момент →" else "Открыть мой день →") }
            }
        }
        "measurements" -> MeasurementsCards()
    } }
}

@Composable private fun DiaryWeekStrip(overview: DiaryOverview?) {
    val h = LocalUi.current
    val selectedDate = LocalDate.parse(h.date)
    val first = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong())
    val days = overview?.week ?: List(7) { DiaryWeekDay(first.plusDays(it.toLong()), false, false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val availableWidth = maxWidth
      Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
       Row(Modifier.width(maxOf(availableWidth, 336.dp)), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { day ->
            val active = day.date == selectedDate; val enabled = day.date <= LocalDate.now() && day.date >= DomainDates.MIN
            Column(Modifier.weight(1f).heightIn(min = 60.dp).clickable(enabled = enabled) { h.date = day.date.toString() }
                .semantics { contentDescription = dateLabel(day.date.toString()) + when { day.completed -> ", питание заполнено"; day.recorded -> ", есть записи еды"; else -> ", нет записей еды" }; selected = active },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Surface(Modifier.size(32.dp), shape = CircleShape,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                    Box(contentAlignment = Alignment.Center) {
                        if (day.completed) Icon(Icons.Outlined.Check, contentDescription = null, Modifier.size(20.dp), tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                        else Text(day.date.dayOfMonth.toString(), color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f) else if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Text(listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[day.date.dayOfWeek.value - 1], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
    }
}

@Composable private fun NutritionHero(record: ReportDay?) {
    val h = LocalUi.current; val food = record?.food; val target = record?.goal; val actual = food?.nutrients
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(if (target == null) "Цель калорий" else if (food == null) "Осталось калорий" else if ((target.kcal - food.nutrients.kcal).sign >= 0) "Осталось калорий" else "Сверх цели")
            Text(if (target == null) "Не задана" else actual?.let { (target.kcal - it.kcal).let { n -> (if (n.sign < 0) -n else n).format(1) } } ?: "—", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted("Употреблено калорий")
            Text(if (food == null || food.entryCount == 0 && !food.completed) "—" else "${actual?.kcal?.format(1)} ккал", style = MaterialTheme.typography.titleLarge)
        }
        if (actual != null && (food.entryCount > 0 || food.completed)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MacroLabel("Б", actual.protein.format(1), Color(0xFF64C7FF))
                MacroLabel("Ж", actual.fat.format(1), Color(0xFFFFBC66))
                MacroLabel("У", actual.carbs.format(1), Color(0xFFC09AFF))
            }
        }
    }
}

@Composable private fun MacroLabel(label: String, amount: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Text(label, color = color, style = MaterialTheme.typography.labelLarge); Text("$amount г", style = MaterialTheme.typography.bodyMedium) }
}

internal fun nutritionBalance(actual: Rational, goal: Rational, unit: String): String { val remaining = goal - actual; return if (remaining.sign >= 0) "Осталось ${remaining.format(1)} $unit" else "Выше цели ${(-remaining).format(1)} $unit" }

/** This control is intentionally outside the configurable home blocks. */
@Composable internal fun DayCompletionControl(day: NutritionDay?) {
    val h = LocalUi.current
    Action(if (day?.completed == true) "✓ День заполнен · открыть для правки" else "Отметить питание заполненным", secondary = true, enabled = day != null) {
        val selectedDate = h.date
        if (day?.completed != true && day?.entryCount == 0) h.confirm("Еды за день не было?", "Пустой подтверждённый день будет учитываться как 0,0 ккал. Пропуск даты так не учитывается.", "Подтвердить отсутствие еды") { h.command("complete-day", fields = mapOf("date" to selectedDate, "completed" to "true", "empty" to "true")) }
        else h.command("complete-day", fields = mapOf("date" to selectedDate, "completed" to (day?.completed != true).toString(), "empty" to "false"))
    }
}

@Composable private fun MealCards(overview: DiaryOverview?) {
    val h = LocalUi.current
    h.state.meals.filter { !it.archived || (overview?.meals?.get(it.id)?.entryCount ?: 0) > 0 }.forEach { meal ->
        var expanded by rememberSaveable(h.date, meal.id) { mutableStateOf(false) }
        LaunchedEffect(h.focusMealId, meal.id) { if (h.focusMealId == meal.id) expanded = true }
        var page by rememberSaveable(h.date, meal.id) { mutableIntStateOf(0) }
        var entries by remember(h.date, meal.id) { mutableStateOf<List<FoodEntryEntity>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
        val summary = overview?.meals?.get(meal.id)
        LaunchedEffect(expanded, h.date, meal.id, page, h.state.dataRevision) {
            if (expanded) {
                loading = true; error = null
                try { entries = h.repository.queryHistory(h.date, h.date, mealId = meal.id, offset = page * 40L, limit = 40) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = app.diet.messages.userMessage(e) }
                finally { loading = false }
            }
        }
        ScreenLoad(loading)
        Surface(Modifier.fillMaxWidth().mealAnchor(meal.id), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(meal.name + if (meal.archived) " · архив" else "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) { Text(summary?.nutrients?.kcal?.format(1) ?: if (overview != null) "0,0" else "—", style = MaterialTheme.typography.titleLarge); Text("ккал", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!meal.archived) IconButton(onClick = { h.navigate("add-food", meal.id) }, modifier = Modifier.semantics { contentDescription = "Добавить еду в ${meal.name}" }) { Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                }
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 14.dp, end = 12.dp).heightIn(min = 48.dp)
                    .semantics { role = Role.Button; stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"; contentDescription = "Состав ${meal.name}: " + foodPositionCount(summary?.entryCount ?: 0) }, verticalAlignment = Alignment.CenterVertically) {
                    Muted(if (overview == null) "Загружаем состав…" else foodPositionCount(summary?.entryCount ?: 0), Modifier.weight(1f))
                    Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedVisibility(expanded, enter = expandVertically(tween(if (LocalReducedMotion.current) 0 else 160)) + fadeIn(tween(if (LocalReducedMotion.current) 0 else 160)), exit = shrinkVertically(tween(if (LocalReducedMotion.current) 0 else 160)) + fadeOut(tween(if (LocalReducedMotion.current) 0 else 160))) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        ErrorNotice(error)
                        entries.forEach { entry -> Row(Modifier.fillMaxWidth().clickable { h.openForm("entry", entry.id) }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(entry.displayName(), style = MaterialTheme.typography.titleMedium); if (entry.kind != "quick") Muted("${decimal(requireNotNull(entry.quantityMicros))} ${unitLabel(requireNotNull(entry.snapshot).unit, entry.snapshot.portionName)}") }
                            Text(entry.nutrients().kcal.format(1), style = MaterialTheme.typography.labelLarge)
                            FoodEntryActions(entry)
                        } }
                        PageControls(page, entries.size == 40, loading) { page = it }
                        if (!meal.archived) TextButton(onClick = { h.navigate("add-food", meal.id) }) { Text("＋ Добавить в ${meal.name.lowercase()}") }
                        TextButton(onClick = { h.openForm("moment", meal = meal.id, extra = mapOf("photoDefaultMeal" to meal.id)) }) { Text("Фото приёма →") }
                        if ((summary?.entryCount ?: 0) > 0) {
                            TextButton(onClick = { h.launch { val source = h.mealRows(h.date, meal.id); h.openForm("template", meal = meal.id, extra = mapOf("name" to meal.name), lines = source.map { UiLine.from(h.repository.foodAsLine(it)) }) } }) { Text("Сохранить как шаблон") }
                            TextButton(onClick = { h.openForm("copy", meal = meal.id, extra = mapOf("sourceDate" to h.date, "sourceMeal" to meal.id)) }) { Text("Скопировать приём") }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun MeasurementsCards() {
    val h = LocalUi.current; val weight = h.state.weights.find { it.localDate == h.date }; val sleep = h.state.sleeps.find { it.localDate == h.date }
    DietCard(modifier = Modifier.returnAnchor("weight"), title = "Утренний вес", color = LocalDietColors.current.peach) { Text(weight?.let { "${decimal(it.centiKg.toLong(), 2)} кг" } ?: "Нет измерения", style = MaterialTheme.typography.headlineSmall); if (h.preferences.gameNames) GameRules.weightName(weight?.centiKg?.toLong())?.let { Pill(it) }; TextButton(onClick = { h.openForm("weight") }) { Text(if (weight == null) "Записать вес →" else "Изменить вес →") } }
    DietCard(modifier = Modifier.returnAnchor("sleep"), title = "Сон", color = LocalDietColors.current.lavender) { Text(sleep?.let { Numbers.formatSleepMinutes(it.minutes) } ?: "Нет записи", style = MaterialTheme.typography.headlineSmall); if (h.preferences.gameNames) GameRules.sleepName(sleep?.minutes)?.let { Pill(it) }; TextButton(onClick = { h.openForm("sleep") }) { Text(if (sleep == null) "Записать сон →" else "Изменить сон →") } }
}

@Composable internal fun CalendarScreen() {
    val h = LocalUi.current; var month by remember { mutableStateOf(YearMonth.from(LocalDate.parse(h.date))) }; var raw by remember { mutableStateOf(h.date) }; var error by remember { mutableStateOf<String?>(null) }
    var points by remember { mutableStateOf<Map<String, ReportSeriesPoint>>(emptyMap()) }; var loading by remember { mutableStateOf(true) }
    LaunchedEffect(month, h.state.dataRevision) { loading = true; try { points = h.repository.readReport(month.atDay(1), minOf(month.atEndOfMonth(), LocalDate.now())).series.associateBy { it.start.toString() } } catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e) } finally { loading = false } }
    ScreenHeading("Календарь", "Откройте любую прошлую дату, чтобы посмотреть или дополнить дневник.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { if (month > YearMonth.of(1900, 1)) month = month.minusMonths(1) }, modifier = Modifier.semantics { contentDescription = "Предыдущий месяц" }) { Text("←") }; Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"))), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); TextButton(onClick = { if (month < YearMonth.from(LocalDate.now())) month = month.plusMonths(1) }, modifier = Modifier.semantics { contentDescription = "Следующий месяц" }) { Text("→") } }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    DietCard { Column(Modifier.horizontalScroll(rememberScrollState()).width(350.dp)) {
        Row(Modifier.fillMaxWidth()) { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium) } }
        val offset = month.atDay(1).dayOfWeek.value - 1
        (0 until ((offset + month.lengthOfMonth() + 6) / 7)).forEach { week -> Row(Modifier.fillMaxWidth()) { (0..6).forEach { day -> val n = week * 7 + day - offset + 1; Box(Modifier.weight(1f)) { if (n in 1..month.lengthOfMonth()) { val date = month.atDay(n).toString(); val point = points[date]; val markers = buildString { if (point?.nutritionCompleteDays == 1) append("✓") else if (point?.nutritionPartialDays == 1) append("·"); if ((point?.weightDays ?: 0) > 0) append("В"); if ((point?.sleepDays ?: 0) > 0) append("С"); if ((point?.momentDays ?: 0) > 0) append("М") }
            TextButton(onClick = { h.date = date; h.root("home") }, enabled = date <= today(), modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).semantics { contentDescription = dateLabel(date) + when { point == null -> ", данные загружаются"; point.nutritionCompleteDays == 1 -> ", питание заполнено"; point.nutritionPartialDays == 1 -> ", питание частично"; else -> ", питания нет" } + if (markers.isEmpty()) "" else ", отметки $markers" }, contentPadding = PaddingValues(1.dp)) { Column { Text(if (date == h.date) "[$n]" else n.toString()); Text(markers.ifEmpty { "—" }, style = MaterialTheme.typography.labelSmall) } }
        } } } } }
    }; Muted("✓ питание заполнено · точка: частично · В: вес · С: сон · М: момент. На узком экране календарь можно листать по горизонтали.") }
    OutlinedTextField(raw, { raw = it }, Modifier.fillMaxWidth(), label = { Text("Точная дата ГГГГ-ММ-ДД") }, singleLine = true)
    ErrorNotice(error)
    Action("Открыть дату") { runCatching { DomainDates.validateFactDate(DomainDates.parse(raw), LocalDate.now()) }.fold({ h.date = it.toString(); h.root("home") }, { error = app.diet.messages.userMessage(it) }) }
}

@Composable internal fun ProfileScreen() {
    val h = LocalUi.current; val p = h.state.profile
    ScreenHeading(p?.name?.takeIf(String::isNotBlank) ?: "Ваш профиль", "Локальный дневник без аккаунта.")
    DietCard { Text(listOfNotNull(p?.age?.let { "$it лет" }, p?.heightCentiCm?.let { "${decimal(it.toLong(), 2)} см" }).joinToString(" · ").ifBlank { "Необязательные сведения не указаны" }); Action("Изменить профиль", secondary = true) { h.openForm("profile") } }
    Action("Ручные цели", secondary = true) { h.navigate("goals") }; Action("Оформление и настройки", secondary = true) { h.navigate("settings") }
}

@Composable internal fun GoalsScreen() {
    val h = LocalUi.current
    ScreenHeading("Мои цели", "Действуют с выбранной даты. Показатели прошлой еды остаются прежними.")
    Action("＋ Добавить ручную цель") { h.openForm("goal") }
    if (h.state.goals.isEmpty()) EmptyState("Наблюдать тоже можно", "Заполняйте дневник без цели или укажите свои значения.")
    h.state.goals.sortedByDescending { it.startDate }.forEach { goal -> DietCard("С ${dateLabel(goal.startDate)}") { NutritionText(goal.nutrients.toDomain()); TextButton(onClick = { h.openForm("goal", goal.id) }) { Text("Изменить") }; DeleteLink("Удалить цель") {
        val chain = h.state; val next = chain.goals.filter { it.startDate > goal.startDate }.minByOrNull { it.startDate }; val previous = chain.goals.filter { it.startDate < goal.startDate }.maxByOrNull { it.startDate }
        val interval = "с ${dateLabel(goal.startDate)}" + (next?.let { " по ${dateLabel(LocalDate.parse(it.startDate).minusDays(1).toString())}" } ?: " и далее")
        h.confirm("Удалить цель?", "Изменится сравнение $interval. " + (previous?.let { "На этот интервал продолжится цель от ${dateLabel(it.startDate)}." } ?: "На этом интервале цели не будет.") + " Сами записи еды останутся прежними.", "Удалить") { h.command("delete-goal", goal.id, goal.revision, mapOf("timeline" to chain.goalTimelineRevision.toString())) }
    } } }
}
