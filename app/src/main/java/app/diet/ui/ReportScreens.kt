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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.diet.data.*
import app.diet.domain.*
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Compact period → tabs → chart → meals → actual historical food rows. */
@Composable internal fun ReportsScreen() {
    val h = LocalUi.current
    val metricKey = "report.metric." + h.route.name
    val routeMetric = h.route.name.removePrefix("graph-").takeIf { h.route.name.startsWith("graph-") }
    val metric = (h.view[metricKey] ?: routeMetric ?: "food").takeIf { it in listOf("food", "weight", "sleep") } ?: "food"
    val end = h.view["report.end"] ?: h.date
    val length = h.view["report.length"]?.toLongOrNull()?.coerceIn(1, DomainDates.TOTAL_SUPPORTED_DAYS) ?: 7L
    val start = h.view["report.start"] ?: runCatching {
        if (h.view["report.length"] == null) ReportPresentation.presetStart("week", DomainDates.parse(end)).toString()
        else maxOf(DomainDates.MIN, DomainDates.parse(end).minusDays(length - 1)).toString()
    }.getOrDefault(end)
    var result by remember { mutableStateOf<ReportBundle?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val readOwner = remember { arrayOfNulls<Any>(1) }
    LaunchedEffect(start, end, h.state.dataRevision, retry) {
        val token = Any(); readOwner[0] = token
        if (result?.period?.start?.toString() != start || result?.period?.endInclusive?.toString() != end) result = null
        error = null; loading = true
        try {
            val next = h.repository.readReport(DomainDates.parse(start), DomainDates.validateFactDate(DomainDates.parse(end), LocalDate.now()))
            if (readOwner[0] === token) result = next
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (readOwner[0] === token) { result = null; error = app.diet.messages.userMessage(e, "Не удалось посчитать отчёт") } }
        finally { if (readOwner[0] === token) loading = false }
    }
    ScreenLoad(loading)
    ReportPeriod(start, end, length)
    FullWidthTabs(listOf("food" to "Питание", "weight" to "Вес", "sleep" to "Сон"), metric) { h.context(metricKey, it) }
    if (metric == "food") NutrientChoice(h.view["report.component"] ?: "kcal")
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Обновление отчёта" })
    ErrorNotice(error)
    if (error != null) Action("Повторить расчёт", secondary = true) { retry++ }
    result?.let { r ->
        val component = h.view["report.component"] ?: "kcal"
        DietCard {
            MetricHeadline(r, metric, component)
            AccessibleChart(r, metric, showComponentChoice = false)
            if (metric == "food") MealBreakdown(r, component)
        }
        if (metric == "food") ReportFoodTable(r) else DailyTable(r, metric)
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
    val preset = h.view["report.preset"] ?: if (h.view["report.length"] == null) "week" else length.toString()
    val calendarWeek = preset in listOf("week", "calendar-week")
    val previous = period?.let { if (calendarWeek) ReportPresentation.adjacentCalendarWeek(it, -1, LocalDate.now()) else ReportPresentation.adjacent(it, -1, LocalDate.now()) }
    val next = period?.let { if (calendarWeek) ReportPresentation.adjacentCalendarWeek(it, 1, LocalDate.now()) else ReportPresentation.adjacent(it, 1, LocalDate.now()) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { previous?.let { choose(it.start.toString(), it.endInclusive.toString(), if (calendarWeek) "calendar-week" else "custom") } }, enabled = previous != null,
                    modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = "Предыдущий период" }) { Text("‹") }
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Изменить период отчёта" }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(when (preset) {
                                "week", "calendar-week" -> if (end == today()) "Текущая неделя" else "Неделя"; "7" -> "7 дней"; "30" -> "30 дней"; "90" -> "90 дней"; "month" -> "С начала месяца"; "year" -> "С начала года"; else -> "Свой период"
                            })
                            Text(shortDate(start) + " — " + shortDate(end) + " ▾", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DropdownMenu(menu, { menu = false }) {
                        listOf("week" to "Текущая неделя", "7" to "7 дней", "30" to "30 дней", "90" to "90 дней", "month" to "С начала месяца", "year" to "С начала года", "custom" to "Свой период").forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                menu = false
                                if (value == "custom") { draftStart = start; draftEnd = end; editing = true; error = null }
                                else {
                                    val last = if (value == "week") LocalDate.now() else runCatching { DomainDates.validateFactDate(DomainDates.parse(end), LocalDate.now()) }.getOrDefault(LocalDate.now())
                                    choose(ReportPresentation.presetStart(value, last).toString(), last.toString(), value)
                                    value.toLongOrNull()?.let { h.context("report.length", value) }; editing = false
                                }
                            })
                        }
                    }
                }
                TextButton(onClick = { next?.let { choose(it.start.toString(), it.endInclusive.toString(), if (calendarWeek) "calendar-week" else "custom") } }, enabled = next != null,
                    modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = "Следующий период" }) { Text("›") }
            }
            ReportReveal(editing) {
                DateChoice("Начало периода", draftStart, DomainDates.MIN, LocalDate.now()) { draftStart = it; error = null }
                DateChoice("Конец периода", draftEnd, DomainDates.MIN, LocalDate.now()) { draftEnd = it; error = null }
                ErrorNotice(error)
                Action("Показать период") {
                    runCatching { DatePeriod(DomainDates.parse(draftStart), DomainDates.validateFactDate(DomainDates.parse(draftEnd), LocalDate.now())) }
                        .fold({ choose(it.start.toString(), it.endInclusive.toString(), "custom"); editing = false }, { error = app.diet.messages.userMessage(it) })
                }
                TextButton(onClick = { editing = false; error = null }) { Text("Отмена") }
            }
        }
    }
}

private fun shortDate(date: String): String = runCatching { DomainDates.parse(date).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru"))) }.getOrDefault(date)

@Composable internal fun CollapsiblePanel(title: String, caption: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    LaunchedEffect(initiallyOpen) { if (initiallyOpen) open = true }
    DietCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }.semantics {
            role = Role.Button; stateDescription = if (open) "Развёрнуто" else "Свёрнуто"; heading()
        }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); if (caption.isNotBlank()) Muted(caption) }
            Text(if (open) "−" else "+", Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.primary)
        }
        ReportReveal(open, content)
    }
}

@Composable private fun ReportReveal(open: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val duration = if (LocalReducedMotion.current) 0 else 180
    AnimatedVisibility(open, enter = fadeIn(tween(duration)) + expandVertically(tween(duration)), exit = fadeOut(tween(duration)) + shrinkVertically(tween(duration))) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable private fun NutrientChoice(component: String) {
    val h = LocalUi.current
    FullWidthTabs(listOf("kcal" to "Калории", "protein" to "Белки", "fat" to "Жиры", "carbs" to "Углеводы"), component) { h.context("report.component", it) }
}

private fun nutrient(value: Nutrients?, component: String) = ReportPresentation.nutrient(value, component)
private fun metricValue(point: ReportSeriesPoint, metric: String, component: String): Rational? = when (metric) {
    "weight" -> point.weightMeanKg; "sleep" -> point.sleepMeanMinutes; else -> nutrient(ReportPresentation.recordedMean(point), component)
}
private fun unit(metric: String, component: String) = when (metric) { "weight" -> "кг"; "sleep" -> "мин"; else -> if (component == "kcal") "ккал" else "г" }
private fun componentLabel(component: String) = when (component) { "protein" -> "Белки"; "fat" -> "Жиры"; "carbs" -> "Углеводы"; else -> "Калории" }
private fun plottedValue(value: Rational, metric: String, component: String): String = if (metric == "sleep") Numbers.formatSleepMinutes(Numbers.checkedInt(value.roundedScaledInteger())) else value.format(if (metric == "weight") 2 else 1) + " " + unit(metric, component)

@Composable private fun MetricHeadline(r: ReportBundle, metric: String, component: String) {
    val recordedCount = ReportPresentation.recordedDays(r.series)
    val actual = nutrient(ReportPresentation.recordedTotal(r.series), component)
    val mean = nutrient(ReportPresentation.recordedMean(r.series), component)
    val goal = nutrient(ReportPresentation.calendarGoalMean(r.series), component)
    val goalCaption = if (r.series.any { it.aggregated } || r.series.mapNotNull { nutrient(it.goalMean, component) }.distinct().size > 1) "Средняя цель: " else "Цель: "
    when (metric) {
        "weight" -> {
            Muted("Вес")
            Text(r.weight.meanKg?.let { plottedValue(it, metric, component) } ?: "— кг", style = MaterialTheme.typography.headlineLarge)
            Muted("Среднее · измерений " + r.weight.n + " из " + r.period.dayCount)
        }
        "sleep" -> {
            Muted("Сон")
            Text(r.sleep.roundedMeanMinutes?.let(Numbers::formatSleepMinutes) ?: "—", style = MaterialTheme.typography.headlineLarge)
            Muted("Среднее · записей " + r.sleep.n + " из " + r.period.dayCount)
        }
        else -> {
            Muted(componentLabel(component))
            Text(if (recordedCount == 0L) "— " + unit(metric, component) else plottedValue(requireNotNull(actual), metric, component), style = MaterialTheme.typography.headlineLarge)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Среднее за день: " + (mean?.format(1) ?: "—"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(goalCaption + (goal?.format(1) ?: "—") + " " + unit(metric, component), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Записано " + recordedCount + " из " + r.period.dayCount + " дней" +
                if (r.nutrition.partialDays > 0) " · не завершено: " + r.nutrition.partialDays else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun mealColor(index: Int) = listOf(Color(0xFFFFC247), Color(0xFF35B9E6), Color(0xFFFF9470), Color(0xFFB78AEC), Color(0xFF67C8AB), Color(0xFFFF78AD), Color(0xFF9CA3AF))[index.coerceIn(0, 6)]
private fun macroColor(component: String) = when (component) { "protein" -> Color(0xFF35B9E6); "fat" -> Color(0xFFFF9470); else -> Color(0xFFFFC247) }

@Composable private fun MealBreakdown(r: ReportBundle, component: String) {
    val h = LocalUi.current
    val rows = (r.meals + h.state.meals.take(6).mapIndexedNotNull { index, meal ->
        if (!meal.archived && r.meals.none { it.id == meal.id }) ReportMealSummary(meal.id, meal.name, Nutrients.ZERO, Nutrients.ZERO, index) else null
    }).sortedBy { it.colorIndex }
    val total = requireNotNull(nutrient(ReportPresentation.recordedTotal(r.series), component))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Приём пищи", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(unit("food", component), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    rows.forEach { meal ->
        val value = requireNotNull(nutrient(meal.completeTotal + meal.partialTotal, component))
        val percent = if (total.isZero) "—" else (value * Rational.of(100) / total).format(0) + "%"
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).background(mealColor(meal.colorIndex), RoundedCornerShape(1.dp)))
            Text(meal.name, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
            Text("(" + percent + ")", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.format(1), Modifier.widthIn(min = 56.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun plotNumber(r: Rational) = BigDecimal(r.numerator).divide(BigDecimal(r.denominator), MathContext.DECIMAL64).toDouble().also { require(it.isFinite()) { "Значение не помещается в координаты графика" } }

/** All recorded facts remain visible; missing dates never become zero-valued bars. */
@Composable internal fun AccessibleChart(r: ReportBundle, metric: String, showComponentChoice: Boolean = true) {
    val h = LocalUi.current
    val component = h.view["report.component"] ?: "kcal"
    if (metric == "food" && showComponentChoice) NutrientChoice(component)
    val selectedKey = "report.bar." + metric + "." + component + "." + r.period.start + "." + r.period.endInclusive
    var selected by rememberSaveable(selectedKey) { mutableIntStateOf(-1) }
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val goalColor = MaterialTheme.colorScheme.onSurface
    val macros = metric == "food" && component != "kcal"
    val values = r.series.map { metricValue(it, metric, component)?.let(::plotNumber) }
    val macroValues = if (macros) r.series.map { row -> listOf("protein", "fat", "carbs").map { nutrient(ReportPresentation.recordedMean(row), it)?.let(::plotNumber) } } else emptyList()
    val goals = if (metric == "food") ReportPresentation.goalRuns(r.series, component) else emptyList()
    val maxValue = ((values.filterNotNull() + macroValues.flatten().filterNotNull() + goals.map { plotNumber(it.value) }).maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.14
    var animate by remember(r.period, metric, component) { mutableStateOf(false) }
    LaunchedEffect(r.period, metric, component) { animate = true }
    val reveal by animateFloatAsState(if (animate) 1f else 0f, tween(if (LocalReducedMotion.current) 0 else 220), label = "report bars")
    val label = when (metric) { "weight" -> "Столбчатый график веса"; "sleep" -> "Столбчатый график сна"; else -> if (macros) "Столбчатый график БЖУ, граммы" else "Столбчатый график питания" }
    val point = r.series.getOrNull(selected)
    val selectionDescription = point?.let { shortDate(it.start.toString()) + ": " + (metricValue(it, metric, component)?.let { value -> plottedValue(value, metric, component) } ?: "нет данных") }.orEmpty()
    Canvas(Modifier.fillMaxWidth().height(172.dp).testTag("report-bars")
        .pointerInput(r.period, metric, component, r.series.size) { detectTapGestures { tap ->
            ReportPresentation.selectedBar(tap.x - 4.dp.toPx(), size.width - 8.dp.toPx(), r.series.size)?.let { selected = it }
        } }
        .semantics {
            contentDescription = label + ". " + selectionDescription
            role = Role.Button
            onClick("Выбрать последний день") { selected = r.series.lastIndex; true }
            customActions = listOf(
                CustomAccessibilityAction("Предыдущий столбик") { if (selected > 0) { selected--; true } else false },
                CustomAccessibilityAction("Следующий столбик") { if (selected < r.series.lastIndex) { selected++; true } else false }
            )
        }) {
        val left = 4.dp.toPx()
        val right = size.width - left
        val bottom = size.height - 4.dp.toPx()
        val top = 6.dp.toPx()
        val height = bottom - top
        val slot = (right - left) / r.series.size.coerceAtLeast(1)
        fun y(value: Double) = bottom - (value / maxValue * height * reveal).toFloat()
        repeat(4) { index ->
            val yy = bottom - height * index / 3
            drawLine(outline.copy(alpha = .65f), Offset(left, yy), Offset(right, yy))
        }
        r.series.forEachIndexed { index, row ->
            val x = left + slot * index
            val value = values[index]
            val gap = minOf(5.dp.toPx(), slot * .22f)
            val barWidth = (slot - gap).coerceAtLeast(.3f)
            if (selected == index) drawRect(primary.copy(alpha = .1f), Offset(x, top), Size(slot, height))
            if (value == null) {
                drawLine(outline, Offset(x + slot * .4f, bottom), Offset(x + slot * .6f, bottom), 1.dp.toPx())
            } else if (macros) {
                listOf("protein", "fat", "carbs").forEachIndexed { macroIndex, name ->
                    val v = macroValues[index][macroIndex] ?: 0.0
                    val w = barWidth / 3f
                    val start = x + gap / 2 + w * macroIndex
                    val color = macroColor(name)
                    if (v == 0.0) drawLine(color, Offset(start, bottom), Offset(start + w * .9f, bottom), 2.dp.toPx())
                    else drawRect(color, Offset(start, y(v)), Size(w * .9f, (bottom - y(v)).coerceAtLeast(0f)))
                }
            } else if (value == 0.0) {
                drawLine(primary, Offset(x + gap / 2, bottom), Offset(x + gap / 2 + barWidth, bottom), 3.dp.toPx())
            } else if (metric == "food") {
                val segments = ReportPresentation.recordedMealMeans(row)
                var cumulative = 0.0
                r.meals.forEach { meal ->
                    val amount = segments[meal.id]?.kcal?.let(::plotNumber) ?: 0.0
                    if (amount > 0) {
                        val next = cumulative + amount
                        drawRect(mealColor(meal.colorIndex), Offset(x + gap / 2, y(next)), Size(barWidth, (y(cumulative) - y(next)).coerceAtLeast(0f)))
                        cumulative = next
                    }
                }
            } else {
                val color = if (metric == "weight") Color(0xFFB78AEC) else Color(0xFF35B9E6)
                drawRect(color, Offset(x + gap / 2, y(value)), Size(barWidth, (bottom - y(value)).coerceAtLeast(0f)))
            }
        }
        // Targets are drawn last, across every slot including dates without food.
        // Equal adjacent targets form one continuous line; historical changes remain steps.
        goals.forEachIndexed { index, run ->
            val yy = y(plotNumber(run.value))
            val startX = left + slot * run.start
            val endX = left + slot * run.endExclusive
            drawLine(goalColor, Offset(startX, yy), Offset(endX, yy), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())))
            val next = goals.getOrNull(index + 1)
            if (next != null && run.endExclusive == next.start) {
                drawLine(goalColor, Offset(endX, yy), Offset(endX, y(plotNumber(next.value))), 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            }
        }
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth.value
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (r.series.size <= 7 && availableWidth >= r.series.size * 34 * fontScale) {
                r.series.forEach { row -> Text(row.start.format(DateTimeFormatter.ofPattern("EE d", Locale.forLanguageTag("ru"))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Text(shortDate(r.period.start.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(shortDate(r.period.endInclusive.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (macros) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("protein", "fat", "carbs").forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(8.dp).background(macroColor(name), RoundedCornerShape(1.dp)))
                Text(when (name) { "protein" -> "Б"; "fat" -> "Ж"; else -> "У" }, modifier = Modifier.semantics { contentDescription = componentLabel(name) + ", граммы" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (metric == "food" && goals.isNotEmpty()) Text("Пунктир — цель " + componentLabel(component).lowercase() + if (r.series.any { it.aggregated }) "; для интервала — среднее по датам" else " по датам", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (values.all { it == null }) Muted("Нет записей. Пропуски обозначены штрихом.")
    if (r.series.any { it.aggregated }) Text("Столбики — средние интервалов; нажмите для точных дат.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ReportReveal(point != null) {
        point?.let { SelectedBar(r, it, metric, component, selected, { selected = it }, { selected = -1 }) }
    }
}

@Composable private fun SelectedBar(r: ReportBundle, point: ReportSeriesPoint, metric: String, component: String, selected: Int, select: (Int) -> Unit, close: () -> Unit) {
    val h = LocalUi.current
    val caption = if (point.aggregated) shortDate(point.start.toString()) + " — " + shortDate(point.endInclusive.toString()) else dateLabel(point.start.toString())
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(caption, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = close, modifier = Modifier.semantics { contentDescription = "Закрыть выбранный столбик" }) { Text("×") }
            }
            Text(metricValue(point, metric, component)?.let { plottedValue(it, metric, component) } ?: "Нет данных")
            val n = when (metric) { "weight" -> point.weightDays; "sleep" -> point.sleepDays; else -> ReportPresentation.recordedDays(point) }
            Text("Записано " + n + " из " + point.calendarDays + " дней" + if (point.aggregated) " · среднее интервала" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (metric == "food") {
                if (point.nutritionPartialDays > 0) Text("Не завершено дней: " + point.nutritionPartialDays, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (component != "kcal") ReportPresentation.recordedMean(point)?.let { Text("Б " + it.protein.format(1) + " · Ж " + it.fat.format(1) + " · У " + it.carbs.format(1) + " г", style = MaterialTheme.typography.bodySmall) }
                nutrient(point.goalMean, component)?.let { Text("Цель: " + it.format(1) + " " + unit(metric, component), style = MaterialTheme.typography.bodySmall) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { select(selected - 1) }, enabled = selected > 0) { Text("← День") }
                TextButton(onClick = { select(selected + 1) }, enabled = selected < r.series.lastIndex) { Text("День →") }
            }
            TextButton(onClick = {
                if (point.aggregated) { h.context("report.start", point.start.toString()); h.context("report.end", point.endInclusive.toString()); h.context("report.preset", "custom") }
                else { h.date = point.start.toString(); h.navigate("home") }
            }) { Text(if (point.aggregated) "Открыть интервал →" else "Открыть день →") }
        }
    }
}

@Composable private fun ReportFoodTable(r: ReportBundle) {
    val h = LocalUi.current
    var page by rememberSaveable(r.period.start.toString(), r.period.endInclusive.toString()) { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<FoodEntryEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(r.period, page, h.state.dataRevision, retry) {
        loading = true; error = null
        try { rows = h.repository.queryHistory(r.period.start.toString(), r.period.endInclusive.toString(), offset = page * 40L, limit = 40) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { rows = emptyList(); error = app.diet.messages.userMessage(e, "Не удалось открыть съеденную пищу") }
        finally { loading = false }
    }
    ScreenLoad(loading)
    DietCard {
        Text("Съеденная пища", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Продукт", Modifier.weight(.54f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Кол-во", Modifier.weight(.23f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            Text("ккал", Modifier.weight(.23f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorNotice(error)
        if (error != null) TextButton(onClick = { retry++ }) { Text("Повторить чтение еды") }
        if (!loading && error == null && rows.isEmpty()) Muted("За этот период еды пока нет")
        rows.forEach { entry -> key(entry.id) {
            val quantity = if (entry.kind == "quick") "—" else decimal(requireNotNull(entry.quantityMicros)) + " " + unitLabel(requireNotNull(entry.snapshot).unit, entry.snapshot.portionName)
            Row(Modifier.fillMaxWidth().clickable { h.openForm("entry", entry.id) }.heightIn(min = 48.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(.54f)) {
                    Text(entry.displayName(), style = MaterialTheme.typography.bodyMedium)
                    if (r.period.dayCount > 1) Text(shortDate(entry.localDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(quantity, Modifier.weight(.23f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
                Text(entry.nutrients().kcal.format(1), Modifier.weight(.23f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        } }
        PageControls(page, rows.size == 40, loading) { page = it }
    }
}

@Composable private fun DailyTable(r: ReportBundle, metric: String) {
    val h = LocalUi.current
    var page by rememberSaveable(r.period.start.toString(), r.period.endInclusive.toString(), metric) { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<ReportDayPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(r.period, page, h.state.dataRevision, retry) {
        loading = true; error = null
        try { data = h.repository.pageReportDays(r.period.start, r.period.endInclusive, page * 40L, 40) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { data = null; error = app.diet.messages.userMessage(e) }
        finally { loading = false }
    }
    ScreenLoad(loading)
    DietCard {
        Row(Modifier.fillMaxWidth()) {
            Text("Дата", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (metric == "weight") "Вес" else "Сон", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorNotice(error)
        if (error != null) TextButton(onClick = { retry++ }) { Text("Повторить чтение дат") }
        data?.days?.forEach { row ->
            Row(Modifier.fillMaxWidth().clickable { h.date = row.date.toString(); h.navigate("home") }.heightIn(min = 48.dp).padding(vertical = 11.dp)) {
                Text(shortDate(row.date.toString()), Modifier.weight(1f))
                Text(if (metric == "weight") row.weightCentiKg?.let { decimal(it, 2) + " кг" } ?: "—"
                    else row.sleepMinutes?.let(Numbers::formatSleepMinutes) ?: "—")
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        PageControls(page, data?.hasMore == true, loading) { page = it }
    }
}
