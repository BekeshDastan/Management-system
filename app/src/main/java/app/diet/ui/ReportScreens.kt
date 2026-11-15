package app.diet.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.diet.data.*
import app.diet.domain.*
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

@Composable internal fun ReportsScreen() {
    val h = LocalUi.current; val graph = h.route.name.startsWith("graph-"); val metric = h.route.name.removePrefix("graph-").takeIf { graph } ?: "food"
    val end = h.view["report.end"] ?: h.date; val length = h.view["report.length"]?.toLongOrNull() ?: 30L; val startRaw = h.view["report.start"] ?: runCatching { maxOf(DomainDates.MIN, LocalDate.parse(end).minusDays(length - 1)).toString() }.getOrDefault(end)
    var result by remember { mutableStateOf<ReportBundle?>(null) }; var previous by remember { mutableStateOf<ReportBundle?>(null) }; var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(startRaw, end, h.state.dataRevision) {
        result = null; previous = null; error = null; loading = true
        try { val first = DomainDates.parse(startRaw); val last = DomainDates.validateFactDate(DomainDates.parse(end), LocalDate.now()); val period = DatePeriod(first, last); result = h.repository.readReport(first, last); period.previous().period?.let { previous = h.repository.readReport(it.start, it.endInclusive) } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e, "Не удалось посчитать отчёт") } finally { loading = false }
    }
    ScreenHeading(if (graph) when (metric) { "weight" -> "Вес · подробнее"; "sleep" -> "Сон · подробнее"; else -> "Питание · подробнее" } else "Мой отчёт", "Спокойно посмотреть на свой ритм. Пропуски не считаются нулями.")
    Choices(listOf("7" to "7 дней", "30" to "30 дней", "90" to "90 дней", "month" to "С начала месяца", "year" to "С начала года", "custom" to "Свой период"), h.view["report.preset"] ?: length.toString()) { value ->
        h.context("report.preset", value)
        if (value != "custom") runCatching { val last = DomainDates.parse(end); when (value) { "month" -> last.withDayOfMonth(1); "year" -> last.withDayOfYear(1); else -> maxOf(DomainDates.MIN, last.minusDays(value.toLong() - 1)) } }.getOrNull()?.let { h.context("report.start", it.toString()); if (value.toLongOrNull() != null) h.context("report.length", value) }
    }
    CollapsiblePanel("Период", "$startRaw — $end", initiallyOpen = h.view["report.preset"] == "custom") { OutlinedTextField(startRaw, { h.context("report.preset", "custom"); h.context("report.start", it) }, Modifier.fillMaxWidth(), label = { Text("С ГГГГ-ММ-ДД") }, singleLine = true); OutlinedTextField(end, { h.context("report.preset", "custom"); h.context("report.end", it) }, Modifier.fillMaxWidth(), label = { Text("По ГГГГ-ММ-ДД") }, singleLine = true) }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()); ErrorNotice(error)
    result?.let { r ->
        if (!graph) DietCard("Картина за период", color = MaterialTheme.colorScheme.primaryContainer) {
            Text("${r.nutrition.mean?.kcal?.format(1) ?: "—"} ккал / день", style = MaterialTheme.typography.headlineSmall)
            Muted("${r.weight.meanKg?.format(2)?.let { "$it кг" } ?: "Вес: нет измерений"} · ${r.sleep.roundedMeanMinutes?.let(Numbers::formatSleepMinutes) ?: "Сон: нет записей"}")
            Muted("Питание N=${r.nutrition.n} · вес N=${r.weight.n} · сон N=${r.sleep.n} из M=${r.period.dayCount} дат")
        }
        if (graph) {
            DietCard("${dateLabel(r.period.start.toString())} — ${dateLabel(r.period.endInclusive.toString())}") { MetricSummary(r, previous, metric); AccessibleChart(r, metric); Muted("Точки — измерения или явно подписанные средние интервалов. Линия обрывается на пропусках; график не оценивает результат.") }
            if (metric == "food") ComparisonCard(r)
            DailyTable(r, metric)
        } else {
            Row { TextButton(onClick = { h.context("report.open", "food,weight,sleep,habits,moments,method") }) { Text("Развернуть всё") }; TextButton(onClick = { h.context("report.open", "") }) { Text("Свернуть всё") } }
            ReportPanel("food", "Питание", "${r.nutrition.mean?.kcal?.format(1) ?: "—"} ккал · N=${r.nutrition.n}/${r.period.dayCount}") {
                MetricSummary(r, previous, "food"); AccessibleChart(r, "food"); Action("Подробное питание и цели →", secondary = true) { h.navigate("graph-food") }; ComparisonCard(r)
                Text("Часто в дневнике", style = MaterialTheme.typography.titleMedium); Muted("Все фактические строки, включая частичные дни. Частота не оценивает пользу."); if (r.topFoods.isEmpty()) Muted("Пока нет записей еды"); r.topFoods.forEach { Text("${it.name} · ${it.count} записей") }
            }
            ReportPanel("weight", "Вес", "${r.weight.meanKg?.format(2)?.let { "$it кг" } ?: "Нет измерений"} · N=${r.weight.n}") { MetricSummary(r, previous, "weight"); AccessibleChart(r, "weight"); Action("Точные значения и даты →", secondary = true) { h.navigate("graph-weight") } }
            ReportPanel("sleep", "Сон", "${r.sleep.roundedMeanMinutes?.let(Numbers::formatSleepMinutes) ?: "Нет записей"} · N=${r.sleep.n}") { MetricSummary(r, previous, "sleep"); AccessibleChart(r, "sleep"); Action("Подробный график сна →", secondary = true) { h.navigate("graph-sleep") } }
            ReportPanel("habits", "Привычки и полнота", "Разные показатели — разные выборки") {
                Text("Питание заполнено: ${r.nutrition.n}/${r.period.dayCount}"); Text("Частично: ${r.nutrition.partialDays} · без данных: ${r.nutrition.missingDays}"); Text("Измерения веса: ${r.weight.n} · записи сна: ${r.sleep.n}")
                Muted("За игру считаются заполненные даты. Ни вес, ни калории не меняют награду."); DailyTable(r, "habits")
            }
            ReportPanel("moments", "Личные моменты", "Фото, настроение и заметки") {
                MomentReport(r)
            }
            ReportPanel("method", "Как посчитано", "Периоды, округление и пропуски") {
                Text("M=${r.period.dayCount} — календарные даты. N=${r.nutrition.n} — заполненные даты питания. Q=${r.nutrition.q} — заполненные даты с действующей целью.")
                Muted("Среднее питания включает только заполненные дни и подтверждённый ноль. Частичные дни показаны отдельно, пропуски не заменяются нулями. Вес и сон используют свои N; у сна подтверждённые 0 минут входят.")
                Muted("Точные исходные значения складываются до округления. Цели применяются по датам, сравнение целей использует одну и ту же выборку Q. Предыдущий период имеет ту же длительность; у нижней границы диапазон может быть короче.")
                if (r.series.any { it.aggregated }) Muted("Длинный период сгруппирован только для рисунка: каждая точка подписывает интервал и N. Итоги считают всю историю; точные дни доступны страницами.")
            }
        }
    }
}

@Composable internal fun CollapsiblePanel(title: String, caption: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(initiallyOpen) }
    LaunchedEffect(initiallyOpen) { if (initiallyOpen) open = true }
    DietCard { Column(Modifier.fillMaxWidth().clickable { open = !open }.semantics { role = Role.Button; stateDescription = if (open) "Развёрнуто" else "Свёрнуто"; heading() }) { Text("${if (open) "−" else "+"}  $title", style = MaterialTheme.typography.titleLarge); Muted(caption) }; if (open) content() }
}
@Composable private fun ReportPanel(id: String, title: String, caption: String, content: @Composable ColumnScope.() -> Unit) {
    val h = LocalUi.current; val open = id in h.view["report.open"].orEmpty().split(',')
    DietCard { Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { val values = h.view["report.open"].orEmpty().split(',').filter { it.isNotEmpty() }.toSet(); h.context("report.open", (if (open) values - id else values + id).joinToString(",")) }.semantics { role = Role.Button; stateDescription = if (open) "Развёрнуто" else "Свёрнуто"; heading() }) { Text("${if (open) "−" else "+"}  $title", style = MaterialTheme.typography.titleLarge); Muted(caption) }; if (open) content() }
}

@Composable private fun MetricSummary(r: ReportBundle, previous: ReportBundle?, metric: String) {
    when (metric) {
        "weight" -> { Text(r.weight.meanKg?.let { "${it.format(2)} кг · среднее" } ?: "Нет измерений веса", style = MaterialTheme.typography.headlineSmall); Muted("N=${r.weight.n} / M=${r.period.dayCount}"); r.weight.firstToLastChangeKg?.let { Text("От первого к последнему: ${it.format(2)} кг") }; Difference(r.weight.meanKg, previous?.weight?.meanKg, "кг", 2) }
        "sleep" -> { Text(r.sleep.roundedMeanMinutes?.let(Numbers::formatSleepMinutes) ?: "Нет записей сна", style = MaterialTheme.typography.headlineSmall); Muted("N=${r.sleep.n} / M=${r.period.dayCount}; подтверждённый 0 входит"); Difference(r.sleep.meanMinutes, previous?.sleep?.meanMinutes, "мин", 0) }
        else -> { NutritionText(r.nutrition.mean); Muted("Среднее по N=${r.nutrition.n} заполненным дням из M=${r.period.dayCount}. Частичные дни и пропуски не входят."); Difference(r.nutrition.mean?.kcal, previous?.nutrition?.mean?.kcal, "ккал", 1) }
    }
}
@Composable private fun Difference(value: Rational?, previous: Rational?, unit: String, places: Int) { if (value == null || previous == null) Muted("Для сравнения с предыдущим периодом недостаточно данных") else Muted("К предыдущему периоду: ${(value - previous).format(places)} $unit. Изменение без оценки «хорошо/плохо».") }
@Composable private fun ComparisonCard(r: ReportBundle) { val c = r.nutrition.comparison; if (c == null) Muted("Сравнения с целью пока нет: Q=0") else { Text("Цели на той же выборке Q=${r.nutrition.q}", style = MaterialTheme.typography.titleMedium); Text("Факт ${c.actualMean.kcal.format(1)} · цель ${c.targetMean.kcal.format(1)} ккал"); Text("Разница ${c.difference.kcal.format(1)} ккал · ${c.percentages.kcal?.format(1) ?: "—"}%"); Muted("Б ${c.difference.protein.format(1)} · Ж ${c.difference.fat.format(1)} · У ${c.difference.carbs.format(1)} г — разница средних по Q") } }

private fun plotNumber(r: Rational) = BigDecimal(r.numerator).divide(BigDecimal(r.denominator), MathContext.DECIMAL64).toDouble().also { require(it.isFinite()) { "Значение не помещается в координаты графика" } }
@Composable internal fun AccessibleChart(r: ReportBundle, metric: String) {
    val h = LocalUi.current
    val component = h.view["report.component"] ?: "kcal"; val showPartial = h.view["report.partial"] != "false"
    if (metric == "food") { Choices(listOf("kcal" to "Калории", "protein" to "Белки", "fat" to "Жиры", "carbs" to "Углеводы"), component) { h.context("report.component", it) }; ToggleRow("Показывать частичные дни", showPartial) { h.context("report.partial", it.toString()) } }
    val unit = when (metric) { "weight" -> "кг"; "sleep" -> "мин"; else -> if (component == "kcal") "ккал" else "г" }; val places = if (metric == "weight") 2 else 1
    fun nutrient(n: Nutrients?): Rational? = when (component) { "protein" -> n?.protein; "fat" -> n?.fat; "carbs" -> n?.carbs; else -> n?.kcal }
    val primary = MaterialTheme.colorScheme.primary; val outline = MaterialTheme.colorScheme.outline; val secondary = LocalDietColors.current.lavender
    fun value(p: ReportSeriesPoint): Rational? = when (metric) { "weight" -> p.weightMeanKg; "sleep" -> p.sleepMeanMinutes; else -> nutrient(p.nutritionMean) }
    val points = r.series.map { value(it)?.let(::plotNumber) }; val additional = if (metric == "food") r.series.flatMap { p -> listOfNotNull(if (showPartial) nutrient(p.partialNutritionMean) else null, nutrient(p.goalMean)).map(::plotNumber) } else emptyList(); val real = points.filterNotNull() + additional; val min = real.minOrNull() ?: 0.0; val max = real.maxOrNull() ?: 1.0
    var selected by remember(r.period, metric, component) { mutableIntStateOf(r.series.indexOfLast { value(it) != null || metric == "food" && nutrient(it.partialNutritionMean) != null }.coerceAtLeast(0)) }
    if (real.isEmpty()) { Muted("График появится после записей за этот период."); return }
    val description = when (metric) { "weight" -> "График веса"; "sleep" -> "График сна"; else -> "График питания по заполненным датам" } + ". Точные даты и значения доступны в таблице ниже."
    Canvas(Modifier.fillMaxWidth().height(190.dp).pointerInput(r.period, metric, component, r.series.size) { detectTapGestures { offset -> val left = 8.dp.toPx(); val width = size.width - left * 2; if (width > 0 && r.series.isNotEmpty()) selected = kotlin.math.round(((offset.x - left) / width) * maxOf(1, r.series.lastIndex)).toInt().coerceIn(0, r.series.lastIndex) } }.semantics { contentDescription = description; customActions = listOf(CustomAccessibilityAction("Предыдущая точка") { if (selected > 0) { selected--; true } else false }, CustomAccessibilityAction("Следующая точка") { if (selected < r.series.lastIndex) { selected++; true } else false }) }) {
        val left = 8.dp.toPx(); val width = size.width - left * 2; val height = size.height - 18.dp.toPx()
        fun y(v: Double) = (height - (if (max == min) .5 else ((v - min) / (max - min))) * (height - 12.dp.toPx())).toFloat()
        repeat(4) { n -> val yy = height * n / 3; drawLine(outline.copy(alpha = .2f), Offset(left, yy), Offset(size.width - left, yy)) }
        var previous: Offset? = null; var previousGoal: Offset? = null
        r.series.forEachIndexed { index, p -> val v = points[index]; val x = left + width * index / maxOf(1, points.lastIndex); val gaps = when (metric) { "weight" -> p.hasWeightGaps; "sleep" -> p.hasSleepGaps; else -> p.hasNutritionGaps }
            if (v == null) previous = null else { val next = Offset(x, y(v)); if (!gaps) previous?.let { drawLine(primary, it, next, 2.dp.toPx()) }; drawCircle(primary, if (p.aggregated) 3.dp.toPx() else 4.dp.toPx(), next); previous = if (gaps) null else next }
            if (metric == "food") {
                if (showPartial) nutrient(p.partialNutritionMean)?.let { partial -> drawCircle(outline, 3.dp.toPx(), Offset(x, y(plotNumber(partial)))) }
                val goal = nutrient(p.goalMean)
                if (goal == null) previousGoal = null else { val next = Offset(x, y(plotNumber(goal))); if (p.goalDays.toLong() == p.calendarDays) previousGoal?.let { drawLine(outline, it, next, 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))) }; previousGoal = if (p.goalDays.toLong() == p.calendarDays) next else null }
            }
            if (index == selected) drawLine(primary.copy(alpha = .25f), Offset(x, 0f), Offset(x, height), 1.dp.toPx())
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Muted(r.period.start.toString()); Muted(r.period.endInclusive.toString()) }
    if (r.series.any { it.aggregated }) Muted("Точки — средние подписанных интервалов; пропуски не соединены.")
    r.series.getOrNull(selected)?.let { point ->
        val range = if (point.aggregated) "${dateLabel(point.start.toString())} — ${dateLabel(point.endInclusive.toString())}" else dateLabel(point.start.toString())
        DietCard(if (point.aggregated) "Среднее интервала" else "Выбранная дата", range, color = MaterialTheme.colorScheme.primaryContainer) {
            val n = when (metric) { "weight" -> point.weightDays; "sleep" -> point.sleepDays; else -> point.nutritionCompleteDays }
            Text(value(point)?.let { "${it.format(places)} $unit" } ?: "Нет данных для основного показателя")
            Muted("N=$n / M=${point.calendarDays}")
            if (metric == "food") { nutrient(point.partialNutritionMean)?.let { Text("Частично: ${it.format(1)} $unit · N=${point.nutritionPartialDays} (в основное среднее не входят)") }; nutrient(point.goalMean)?.let { Muted("Цель: ${it.format(1)} $unit · ${point.goalDays} дат с целью; календарное среднее интервала, отдельно от Q") } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { selected-- }, enabled = selected > 0) { Text("← Точка") }; TextButton(onClick = { selected++ }, enabled = selected < r.series.lastIndex) { Text("Точка →") } }
        }
    }
    if (metric == "food") Muted("Акцент — заполненное питание. Приглушённые точки — частичные дни, в среднее N не входят. Переключатель меняет только рисунок. Пунктир — цель на дату; в длинном интервале её календарное среднее, не сравнение Q.")
}

@Composable private fun DailyTable(r: ReportBundle, metric: String) {
    val h = LocalUi.current; var page by remember(r.period, metric) { mutableIntStateOf(0) }; var data by remember { mutableStateOf<ReportDayPage?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(r.period, page) { data = null; try { data = h.repository.pageReportDays(r.period.start, r.period.endInclusive, page * 40L, 40) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e) } }
    Text("Точные даты и значения", style = MaterialTheme.typography.titleMedium); ErrorNotice(error)
    data?.days?.forEach { row ->
        Column(Modifier.fillMaxWidth().clickable { h.date = row.date.toString(); h.navigate("home") }.padding(vertical = 10.dp)) {
            Text(dateLabel(row.date.toString()), style = MaterialTheme.typography.labelLarge)
            when (metric) {
                "weight" -> Text(row.weightCentiKg?.let { "${decimal(it, 2)} кг" } ?: "Нет измерения")
                "sleep" -> Text(row.sleepMinutes?.let(Numbers::formatSleepMinutes) ?: "Нет записи")
                "habits" -> Text("Питание: ${statusLabel(row.food.status)} · вес ${if (row.weightCentiKg == null) "—" else "есть"} · сон ${if (row.sleepMinutes == null) "—" else "есть"}")
                else -> { Text(statusLabel(row.food.status)); if (row.food.status != DayStatus.MISSING) NutritionText(row.food.nutrients, true); row.goal?.let { Muted("Цель ${it.kcal.format(1)} ккал") } }
            }
            Text("Открыть день →", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }; HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
    }
    PageControls(page, data?.hasMore == true, data == null) { page = it }
}
private fun statusLabel(status: DayStatus) = when (status) { DayStatus.MISSING -> "Нет данных"; DayStatus.PARTIAL -> "Частично заполнен"; DayStatus.COMPLETE -> "День заполнен"; DayStatus.CONFIRMED_ZERO -> "Подтверждено: еды не было" }

@Composable private fun MomentReport(report: ReportBundle) {
    val h = LocalUi.current; val period = report.period
    Text("${report.moments.photoCount} фото · ${report.moments.noteCount} заметок · ${report.moments.recordedDays} дат с моментами")
    report.moments.moodCounts.forEach { (mood, count) -> Text("${when (mood) { "great" -> "Отлично"; "good" -> "Хорошо"; "tired" -> "Усталость"; else -> "Обычно" }} · $count") }
    Muted("Личные моменты хранятся отдельно от питания и не участвуют в КБЖУ или игровых баллах.")
    Action("Открыть фотографии периода →", secondary = true) { h.context("photos.start", period.start.toString()); h.context("photos.end", period.endInclusive.toString()); h.context("photos.page", "0"); h.navigate("photo-feed") }
    Action("Открыть календарь заметок →", secondary = true) { h.navigate("calendar") }
}
