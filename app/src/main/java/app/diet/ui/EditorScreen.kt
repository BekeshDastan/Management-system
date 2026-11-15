package app.diet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.diet.data.*
import app.diet.domain.*
import java.time.LocalDate

@Composable
internal fun EditorScreen() {
    val h = LocalUi.current; val s = h.form ?: return; val d = s.data; val kind = s.entity.kind
    val title = formLabel(kind)
    ScreenHeading(title, when (kind) { "weight" -> "Одна запись на дату. Любое значение — просто наблюдение."; "sleep" -> "Запишите общую длительность сна за выбранную дату."; "moment" -> "За кадром цифр. Без оценок и баллов."; else -> "Изменения появятся в дневнике после сохранения." })
    if (d["date"].isNotEmpty()) Pill(dateLabel(d["date"]))
    when (kind) {
        "profile", "onboarding-profile" -> {
            DietCard { Field(s, "name", "Как к вам обращаться", hint = "Можно оставить пустым"); Field(s, "age", "Возраст, лет", numeric = true); Field(s, "height", "Рост, см", numeric = true); Field(s, "weight", "Исходный вес, кг", numeric = true); Muted("Необязательные поля. Исходный вес профиля не создаёт измерение за день.") }
            DietCard("Ваше направление") { Choices(listOf("lose" to "Снизить вес", "maintain" to "Сохранить баланс", "gain" to "Набрать вес"), d["direction"]) { s.field("direction", it) }; Muted("Направление не рассчитывает рекомендации и не оценивает ваши записи.") }
        }
        "weight" -> {
            DietCard(color = LocalDietColors.current.peach) { Field(s, "weight", "Вес, кг", numeric = true, hint = "Например, 72,4"); runCatching { Numbers.parseWeightCentiKg(d["weight"]) }.getOrNull()?.let { if (h.preferences.gameNames) Pill(GameRules.weightName(it).orEmpty()) } }
            h.state.weights.find { it.localDate == d["date"] }?.let { old -> DeleteLink("Удалить измерение") { h.confirm("Удалить вес?", "Запись за эту дату исчезнет. Другие даты останутся.", "Удалить") { h.command("delete-weight", old.id, old.revision) } } }
        }
        "sleep" -> {
            DietCard(color = LocalDietColors.current.lavender) { Field(s, "hours", "Часы", numeric = true); Field(s, "minutes", "Минуты", numeric = true); ToggleRow("Подтверждаю: сна не было (0 ч 0 мин)", d["zero"] == "true", !s.busy) { s.field("zero", it.toString()) }; Muted("Пустая запись отличается от явно подтверждённого отсутствия сна.") }
            h.state.sleeps.find { it.localDate == d["date"] }?.let { old -> DeleteLink("Удалить запись сна") { h.confirm("Удалить сон?", "Эта дата снова будет без измерения.", "Удалить") { h.command("delete-sleep", old.id, old.revision) } } }
        }
        "goal", "onboarding-goal" -> GoalEditor(s)
        "product", "quick" -> {
            DietCard { Field(s, "name", if (kind == "product") "Название" else "Название (необязательно)"); if (kind == "product") { Choices(listOf("GRAM" to "100 г", "MILLILITRE" to "100 мл", "PORTION" to "1 порция"), d["unit"]) { s.field("unit", it) }; if (d["unit"] == "PORTION") Field(s, "portion", "Как называется порция"); CategoryChoice(s) }; MacroFields(s); Muted(if (kind == "product") "Пищевая ценность на выбранную базу. Нули допустимы, пропуски — нет." else "КБЖУ всей съеденной порции. В справочник продукт не добавляется.") }
            if (kind == "quick") MealChoice(s)
        }
        "portion", "entry" -> {
            if (kind == "entry" && d["quick"] == "true") DietCard { Field(s, "name", "Название"); MacroFields(s) }
            else {
                val line = d.lines.firstOrNull()
                if (line != null) DietCard(line.snapshot.name) {
                    NutritionText(line.snapshot.columns().toDomain().nutrientsPerBasis, true); Muted("На ${decimal(line.snapshot.basis)} ${unitLabel(line.snapshot.unit, line.snapshot.portion)} · исторический снимок")
                    QuantityField(s, line)
                    if (kind == "portion") {
                        val itemId = d["item"]; val group = h.state.quickPortionGroups.find { it.itemId == itemId && it.unit == line.snapshot.unit && it.basisMicros == line.snapshot.basis && it.unitLabel == line.snapshot.portion.orEmpty() }
                        val custom = h.state.quickPortions.filter { it.groupId == group?.id }.sortedBy { it.ordinal }.map { it.quantityMicros }
                        val values = custom.ifEmpty { when (line.snapshot.unit) { "GRAM" -> listOf(100_000_000L, 150_000_000L, 250_000_000L); "MILLILITRE" -> listOf(100_000_000L, 200_000_000L, 250_000_000L); else -> listOf(1_000_000L, 2_000_000L, 3_000_000L) } }
                        val selectedQuantity = runCatching { decimal(Numbers.parseQuantityMicros(line.quantity)) }.getOrDefault("")
                        Choices(values.map { decimal(it) to "${decimal(it)} ${unitLabel(line.snapshot.unit, line.snapshot.portion)}" }, selectedQuantity) { s.lineQuantity(line.id, it) }
                        if (itemId.isNotEmpty()) TextButton(enabled = s.editable, onClick = { h.launch { s.checkpoint(); h.form = null; h.root("catalog"); h.openForm("quick-portions", itemId) } }) { Text("Настроить быстрые порции") }
                    }
                    PreviewLines(d.lines)
                }
            }
            if (kind == "portion") MealChoice(s)
            if (kind == "entry") {
                Action("Явно сменить продукт / рецепт", secondary = true, enabled = s.editable) { h.push(UiRoute("replace-picker")) }
                if (d["replace"] == "true") Muted("Выбран новый самостоятельный снимок: ${d["replacementName"]}. Он заменит только эту запись после сохранения.")
                DeleteLink("Удалить запись") { h.confirm("Удалить еду за ${dateLabel(d["date"])}?", "Изменится только выбранный факт. Если это последняя строка дня, отметка «Питание заполнено» снимется; пустота не станет подтверждённым нулевым днём. Продукт останется в библиотеке.", "Удалить") { h.command("delete-food", s.entity.targetEntityId, s.entity.baseRevision) } }
            }
        }
        "batch-food" -> {
            MealChoice(s)
            Field(s, "date", "Дата ГГГГ-ММ-ДД")
            Action("＋ Выбрать продукты", secondary = true, enabled = s.editable) { h.push(UiRoute("batch-picker")) }
            if (d.lines.isEmpty()) EmptyState("Соберите приём пищи", "Выберите несколько продуктов, проверьте порции и добавьте всё одним действием.")
            var batchPage by rememberSaveable(s.entity.id) { mutableIntStateOf(0) }
            val visiblePage = minOf(batchPage, ((d.lines.size - 1).coerceAtLeast(0) / 20))
            d.lines.drop(visiblePage * 20).take(20).forEach { line -> key(line.id) { DietCard(line.snapshot.name) {
                QuantityField(s, line)
                NutritionText(runCatching { line.input().let { it.snapshot.toDomain().portion(it.quantityMicros) } }.getOrNull(), true)
                DeleteLink("Убрать из выбора") { s.removeLine(line.id) }
            } } }
            PageControls(visiblePage, (visiblePage + 1) * 20 < d.lines.size, s.busy) { batchPage = it }
            if (d.lines.isNotEmpty()) DietCard("Итого · ${d.lines.size}") { PreviewLines(d.lines) }
        }
        "move-entry", "repeat-entry" -> {
            DietCard(d["name"]) { PreviewLines(d.lines, showRows = true) }
            Field(s, "date", "Дата ГГГГ-ММ-ДД")
            MealChoice(s)
            Muted(if (kind == "move-entry") "Запись переместится с прежним количеством и пищевой ценностью." else "Добавится ещё одна такая порция. Исходная запись останется.")
        }
        "recipe", "template", "plan" -> {
            DietCard { Field(s, "name", "Название"); if (kind == "recipe") { Field(s, "yield", "Выход готового блюда, г", numeric = true); CategoryChoice(s); Muted("Ингредиенты — самостоятельные снимки. Вложенные рецепты не добавляются.") }; if (kind == "plan") { Field(s, "date", "Дата плана ГГГГ-ММ-ДД"); MealChoice(s); Muted("План можно создать на 90 дней вперёд. Съеденной еды пока не появится.") } }
            d.lines.forEach { line -> DietCard(line.snapshot.name) { QuantityField(s, line); NutritionText(runCatching { line.input().let { it.snapshot.toDomain().portion(it.quantityMicros) } }.getOrNull(), true); DeleteLink("Убрать из состава") { s.removeLine(line.id) } } }
            Action("＋ Добавить из библиотеки", secondary = true, enabled = s.editable) { h.push(UiRoute("line-picker")) }
            if (kind != "recipe") Action("＋ Добавить сохранённый шаблон", secondary = true, enabled = s.editable) { h.push(UiRoute("template-picker")) }
            DietCard("Предпросмотр") { PreviewLines(d.lines); if (kind == "recipe") runCatching { RecipeCalculation.calculate(d["name"], d.lines.map { RecipeIngredient(it.snapshot.columns().toDomain(), Numbers.parseQuantityMicros(it.quantity)) }, Numbers.parseQuantityMicros(d["yield"])) }.getOrNull()?.let { Muted("На 100 г готового блюда"); NutritionText(it.per100Grams, true) } }
        }
        "apply-template", "transfer" -> { DietCard(d["name"], "Строки добавятся к существующим. Фото и полнота дня не копируются.") { PreviewLines(d.lines, showRows = true) }; Field(s, "date", "Дата фактической еды ГГГГ-ММ-ДД"); MealChoice(s); if (kind == "transfer") TransferWarning(s); Muted("Это отдельное подтверждённое добавление. Намеренный повтор добавит новый набор.") }
        "copy" -> {
            DietCard("Откуда") { Field(s, "sourceDate", "Дата источника ГГГГ-ММ-ДД"); Choices(h.state.meals.map { it.id to (it.name + if (it.archived) " · архив" else "") }, d["sourceMeal"]) { s.field("sourceMeal", it) }; Action("Обновить предпросмотр", secondary = true) {
                h.launch { val rows = h.mealRows(d["sourceDate"], d["sourceMeal"]); if (s.data["sourceDate"] != d["sourceDate"] || s.data["sourceMeal"] != d["sourceMeal"]) return@launch; s.replaceLines(rows.map { UiLine.from(h.repository.foodAsLine(it)) }, mapOf("sourceVersions" to rows.joinToString(";") { "${it.id}:${it.revision}" }, "loadedDate" to d["sourceDate"], "loadedMeal" to d["sourceMeal"])) }
            } }
            DietCard("Куда") { Field(s, "date", "Дата назначения ГГГГ-ММ-ДД"); MealChoice(s); PreviewLines(d.lines) }
        }
        "moment" -> MomentEditor(s)
        "meal" -> DietCard { Field(s, "name", "Название приёма"); ToggleRow("В архиве", d["archived"] == "true", !s.busy) { s.field("archived", it.toString()) }; Muted("Исторические записи остаются привязаны к тому же приёму. Последний активный приём нельзя архивировать.") }
        "category" -> DietCard { Field(s, "name", "Название категории") }
        "quick-portions" -> { DietCard(d["name"], "От одного до пяти количеств. Пустые ячейки пропускаются; одинаковые и неположительные значения не сохраняются.") { (0..4).forEach { Field(s, "amount$it", "Порция ${it + 1}", numeric = true) } }; Action("Вернуть стандартные порции", secondary = true) { h.confirm("Вернуть стандартные порции?", "Сохранённые количества этого продукта будут удалены. История не изменится.", "Вернуть") { h.command("reset-portions", s.entity.targetEntityId, s.entity.baseRevision) } } }
        "reminder" -> {
            DietCard { ToggleRow("Хочу получать напоминание", d["enabled"] == "true", !s.busy) { s.field("enabled", it.toString()) }; Field(s, "time", "Местное время ЧЧ:ММ"); WeekdayPicker(d["days"].toIntOrNull() ?: 0) { s.field("days", it.toString()) }; Muted("Около выбранного времени. Android может задержать уведомление при энергосбережении.") }
            DietCard("Доступность уведомлений") { Text(notificationAvailability(h.mediaState, d["kind"])); if (!h.mediaState.notificationEnabled) Action("Разрешить уведомления", secondary = true) { h.media.requestNotifications() }; TextButton(onClick = { h.media.openNotificationSettings() }) { Text("Открыть системные настройки") }; Muted("Предпросмотр: «${app.diet.messages.ReminderMessages.title(d["kind"])}» · ${app.diet.messages.ReminderMessages.BODY}") }
        }
        "command", "onboarding-skip" -> DietCard { Muted(if (s.busy) "Сохраняем подтверждённое действие…" else "Действие не завершено. Можно безопасно повторить с тем же ID операции.") }
    }
    ErrorNotice(s.error)
    val validation = formValidation(s)
    if (validation != null && s.dirty) ErrorNotice(validation)
    Muted(s.checkpointMessage)
}

/** Pinned by the host outside the form's scroll; safe even for a long meal or recipe. */
@Composable internal fun EditorActions() {
    val h = LocalUi.current
    val s = h.form ?: return
    val d = s.data
    val kind = s.entity.kind
    val validation = formValidation(s)
    val mediaBusy = h.mediaState.busy && h.mediaState.draftId == s.entity.id
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            if (s.uncertain && !s.busy) Action("Проверить результат сохранения") { h.checkFormOutcome() }
            else Action(if (s.busy || mediaBusy) "Сохраняем…" else when (kind) {
                "portion", "quick", "batch-food" -> "Добавить в дневник"
                "move-entry" -> "Переместить запись"
                "repeat-entry" -> "Добавить повторно"
                "transfer", "apply-template", "copy" -> "Подтвердить добавление"
                "onboarding-profile" -> "Далее"
                "onboarding-goal" -> if (d["manualAfterOnboarding"] == "true") "Сохранить ручную цель" else "Сохранить и начать"
                "command" -> "Повторить действие"
                else -> "Сохранить"
            }, enabled = s.editable && !mediaBusy && h.pendingPhotoRemoval == null && validation == null) { h.saveForm() }
        }
    }
}

@Composable internal fun Field(s: UiFormSession, key: String, label: String, numeric: Boolean = false, hint: String? = null, multiline: Boolean = false) {
    OutlinedTextField(s.data[key], { if (numeric) s.numericField(key, it) else s.field(key, it) }, Modifier.fillMaxWidth(), enabled = s.editable, label = { Text(label) }, placeholder = hint?.let { { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text), singleLine = !multiline, minLines = if (multiline) 3 else 1, shape = MaterialTheme.shapes.small)
}
@Composable internal fun MacroFields(s: UiFormSession) { listOf("kcal" to "Калории, ккал", "protein" to "Белки, г", "fat" to "Жиры, г", "carbs" to "Углеводы, г").forEach { (key, label) -> Field(s, key, label, numeric = true) } }
@Composable internal fun QuantityField(s: UiFormSession, l: UiLine) { OutlinedTextField(l.quantity, { s.lineQuantity(l.id, it) }, Modifier.fillMaxWidth(), enabled = s.editable, label = { Text("Количество, ${unitLabel(l.snapshot.unit, l.snapshot.portion)}") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = MaterialTheme.shapes.small) }
@Composable internal fun MealChoice(s: UiFormSession, key: String = "meal") { val h = LocalUi.current; Text("Приём пищи", style = MaterialTheme.typography.labelLarge); Choices(h.activeMeals.map { it.id to it.name }, s.data[key]) { s.field(key, it) } }
@Composable internal fun CategoryChoice(s: UiFormSession) { val h = LocalUi.current; if (h.state.categories.isNotEmpty()) { Text("Категория", style = MaterialTheme.typography.labelLarge); Choices(listOf("" to "Без категории") + h.state.categories.map { it.id to it.name }, s.data["category"]) { s.field("category", it) } } }
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun Choices(values: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) { val h = LocalUi.current; val enabled = h.form?.editable != false && h.preferenceForm?.editable != false; FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { values.forEach { (id, name) -> FilterChip(selected == id, { onSelect(id) }, label = { Text(name) }, modifier = Modifier.heightIn(min = 48.dp), enabled = enabled) } } }
@Composable internal fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onToggle: (Boolean) -> Unit) { val h = LocalUi.current; val active = enabled && h.form?.editable != false && h.preferenceForm?.editable != false; Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = active) { onToggle(!checked) }, horizontalArrangement = Arrangement.spacedBy(14.dp)) { Text(label, Modifier.weight(1f).padding(vertical = 12.dp)); Switch(checked, onCheckedChange = onToggle, enabled = active) } }
@Composable internal fun DeleteLink(text: String, onClick: () -> Unit) { val h = LocalUi.current; TextButton(onClick, enabled = h.form?.editable != false && h.preferenceForm?.editable != false, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(text) } }
@Composable internal fun PreviewLines(lines: List<UiLine>, showRows: Boolean = false) {
    var page by remember(lines) { mutableIntStateOf(0) }
    if (showRows) { lines.drop(page * 20).take(20).forEach { line -> Text(line.snapshot.name, style = MaterialTheme.typography.titleMedium); Muted("${line.quantity} ${unitLabel(line.snapshot.unit, line.snapshot.portion)}"); runCatching { line.input().let { it.snapshot.toDomain().portion(it.quantityMicros) } }.getOrNull()?.let { NutritionText(it, true) } }; PageControls(page, (page + 1) * 20 < lines.size, false) { page = it } }
    val result = runCatching { require(lines.isNotEmpty()) { "Добавьте хотя бы одну строку" }; Nutrients.sum(lines.map { it.input().let { i -> i.snapshot.toDomain().portion(i.quantityMicros) } }) }
    result.fold({ NutritionText(it) }, { ErrorNotice(app.diet.messages.userMessage(it, "Проверьте состав")) })
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun WeekdayPicker(mask: Int, change: (Int) -> Unit) { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEachIndexed { index, label -> FilterChip(mask and (1 shl index) != 0, { change(mask xor (1 shl index)) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp)) } } }

@Composable private fun MomentEditor(s: UiFormSession) {
    val h = LocalUi.current; val d = s.data
    DietCard("Как прошёл день?") { Choices(listOf("great" to "✦ Отлично", "good" to "☀ Хорошо", "neutral" to "◌ Обычно", "tired" to "☾ Усталость"), d["mood"]) { s.field("mood", if (d["mood"] == it) "" else it) }; Field(s, "note", "Пара слов для себя", multiline = true); Muted("До 500 знаков; настроение не даёт игровых баллов.") }
    DietCard("Моменты дня", "До четырёх фотографий для вашего дневника.") {
        d.photos.forEach { photo ->
            h.media.Thumbnail(photo.assetId, Modifier.fillMaxWidth().height(220.dp), photo.caption.ifBlank { "Фото дня" })
            OutlinedTextField(photo.caption, { s.photo(photo.id, caption = it) }, Modifier.fillMaxWidth(), label = { Text("Подпись к фото") }, enabled = s.editable, shape = MaterialTheme.shapes.small)
            Choices(listOf("" to "Момент дня") + h.state.meals.map { it.id to (it.name + if (it.archived) " · архив" else "") }, photo.mealId.orEmpty()) { s.photo(photo.id, mealId = it.ifEmpty { null }, changeMeal = true) }
            DeleteLink("Убрать фото") { h.queuePhotoRemoval(s, photo.assetId) }
        }
        val importing = h.mediaState.busy && h.mediaState.draftId == s.entity.id
        Action(if (importing) "Подготавливаем фото…" else "＋ Добавить фото (${d.photos.size}/4)", enabled = s.editable && !importing && d.photos.size < 4, secondary = true) { h.launch { val checkpoint = s.checkpoint(); h.media.clearError(); h.media.pickPhotos(checkpoint.id, d["date"], 4 - d.photos.size) } }
        if (importing) TextButton(onClick = { h.media.cancelImport(s.entity.id) }) { Text("Отменить добавление фото") }
        if (h.mediaState.draftId == s.entity.id) ErrorNotice(h.mediaState.error)
        CollapsiblePanel("О фотографиях", "Форматы и хранение") {
            Muted("JPEG, PNG или WebP до 5 МиБ. Сохраняется оптимизированная локальная JPEG-копия; у анимированного WebP — первый кадр. Оригинал не архивируется. По фотографии ничего не рассчитывается; еду добавляйте отдельно.")
        }
    }
}

internal fun formValidation(s: UiFormSession): String? = runCatching {
    val d = s.data
    when (s.entity.kind) {
        "profile", "onboarding-profile" -> { TextRules.optionalName(d["name"], 40); Numbers.parseOptionalAge(d["age"]); Numbers.parseOptionalHeightCentiCm(d["height"]); Numbers.parseOptionalWeightCentiKg(d["weight"]) }
        "weight" -> Numbers.parseWeightCentiKg(d["weight"])
        "sleep" -> Numbers.parseSleepMinutes(d["hours"], d["minutes"], d["zero"] == "true")
        "goal", "onboarding-goal" -> { DomainDates.parse(d["date"]); microNutrients(d, true); goalConfirmationError(s, s.repository.state.value)?.let { require(false) { it } } }
        "product" -> { TextRules.name(d["name"]); if (d["unit"] == "PORTION") TextRules.name(d["portion"], 30); microNutrients(d) }
        "quick" -> { TextRules.optionalName(d["name"], 80); microNutrients(d) }
        "entry" -> if (d["quick"] == "true") microNutrients(d) else d.lines.single().input()
        "batch-food" -> { DomainDates.validateFactDate(DomainDates.parse(d["date"]), LocalDate.now()); require(s.repository.state.value.meals.any { it.id == d["meal"] && !it.archived }) { "Выберите активный приём пищи" }; Numbers.validateOperationRows(d.lines.size); d.lines.forEach { it.input() } }
        "move-entry", "repeat-entry" -> { DomainDates.validateFactDate(DomainDates.parse(d["date"]), LocalDate.now()); require(s.repository.state.value.meals.any { it.id == d["meal"] && !it.archived }) { "Выберите активный приём пищи" } }
        "portion" -> { require(d["meal"].isNotBlank()) { "Выберите приём" }; d.lines.single().input() }
        "recipe", "template", "plan" -> { TextRules.name(d["name"]); Numbers.validateOperationRows(d.lines.size); d.lines.forEach { it.input() }; if (s.entity.kind == "recipe") Numbers.parseQuantityMicros(d["yield"]); if (s.entity.kind == "plan") DomainDates.validatePlanDate(DomainDates.parse(d["date"]), LocalDate.now()) }
        "copy" -> { DomainDates.validateFactDate(DomainDates.parse(d["sourceDate"]), LocalDate.now()); DomainDates.validateFactDate(DomainDates.parse(d["date"]), LocalDate.now()); require(d["loadedDate"] == d["sourceDate"] && d["loadedMeal"] == d["sourceMeal"] && d.lines.isNotEmpty()) { "Обновите непустой предпросмотр" } }
        "transfer", "apply-template" -> { DomainDates.validateFactDate(DomainDates.parse(d["date"]), LocalDate.now()); require(d.lines.isNotEmpty() && d["meal"].isNotEmpty()) { "Проверьте состав и приём" } }
        "moment" -> { TextRules.note(d["note"]); d.photos.forEach { TextRules.caption(it.caption) }; require(d.photos.size <= 4) }
        "meal" -> TextRules.name(d["name"], 40)
        "category" -> TextRules.name(d["name"], 40)
        "quick-portions" -> { val values = (0..4).mapNotNull { d["amount$it"].takeIf(String::isNotBlank)?.let(Numbers::parseQuantityMicros) }; require(values.size in 1..5 && values.toSet().size == values.size) { "Нужны от 1 до 5 разных положительных количеств" } }
        "reminder" -> { require(d["time"].matches(Regex("([01][0-9]|2[0-3]):[0-5][0-9]"))) { "Укажите время ЧЧ:ММ" }; require((d["days"].toIntOrNull() ?: 0) in 1..127) { "Выберите хотя бы один день" } }
    }
}.exceptionOrNull()?.let { app.diet.messages.userMessage(it, "Проверьте поля") }

internal fun goalConfirmationError(s: UiFormSession, state: DietState, now: LocalDate = LocalDate.now()): String? {
    val d = s.data
    if (s.entity.kind == "onboarding-goal" && state.onboarding?.completed == true && d["manualAfterOnboarding"] != "true") return "Первый запуск завершён. Сначала преобразуйте черновик в ручную цель."
    if (s.entity.kind == "onboarding-goal" && d["manualAfterOnboarding"] != "true" && (d["initialToday"].ifEmpty { d["date"] }) != now.toString() && d["dateConfirmedToday"] != now.toString()) return "Наступила другая дата. Явно подтвердите дату начала цели."
    if (d["goalPreviewDate"] != d["date"] || d["timeline"].toLongOrNull() != state.goalTimelineRevision) return "Обновите предпросмотр периода цели."
    if (d["goalReplacementConfirmed"] != "true") return "Подтвердите замену цели на занятой дате."
    return null
}

@Composable private fun GoalEditor(s: UiFormSession) {
    val h = LocalUi.current; val d = s.data
    DietCard("По вашим значениям", "Автоматические рекомендации ещё не предлагаются. Можно пользоваться дневником без цели.") { if (s.entity.targetEntityId == null) Field(s, "date", "Действует с даты ГГГГ-ММ-ДД"); MacroFields(s); Muted("Существующие снимки еды не изменятся. В отчёте цель применяется к датам её действия.") }
    if (s.entity.kind == "onboarding-goal" && h.state.onboarding?.completed == true && d["manualAfterOnboarding"] != "true") DietCard("Черновик после первого запуска") { Muted("Вы начали дневник без цели. Проверим результат прежней операции, затем сохраним эти значения как самостоятельную ручную цель."); Action("Преобразовать в ручную цель", secondary = true, enabled = s.editable) { h.convertDeferredGoal() } }
    if (s.entity.kind == "onboarding-goal" && d["manualAfterOnboarding"] != "true" && d["initialToday"].ifEmpty { d["date"] } != today() && d["dateConfirmedToday"] != today()) DietCard("Наступил новый день") {
        Muted("В черновике стоит ${dateLabel(d["date"])}. Выберите дату начала явно.")
        Action("Использовать сегодня", secondary = true) { s.fields(mapOf("date" to today(), "dateConfirmedToday" to today(), "goalPreviewDate" to "")) }
        Action("Оставить ${dateLabel(d["date"])}", secondary = true) { s.field("dateConfirmedToday", today()) }
    }
    val next = h.state.goals.filter { it.startDate > d["date"] }.minByOrNull { it.startDate }
    DietCard("Предпросмотр периода") {
        Text("С ${dateLabel(d["date"])}" + (next?.let { " до ${dateLabel(LocalDate.parse(it.startDate).minusDays(1).toString())}" } ?: " и далее"))
        goalConfirmationError(s, h.state)?.let { Muted(it) }
        Action("Обновить предпросмотр периода", secondary = true, enabled = s.editable) { refreshGoalPreview(h, s) }
        Muted("При изменении цепочки целей понадобится новый предпросмотр и подтверждение.")
    }
}

internal fun refreshGoalPreview(h: UiHost, s: UiFormSession) {
    val date = try { DomainDates.parse(s.data["date"]).toString() } catch (e: Exception) { s.error = app.diet.messages.userMessage(e, "Проверьте дату цели"); return }
    val state = h.state; val old = state.goals.find { it.startDate == date }
    val apply = { s.fields(mapOf("goalPreviewDate" to date, "goalBaseRevision" to old?.revision?.toString().orEmpty(), "timeline" to state.goalTimelineRevision.toString(), "goalReplacementConfirmed" to "true")); Unit }
    if (old != null && s.entity.targetEntityId != old.id) h.confirm("На этой дате уже есть цель", "Новая цель заменит значения с ${dateLabel(date)} до следующей даты изменения цели. Исторические записи еды сохранятся.", "Заменить цель на этой дате", apply) else apply()
}

@Composable private fun TransferWarning(s: UiFormSession) {
    val h = LocalUi.current; var last by remember(s.entity.targetEntityId) { mutableStateOf<TransferEntity?>(null) }; var loaded by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.entity.targetEntityId, h.state.dataRevision) { try { last = h.repository.queryTransfers(requireNotNull(s.entity.targetEntityId), 0, 1).firstOrNull(); loaded = true } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e, "Не удалось проверить историю переносов") } }
    ErrorNotice(error)
    if (loaded) last?.let { DietCard("Этот план уже был фактом") { Text("Последний перенос: ${dateLabel(it.localDate)}"); Muted("При сохранении понадобится отдельное подтверждение повтора. Прежняя еда не заменится.") } } ?: Muted("План ещё не переносили в дневник.")
}
