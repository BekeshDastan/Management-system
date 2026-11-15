package app.diet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.diet.data.*
import app.diet.domain.*
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One metric and one chart at a time; all the detail remains reachable on demand. */
@Composable internal fun ReportsScreen() {
    val h = LocalUi.current
    val metricKey = "report.metric.${h.route.name}"
    val routeMetric = h.route.name.removePrefix("graph-").takeIf { h.route.name.startsWith("graph-") }
    val metric = (h.view[metricKey] ?: routeMetric ?: "food").takeIf { it in listOf("food", "weight", "sleep") } ?: "food"
    val end = h.view["report.end"] ?: h.date
    val length = h.view["report.length"]?.toLongOrNull()?.coerceIn(1, DomainDates.TOTAL_SUPPORTED_DAYS) ?: 7L
    val start = h.view["report.start"] ?: runCatching { maxOf(DomainDates.MIN, LocalDate.parse(end).minusDays(length - 1)).toString() }.getOrDefault(end)
    var result by remember { mutableStateOf<ReportBundle?>(null) }
    var previous by remember { mutableStateOf<ReportBundle?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val readOwner = remember { arrayOfNulls<Any>(1) }
    LaunchedEffect(start, end, h.state.dataRevision, retry) {
        val token = Any(); readOwner[0] = token
        // Keep one loaded result during a same-period refresh so the viewport does not
        // collapse. A failed read clears it instead of presenting stale totals as current.
        if (result?.period?.start?.toString() != start || result?.period?.endInclusive?.toString() != end) {
            result = null; previous = null
        }
        error = null; loading = true
        try {
            val first = DomainDates.parse(start)
            val last = DomainDates.validateFactDate(DomainDates.parse(end), LocalDate.now())
            val period = DatePeriod(first, last)
            val next = h.repository.readReport(first, last)
            val preceding = period.previous().period?.let { h.repository.readReport(it.start, it.endInclusive) }
            if (readOwner[0] === token) { result = next; previous = preceding }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (readOwner[0] === token) { result = null; previous = null; error = app.diet.messages.userMessage(e, "Не удалось посчитать отчёт") } }
        finally { if (readOwner[0] === token) loading = false }
    }
    ScreenLoad(loading)
    ScreenHeading("Мой отчёт", "Ваш ритм за выбранный период")
    ReportPeriod(start, end, length)
    Choices(listOf("food" to "Питание", "weight" to "Вес", "sleep" to "Сон"), metric) { h.context(metricKey, it) }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Обновление отчёта" })
    ErrorNotice(error)
    if (error != null) Action("Повторить расчёт", secondary = true) { retry++ }
    result?.let { r ->
        val component = h.view["report.component"] ?: "kcal"
        DietCard {
            if (metric == "food") NutrientChoice(component)
            MetricHeadline(r, metric, component)
            AccessibleChart(r, metric, showComponentChoice = false)
        }
        ReportPanel("details-$metric", "Подробнее", "${if (metric == "food") "Приёмы пищи, цели и " else ""}точные значения") {
            MetricSummary(r, previous, metric)
            if (metric == "food") {
                MealBreakdown(r, component)
                ComparisonCard(r)
                CollapsiblePanel("Часто в дневнике", "Фактические записи, включая неполные дни") {
                    if (r.topFoods.isEmpty()) Muted("Пока нет записей еды")
                    r.topFoods.forEach { Text("${it.name} · ${it.count} записей") }
                }
            }
            DailyTable(r, metric)
        }
        ReportPanel("habits", "Полнота дневника", "${r.nutrition.n} заполненных · ${r.nutrition.partialDays} неполных дней") {
            Text("Питание заполнено: ${r.nutrition.n}/${r.period.dayCount}")
            Text("Частично: ${r.nutrition.partialDays} · без данных: ${r.nutrition.missingDays}")
            Text("Измерения веса: ${r.weight.n} · записи сна: ${r.sleep.n}")
            Muted("Уровень растёт за заполненные даты. Вес и количество еды не меняют награду.")
            DailyTable(r, "habits")
        }
        ReportPanel("moments", "Личные моменты", "${r.moments.photoCount} фото · ${r.moments.noteCount} заметок") { MomentReport(r) }
        ReportPanel("method", "Как посчитано", "Пропуски и неполные дни") {
            Text("M=${r.period.dayCount} — календарные даты. N=${r.nutrition.n} — заполненные даты питания. Q=${r.nutrition.q} — заполненные даты с действующей целью.")
            Muted("Среднее питания включает только заполненные дни и подтверждённый ноль. Частичные дни показаны отдельно, пропуски не заменяются нулями. Вес и сон используют свои N; у сна подтверждённые 0 минут входят.")
            Muted("Точные исходные значения складываются до округления. Цели применяются по датам, сравнение целей использует одну и ту же выборку Q. Предыдущий период имеет ту же длительность; у нижней границы диапазон может быть короче.")
            if (r.series.any { it.aggregated }) Muted("Длинный период сгруппирован только для рисунка: столбик показывает среднее интервала и число записанных дней. Итоги считают всю историю; точные дни доступны страницами.")
        }
    }
}

@Composable private fun ReportPeriod(start: String, end: String, length: Long) {
    val h = LocalUi.current
    var menu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draftStart by rememberSaveable { mutableStateOf(start) }
    var draftEnd by rememberSaveable { mutableStateOf(end) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val period = runCatching { DatePeriod(DomainDates.parse(start), DomainDates.validateFactDate(DomainDates.parse(end), LocalDate.now())) }.getOrNull()
    fun choose(first: String, last: String, preset: String) {
        h.context("report.start", first); h.context("report.end", last); h.context("report.preset", preset)
    }
    val previous = period?.let { ReportPresentation.adjacent(it, -1, LocalDate.now()) }
    val next = period?.let { ReportPresentation.adjacent(it, 1, LocalDate.now()) }
    DietCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { previous?.let { choose(it.start.toString(), it.endInclusive.toString(), "custom") } }, enabled = previous != null,
                modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = "Предыдущий период" }) { Text("‹") }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Изменить период отчёта" }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(when (h.view["report.preset"] ?: length.toString()) { "7" -> "7 дней"; "30" -> "30 дней"; "90" -> "90 дней"; "month" -> "С начала месяца"; "year" -> "С начала года"; else -> "Свой период" })
                        Muted("${shortDate(start)} — ${shortDate(end)} ▾")
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf("7" to "7 дней", "30" to "30 дней", "90" to "90 дней", "month" to "С начала месяца", "year" to "С начала года", "custom" to "Свой период").forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            menu = false
                            if (value == "custom") { draftStart = start; draftEnd = end; editing = true; error = null }
                            else {
                                val last = runCatching { DomainDates.validateFactDate(DomainDates.parse(end), LocalDate.now()) }.getOrDefault(LocalDate.now())
                                choose(ReportPresentation.presetStart(value, last).toString(), last.toString(), value)
                                value.toLongOrNull()?.let { h.context("report.length", value) }; editing = false
                            }
                        })
                    }
                }
            }
            TextButton(onClick = { next?.let { choose(it.start.toString(), it.endInclusive.toString(), "custom") } }, enabled = next != null,
                modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = "Следующий период" }) { Text("›") }
        }
        ReportReveal(editing) {
            OutlinedTextField(draftStart, { draftStart = it; error = null }, Modifier.fillMaxWidth(), label = { Text("С ГГГГ-ММ-ДД") }, singleLine = true)
            OutlinedTextField(draftEnd, { draftEnd = it; error = null }, Modifier.fillMaxWidth(), label = { Text("По ГГГГ-ММ-ДД") }, singleLine = true)
            ErrorNotice(error)
            Action("Показать период") {
                runCatching { DatePeriod(DomainDates.parse(draftStart), DomainDates.validateFactDate(DomainDates.parse(draftEnd), LocalDate.now())) }
                    .fold({ choose(it.start.toString(), it.endInclusive.toString(), "custom"); editing = false }, { error = app.diet.messages.userMessage(it) })
            }
            TextButton(onClick = { editing = false; error = null }) { Text("Отмена") }
        }
    }
}

private fun shortDate(date: String): String = runCatching { DomainDates.parse(date).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru"))) }.getOrDefault(date)

@Composable internal fun CollapsiblePanel(title: String, caption: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    LaunchedEffect(initiallyOpen) { if (initiallyOpen) open = true }
    DietCard {
        PanelHeader(title, caption, open) { open = !open }
        ReportReveal(open, content)
    }
}

@Composable private fun ReportPanel(id: String, title: String, caption: String, content: @Composable ColumnScope.() -> Unit) {
    val h = LocalUi.current
    val open = id in h.view["report.open"].orEmpty().split(',')
    DietCard {
        PanelHeader(title, caption, open) {
            val values = h.view["report.open"].orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
            h.context("report.open", (if (open) values - id else values + id).joinToString(","))
        }
        ReportReveal(open, content)
    }
}

@Composable private fun PanelHeader(title: String, caption: String, open: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).semantics {
        role = Role.Button; stateDescription = if (open) "Развёрнуто" else "Свёрнуто"; heading()
    }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Muted(caption) }
        Text(if (open) "−" else "+", Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable private fun ReportReveal(open: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val duration = if (LocalReducedMotion.current) 0 else 180
    AnimatedVisibility(open, enter = fadeIn(tween(duration)) + expandVertically(tween(duration)), exit = fadeOut(tween(duration)) + shrinkVertically(tween(duration))) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun NutrientChoice(component: String) {
    val h = LocalUi.current
    Choices(listOf("kcal" to "Калории", "protein" to "Белки", "fat" to "Жиры", "carbs" to "Углеводы"), component) { h.context("report.component", it) }
}

private fun nutrient(value: Nutrients?, component: String): Rational? = when (component) {
    "protein" -> value?.protein; "fat" -> value?.fat; "carbs" -> value?.carbs; else -> value?.kcal
}
private fun metricValue(point: ReportSeriesPoint, metric: String, component: String): Rational? = when (metric) {
    "weight" -> point.weightMeanKg; "sleep" -> point.sleepMeanMinutes; else -> nutrient(point.nutritionMean, component)
}
private fun unit(metric: String, component: String) = when (metric) { "weight" -> "кг"; "sleep" -> "мин"; else -> if (component == "kcal") "ккал" else "г" }
private fun plottedValue(value: Rational, metric: String, component: String): String = if (metric == "sleep") Numbers.formatSleepMinutes(Numbers.checkedInt(value.roundedScaledInteger())) else "${value.format(if (metric == "weight") 2 else 1)} ${unit(metric, component)}"

@Composable private fun MetricHeadline(r: ReportBundle, metric: String, component: String) {
    val number = when (metric) {
        "weight" -> r.weight.meanKg?.let { plottedValue(it, metric, component) } ?: "Нет измерений веса"
        "sleep" -> r.sleep.roundedMeanMinutes?.let(Numbers::formatSleepMinutes) ?: "Нет записей сна"
        else -> nutrient(r.nutrition.mean, component)?.let { plottedValue(it, metric, component) } ?: "Пока нет заполненных дней"
    }
    Text(number, style = MaterialTheme.typography.headlineLarge)
    when (metric) {
        "weight" -> Muted("Средний вес · ${r.weight.n} из ${r.period.dayCount} дней")
        "sleep" -> Muted("Средний сон · ${r.sleep.n} из ${r.period.dayCount} дней")
        else -> Muted(if (r.nutrition.n == 0) "Заполнено 0 из ${r.period.dayCount} дней" else "Среднее · заполнено ${r.nutrition.n} из ${r.period.dayCount} дней")
    }
}

@Composable private fun MetricSummary(r: ReportBundle, previous: ReportBundle?, metric: String) {
    val component = LocalUi.current.view["report.component"] ?: "kcal"
    when (metric) {
        "weight" -> {
            Muted("Измерено ${r.weight.n} из ${r.period.dayCount} дней")
            r.weight.firstToLastChangeKg?.let { Text("От первого к последнему: ${it.format(2)} кг") }
            Difference(r.weight.meanKg, previous?.weight?.meanKg, "кг", 2)
        }
        "sleep" -> { Muted("Записано ${r.sleep.n} из ${r.period.dayCount} дней; подтверждённый 0 входит"); Difference(r.sleep.meanMinutes, previous?.sleep?.meanMinutes, "мин", 0) }
        else -> { Muted("Неполные дни и пропуски не входят в среднее. Подтверждённое отсутствие еды учитывается как ноль."); Difference(nutrient(r.nutrition.mean, component), nutrient(previous?.nutrition?.mean, component), unit(metric, component), 1) }
    }
}

@Composable private fun Difference(value: Rational?, previous: Rational?, unit: String, places: Int) {
    if (value == null || previous == null) Muted("Для сравнения с предыдущим периодом недостаточно данных")
    else Muted("К предыдущему периоду: ${(value - previous).format(places)} $unit")
}

@Composable private fun ComparisonCard(r: ReportBundle) {
    val c = r.nutrition.comparison
    if (c == null) Muted("Сравнения с целью пока нет: нет заполненных дней с целью")
    else {
        Text("Цели · ${r.nutrition.q} заполненных дней с целью", style = MaterialTheme.typography.titleMedium)
        Text("Факт ${c.actualMean.kcal.format(1)} · цель ${c.targetMean.kcal.format(1)} ккал")
        Text("Разница ${c.difference.kcal.format(1)} ккал · ${c.percentages.kcal?.format(1) ?: "—"}%")
        Muted("Б ${c.difference.protein.format(1)} · Ж ${c.difference.fat.format(1)} · У ${c.difference.carbs.format(1)} г — разница средних на той же выборке Q")
    }
}

private fun mealColor(index: Int) = listOf(Color(0xFF50A8BD), Color(0xFFDEA55E), Color(0xFFAC8BC9), Color(0xFF6FB49C), Color(0xFFD98591), Color(0xFF819CCE), Color(0xFF9B9B8B))[index.coerceIn(0, 6)]

@Composable private fun MealBreakdown(r: ReportBundle, component: String) {
    Text("По приёмам пищи", style = MaterialTheme.typography.titleMedium)
    Muted("Среднее по заполненным дням; неполные дни не входят")
    if (r.nutrition.n == 0) { Muted("Пока нет заполненных дней"); return }
    r.meals.forEach { meal ->
        val mean = nutrient(meal.completeTotal, component)!! / Rational.of(r.nutrition.n.toLong())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).background(mealColor(meal.colorIndex), RoundedCornerShape(3.dp)))
            Text(meal.name, Modifier.weight(1f))
            Text("${mean.format(1)} ${unit("food", component)}")
        }
    }
}

private fun plotNumber(r: Rational) = BigDecimal(r.numerator).divide(BigDecimal(r.denominator), MathContext.DECIMAL64).toDouble().also { require(it.isFinite()) { "Значение не помещается в координаты графика" } }

/** A zero-based bar axis never inflates weight changes or turns absence into a zero fact. */
@Composable internal fun AccessibleChart(r: ReportBundle, metric: String, showComponentChoice: Boolean = true) {
    val h = LocalUi.current
    val component = h.view["report.component"] ?: "kcal"
    val showPartial = h.view["report.partial"] != "false"
    if (metric == "food" && showComponentChoice) NutrientChoice(component)
    val selectedKey = "report.bar.$metric.${r.period.start}.${r.period.endInclusive}"
    var selected by rememberSaveable(selectedKey) { mutableIntStateOf(-1) }
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val values = r.series.map { metricValue(it, metric, component)?.let(::plotNumber) }
    val extra = if (metric == "food") r.series.flatMap { listOfNotNull(if (showPartial) nutrient(it.partialNutritionMean, component) else null, nutrient(it.goalMean, component)).map(::plotNumber) } else emptyList()
    val maxValue = ((values.filterNotNull() + extra).maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.08
    var animate by remember(r.period, metric, component, showPartial) { mutableStateOf(false) }
    LaunchedEffect(r.period, metric, component, showPartial) { animate = true }
    val reveal by animateFloatAsState(if (animate) 1f else 0f, tween(if (LocalReducedMotion.current) 0 else 240), label = "report bars")
    val label = when (metric) { "weight" -> "Столбчатый график веса"; "sleep" -> "Столбчатый график сна"; else -> "Столбчатый график питания" }
    val point = r.series.getOrNull(selected)
    val selectionDescription = point?.let { "${shortDate(it.start.toString())}: ${metricValue(it, metric, component)?.let { value -> plottedValue(value, metric, component) } ?: "нет заполненных данных"}" }.orEmpty()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Muted(if (metric == "sleep") "Длительность сна" else unit(metric, component))
        Muted("Нажмите на столбик")
    }
    Canvas(Modifier.fillMaxWidth().height(190.dp).testTag("report-bars")
        .pointerInput(r.period, metric, component, r.series.size) { detectTapGestures { tap ->
            ReportPresentation.selectedBar(tap.x - 6.dp.toPx(), size.width - 12.dp.toPx(), r.series.size)?.let { selected = it }
        } }
        .semantics {
            contentDescription = "$label. $selectionDescription"
            role = Role.Button
            onClick("Выбрать последний день") { selected = r.series.lastIndex; true }
            customActions = listOf(
                CustomAccessibilityAction("Предыдущий столбик") { if (selected > 0) { selected--; true } else false },
                CustomAccessibilityAction("Следующий столбик") { if (selected < r.series.lastIndex) { selected++; true } else false }
            )
        }) {
        val left = 6.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        val top = 8.dp.toPx()
        val plotHeight = bottom - top
        val slot = (size.width - left * 2) / r.series.size.coerceAtLeast(1)
        fun y(value: Double) = bottom - (value / maxValue * plotHeight * reveal).toFloat()
        repeat(4) { index ->
            val yy = bottom - plotHeight * index / 3
            drawLine(outline.copy(alpha = .16f), Offset(left, yy), Offset(size.width - left, yy))
        }
        r.series.forEachIndexed { index, row ->
            val x = left + slot * index
            val full = values[index]
            val partial = if (metric == "food" && showPartial) nutrient(row.partialNutritionMean, component)?.let(::plotNumber) else null
            val dual = full != null && partial != null
            val gap = minOf(6.dp.toPx(), slot * .25f)
            val baseWidth = (slot - gap).coerceAtLeast(.5f)
            val width = if (dual) baseWidth / 2 else baseWidth
            if (selected == index) drawRect(primary.copy(alpha = .09f), Offset(x, top), Size(slot, plotHeight))
            fun drawBar(value: Double, start: Float, segments: Map<String, Nutrients>, alpha: Float) {
                if (value == 0.0) {
                    drawLine(primary.copy(alpha = alpha), Offset(start, bottom), Offset(start + width, bottom), 3.dp.toPx())
                } else if (metric == "food" && component == "kcal" && segments.isNotEmpty()) {
                    var cumulative = 0.0
                    r.meals.forEach { meal ->
                        val segment = segments[meal.id]?.kcal?.let(::plotNumber) ?: 0.0
                        if (segment > 0) {
                            val next = cumulative + segment
                            drawRect(mealColor(meal.colorIndex).copy(alpha = alpha), Offset(start, y(next)), Size(width, (y(cumulative) - y(next)).coerceAtLeast(0f)))
                            cumulative = next
                        }
                    }
                } else drawRect(primary.copy(alpha = alpha), Offset(start, y(value)), Size(width, (bottom - y(value)).coerceAtLeast(0f)))
            }
            full?.let { drawBar(it, x + gap / 2, row.mealMeans, 1f) }
            partial?.let { drawBar(it, x + gap / 2 + if (dual) width else 0f, row.partialMealMeans, .42f) }
            if (full == null && partial == null) drawLine(outline.copy(alpha = .35f), Offset(x + slot * .4f, bottom), Offset(x + slot * .6f, bottom), 1.dp.toPx())
            if (metric == "food") nutrient(row.goalMean, component)?.let { goal ->
                val yy = y(plotNumber(goal))
                drawLine(outline, Offset(x, yy), Offset(x + slot, yy), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
            }
        }
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth.value
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (r.series.size <= 7 && availableWidth >= r.series.size * 39 * fontScale) {
                r.series.forEach { row -> Muted(row.start.format(DateTimeFormatter.ofPattern("EE d", Locale.forLanguageTag("ru")))) }
            } else {
                Muted(r.period.start.format(DateTimeFormatter.ofPattern("dd.MM.yy")))
                Muted(r.period.endInclusive.format(DateTimeFormatter.ofPattern("dd.MM.yy")))
            }
        }
    }
    if (metric == "food" && component == "kcal" && r.meals.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            r.meals.forEach { meal ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(8.dp).background(mealColor(meal.colorIndex), RoundedCornerShape(2.dp)))
                    Muted(meal.name)
                }
            }
        }
    }
    if (metric == "food") ToggleRow("Показывать частичные дни", showPartial) { h.context("report.partial", it.toString()) }
    if (values.all { it == null } && extra.isEmpty()) Muted(if (metric == "food" && r.nutrition.partialDays > 0 && !showPartial) "Неполные дни скрыты. Заполненных дней за период пока нет." else "Записей за этот период пока нет. Пропуски обозначены штрихом.")
    if (r.series.any { it.aggregated }) Muted("Столбики — средние за интервалы. Нажмите, чтобы увидеть даты и число учтённых дней.")
    if (metric == "food") Muted("Приглушённый столбик — неполный день, пунктир — цель. Пропуск — штрих; подтверждённый ноль — яркая отметка у основания.")
    ReportReveal(point != null) {
        point?.let { SelectedBar(r, it, metric, component, selected, { selected = it }, { selected = -1 }) }
    }
}

@Composable private fun SelectedBar(r: ReportBundle, point: ReportSeriesPoint, metric: String, component: String, selected: Int, select: (Int) -> Unit, close: () -> Unit) {
    val h = LocalUi.current
    val caption = if (point.aggregated) "${shortDate(point.start.toString())} — ${shortDate(point.endInclusive.toString())}" else dateLabel(point.start.toString())
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(caption, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = close, modifier = Modifier.semantics { contentDescription = "Закрыть выбранный столбик" }) { Text("×") }
            }
            Text(metricValue(point, metric, component)?.let { plottedValue(it, metric, component) } ?: "Нет данных для основного среднего")
            val n = when (metric) { "weight" -> point.weightDays; "sleep" -> point.sleepDays; else -> point.nutritionCompleteDays }
            Muted("Учтено $n из ${point.calendarDays} дней${if (point.aggregated) " · среднее интервала" else ""}")
            if (metric == "food") {
                nutrient(point.partialNutritionMean, component)?.let { Text("Частично: ${it.format(1)} ${unit(metric, component)} · неполных дней: ${point.nutritionPartialDays} (в основное среднее не входят)") }
                nutrient(point.goalMean, component)?.let { Muted("Цель: ${it.format(1)} ${unit(metric, component)} · ${point.goalDays} дат с целью${if (point.aggregated) "; среднее по календарным датам, отдельно от Q" else ""}") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { select(selected - 1) }, enabled = selected > 0) { Text("← День") }
                TextButton(onClick = { select(selected + 1) }, enabled = selected < r.series.lastIndex) { Text("День →") }
            }
            if (point.aggregated) Action("Открыть этот интервал", secondary = true) {
                h.context("report.start", point.start.toString()); h.context("report.end", point.endInclusive.toString()); h.context("report.preset", "custom")
            } else Action("Открыть день →", secondary = true) { h.date = point.start.toString(); h.navigate("home") }
        }
    }
}

@Composable private fun DailyTable(r: ReportBundle, metric: String) {
    val h = LocalUi.current
    var page by rememberSaveable(r.period.start.toString(), r.period.endInclusive.toString(), metric) { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<ReportDayPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(r.period, page) {
        data = null; error = null
        try { data = h.repository.pageReportDays(r.period.start, r.period.endInclusive, page * 40L, 40) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = app.diet.messages.userMessage(e) }
    }
    ScreenLoad(data == null && error == null)
    Text("Точные даты и значения", style = MaterialTheme.typography.titleMedium)
    ErrorNotice(error)
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
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
    }
    PageControls(page, data?.hasMore == true, data == null) { page = it }
}

private fun statusLabel(status: DayStatus) = when (status) { DayStatus.MISSING -> "Нет данных"; DayStatus.PARTIAL -> "Частично заполнен"; DayStatus.COMPLETE -> "День заполнен"; DayStatus.CONFIRMED_ZERO -> "Подтверждено: еды не было" }

@Composable private fun MomentReport(report: ReportBundle) {
    val h = LocalUi.current
    val period = report.period
    Text("${report.moments.photoCount} фото · ${report.moments.noteCount} заметок · ${report.moments.recordedDays} дат с моментами")
    report.moments.moodCounts.forEach { (mood, count) -> Text("${when (mood) { "great" -> "Отлично"; "good" -> "Хорошо"; "tired" -> "Усталость"; else -> "Обычно" }} · $count") }
    Muted("Личные моменты хранятся отдельно от питания и не участвуют в КБЖУ или игровых баллах.")
    Action("Открыть фотографии периода →", secondary = true) { h.context("photos.start", period.start.toString()); h.context("photos.end", period.endInclusive.toString()); h.context("photos.page", "0"); h.navigate("photo-feed") }
    Action("Открыть календарь заметок →", secondary = true) { h.navigate("calendar") }
}
