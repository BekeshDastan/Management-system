package app.diet.ui

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    var day by remember(h.date) { mutableStateOf<ReportDay?>(null) }; var dayError by remember(h.date) { mutableStateOf<String?>(null) }
    ReportDrawnWhen { day != null && dayError == null }
    LaunchedEffect(h.date, state.dataRevision) { dayError = null; try { val selected = LocalDate.parse(h.date); day = h.repository.pageReportDays(selected, selected, 0, 1).days.single() } catch (e: CancellationException) { throw e } catch (e: Exception) { dayError = app.diet.messages.userMessage(e, "Не удалось посчитать выбранный день") } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Column(Modifier.weight(1f)) { Eyebrow("DIET · ДЛЯ СЕБЯ"); Text(if (state.profile?.name.isNullOrBlank()) "Хороший день для себя." else "${state.profile?.name.orEmpty()}, ваш день.", style = MaterialTheme.typography.headlineSmall) }; TextButton(onClick = { h.navigate("profile") }) { Text("Профиль") } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { h.date = LocalDate.parse(h.date).minusDays(1).toString() }, enabled = !h.openingForm && h.date > "1900-01-01", modifier = Modifier.semantics { contentDescription = "Предыдущая дата" }) { Text("←") }; TextButton(onClick = { h.navigate("calendar") }, modifier = Modifier.weight(1f)) { Text(dateLabel(h.date), textAlign = TextAlign.Center) }; TextButton(onClick = { h.date = LocalDate.parse(h.date).plusDays(1).toString() }, enabled = !h.openingForm && h.date < today(), modifier = Modifier.semantics { contentDescription = "Следующая дата" }) { Text("→") } }
    if (h.date != today()) TextButton(onClick = { h.date = today() }) { Text("Вернуться к сегодня") }
    ErrorNotice(dayError)
    Action("＋ Добавить еду") { h.navigate("add-food") }
    DayCompletionControl(day?.food)
    state.drafts.take(3).forEach { draft -> DietCard("Черновик ждёт вас") { Muted("${draft.kind} · ${draft.selectedLocalDate?.let(::dateLabel).orEmpty()}"); Action("Продолжить", secondary = true) { h.resume(draft) } } }
    if (state.drafts.isNotEmpty()) TextButton(onClick = { h.navigate("drafts") }) { Text("Все черновики →") }
    h.preferences.homeOrder.filterNot { it in h.preferences.hiddenHome }.forEach { block -> when (block) {
        "hero" -> CoverArt(h.preferences.cover)
        "nutrition" -> NutritionHero(day)
        "meals" -> MealCards()
        "journal" -> {
            val moment = state.moments.find { it.localDate == h.date }; val photos = state.photos.filter { it.momentId == moment?.id }
            DietCard("За кадром цифр") { Muted(moment?.note?.takeIf(String::isNotBlank) ?: "Как вы сегодня? Оставьте пару слов или фотографию."); moment?.mood?.let { Pill(when (it) { "great" -> "✦ Отлично"; "good" -> "☀ Хорошо"; "tired" -> "☾ Усталость"; else -> "◌ Обычно" }) }; photos.take(1).forEach { h.media.Thumbnail(it.assetId, Modifier.fillMaxWidth().height(180.dp), it.caption.ifBlank { "Фото дня" }) }; TextButton(onClick = { h.openForm("moment") }) { Text(if (moment == null) "Добавить момент →" else "Открыть мой день →") } }
        }
        "prompt" -> DietCard(color = MaterialTheme.colorScheme.primaryContainer) { Eyebrow("Маленькое напоминание"); Text("Достаточно записать как было.", style = MaterialTheme.typography.headlineSmall); Muted("Не каждый день должен быть идеальным. Наблюдения помогают замечать свой ритм.") }
        "measurements" -> MeasurementsCards()
        "level" -> if (h.preferences.gameLevels) { val level = GameRules.level(state.completedDayCount); DietCard("${level.number} · ${level.name}") { Muted("${state.completedDayCount} заполненных дат. Уровень растёт за ведение дневника, а не за количество еды или изменение веса."); level.daysToNext?.let { Muted("До следующего уровня: $it дат") }; TextButton(onClick = { h.navigate("game") }) { Text("Названия и уровни →") } } }
    } }
    Action("Открыть инструменты дня", secondary = true) { h.navigate("settings") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NutritionHero(record: ReportDay?) {
    val h = LocalUi.current; val day = record?.food; val complete = day?.completed == true; val entryCount = day?.entryCount ?: 0; val goal = record?.goal
    DietCard(color = LocalDietColors.current.forest) {
        Text("СЕГОДНЯ В ЦИФРАХ", style = MaterialTheme.typography.labelSmall, color = LocalDietColors.current.lime)
        Text(day?.nutrients?.kcal?.format(1)?.let { "$it ккал" } ?: "Считаем день…", style = MaterialTheme.typography.displaySmall, color = androidx.compose.ui.graphics.Color.White)
        if (day != null && entryCount == 0 && !complete) Text("Записей пока нет", color = androidx.compose.ui.graphics.Color(0xFFCFDBCC))
        else day?.nutrients?.let { Text("Б ${it.protein.format(1)} · Ж ${it.fat.format(1)} · У ${it.carbs.format(1)} г", color = androidx.compose.ui.graphics.Color(0xFFCFDBCC)) }
        Text(when { day == null -> "Загружаем точные данные"; complete && entryCount == 0 -> "Подтверждено: еды не было"; complete -> "День заполнен"; entryCount > 0 -> "Частично заполнен"; else -> "Нет данных о питании" }, color = LocalDietColors.current.lime)
        goal?.let { target ->
            Text("Цель на дату: ${target.kcal.format(1)} ккал", color = androidx.compose.ui.graphics.Color(0xFFCFDBCC))
            day?.let { Text(nutritionBalance(it.nutrients.kcal, target.kcal, "ккал"), color = LocalDietColors.current.lime) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(Triple("Белки", day?.nutrients?.protein, target.protein), Triple("Жиры", day?.nutrients?.fat, target.fat), Triple("Углеводы", day?.nutrients?.carbs, target.carbs)).forEach { (label, actual, value) ->
                    Column(Modifier.widthIn(min = 84.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(label, color = androidx.compose.ui.graphics.Color(0xFFCFDBCC), style = MaterialTheme.typography.labelMedium)
                        Text("${actual?.format(1) ?: "—"} / ${value.format(1)} г", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleMedium)
                        actual?.let { Text(nutritionBalance(it, value, "г"), color = LocalDietColors.current.lime, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            MutedOnForest("Остаток и превышение считаются только по внесённым записям; это не рекомендация изменить рацион.")
        } ?: Text("Можно добавить свою цель", color = androidx.compose.ui.graphics.Color(0xFFCFDBCC))
        TextButton(onClick = { h.navigate("goals") }, colors = ButtonDefaults.textButtonColors(contentColor = LocalDietColors.current.lime)) { Text("Мои цели →") }
    }
}

@Composable private fun MutedOnForest(text: String) { Text(text, color = androidx.compose.ui.graphics.Color(0xFFCFDBCC), style = MaterialTheme.typography.bodySmall) }
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

@Composable private fun MealCards() {
    val h = LocalUi.current
    var occupiedMeals by remember(h.date) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(h.date, h.state.dataRevision) { occupiedMeals = h.repository.mealsWithEntries(h.date) }
    Text("Приёмы пищи", style = MaterialTheme.typography.titleLarge)
    h.state.meals.filter { !it.archived || it.id in occupiedMeals }.forEach { meal ->
        var page by remember(h.date, meal.id) { mutableIntStateOf(0) }; var entries by remember(h.date, meal.id) { mutableStateOf<List<FoodEntryEntity>>(emptyList()) }; var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(h.date, meal.id, page, h.state.dataRevision) { loading = true; try { entries = h.repository.queryHistory(h.date, h.date, mealId = meal.id, offset = page * 40L, limit = 40) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e) } finally { loading = false } }
        DietCard(meal.name + if (meal.archived) " · архив" else "") {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()); ErrorNotice(error); if (!loading && entries.isEmpty()) Muted("Пока ничего не записано")
            entries.forEach { entry -> Row(Modifier.fillMaxWidth().clickable { h.openForm("entry", entry.id) }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Column(Modifier.weight(1f)) { Text(entry.displayName(), style = MaterialTheme.typography.titleMedium); if (entry.kind != "quick") Muted("${decimal(requireNotNull(entry.quantityMicros))} ${unitLabel(requireNotNull(entry.snapshot).unit, entry.snapshot.portionName)}") }; Text("${entry.nutrients().kcal.format(1)} ккал", style = MaterialTheme.typography.labelLarge) } }
            if (!meal.archived) TextButton(onClick = { h.navigate("add-food", meal.id) }) { Text("＋ Добавить в ${meal.name.lowercase()}") }
            TextButton(onClick = { h.openForm("moment", meal = meal.id, extra = mapOf("photoDefaultMeal" to meal.id)) }) { Text("Фото приёма →") }
            PageControls(page, entries.size == 40, loading) { page = it }
            if (entries.isNotEmpty()) { TextButton(onClick = { h.launch { val source = h.mealRows(h.date, meal.id); h.openForm("template", meal = meal.id, extra = mapOf("name" to meal.name), lines = source.map { UiLine.from(h.repository.foodAsLine(it)) }) } }) { Text("Сохранить как шаблон") }; TextButton(onClick = { h.openForm("copy", meal = meal.id, extra = mapOf("sourceDate" to h.date, "sourceMeal" to meal.id)) }) { Text("Скопировать приём") } }
        }
    }
}

@Composable private fun MeasurementsCards() {
    val h = LocalUi.current; val weight = h.state.weights.find { it.localDate == h.date }; val sleep = h.state.sleeps.find { it.localDate == h.date }
    DietCard("Утренний вес", color = LocalDietColors.current.peach) { Text(weight?.let { "${decimal(it.centiKg.toLong(), 2)} кг" } ?: "Нет измерения", style = MaterialTheme.typography.headlineSmall); if (h.preferences.gameNames) GameRules.weightName(weight?.centiKg?.toLong())?.let { Pill(it) }; TextButton(onClick = { h.openForm("weight") }) { Text(if (weight == null) "Записать вес →" else "Изменить вес →") } }
    DietCard("Сон", color = LocalDietColors.current.lavender) { Text(sleep?.let { Numbers.formatSleepMinutes(it.minutes) } ?: "Нет записи", style = MaterialTheme.typography.headlineSmall); if (h.preferences.gameNames) GameRules.sleepName(sleep?.minutes)?.let { Pill(it) }; TextButton(onClick = { h.openForm("sleep") }) { Text(if (sleep == null) "Записать сон →" else "Изменить сон →") } }
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
