package app.diet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import java.time.LocalDate
import app.diet.domain.Numbers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.diet.data.*
import app.diet.domain.GameRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalResources

internal val HomeLabels = mapOf("meals" to "Приёмы пищи", "journal" to "Мой день", "measurements" to "Вес и сон")

@Composable internal fun SettingsScreen() {
    val h = LocalUi.current; val prefs = h.preferences
    when (h.route.name) {
        "sources" -> SourcesScreen()
        "drafts" -> DraftsScreen()
        "preference-resume" -> DirectPreferenceEditor()
        "appearance", "home-layout" -> PreferenceEditor(h.route.name)
        "game" -> {
            ScreenHeading("Игра в своём темпе", "Названия — образы диапазонов, а не оценка тела или качества сна.")
            DietCard { ToggleRow("Игровые названия веса и сна", prefs.gameNames) { h.preference("gameNames", mapOf("enabled" to it.toString())) }; ToggleRow("Уровни за заполнение дневника", prefs.gameLevels) { h.preference("gameLevels", mapOf("enabled" to it.toString())) }; Muted("Серия показывает привычку записывать. Нет штрафов за пропуски или наград за снижение веса и калорий.") }
            DietCard("Вес · просто названия") { listOf("До 60 кг — Искра", "60–<70 — Вихрь", "70–<80 — Метеор", "80–<90 — Шторм", "90–95 включительно — Машина", ">95–<100 — Титан", "100 кг и больше — Богатырь").forEach { Text(it) } }
            DietCard("Сон · просто названия") { listOf("Больше 0 и меньше 6 ч — Короткий привал", "6–<8 ч — Ловец снов", "8–<10 ч — Соня", "10 ч и больше — Хранитель подушки", "0 ч — без игрового названия").forEach { Text(it) } }
            DietCard("Растём за привычку записывать") { listOf(0L, 1, 2, 7, 14, 30, 60).forEach { count -> Text("${recordedDateCount(count)} · ${GameRules.level(count).name}") }; Muted("Одна заполненная дата считается один раз. Изменение полноты пересчитывает уровень.") }
        }
        "meals" -> {
            ScreenHeading("Мои приёмы пищи", "Свой порядок и названия. Архив сохраняет историю.")
            h.state.meals.forEachIndexed { index, meal -> DietCard(meal.name + if (meal.archived) " · архив" else "") {
                TextButton(onClick = { h.openForm("meal", meal.id) }) { Text("Изменить / архивировать") }
                Row { if (index > 0) TextButton(onClick = { reorderMeal(h, index, index - 1) }) { Text("↑ Выше") }; if (index < h.state.meals.lastIndex) TextButton(onClick = { reorderMeal(h, index, index + 1) }) { Text("↓ Ниже") } }
            } }; Action("＋ Добавить приём") { h.openForm("meal") }
        }
        "categories" -> {
            ScreenHeading("Категории библиотеки")
            h.state.categories.forEach { category -> DietCard(category.name) { TextButton(onClick = { h.openForm("category", category.id) }) { Text("Переименовать") }; DeleteLink("Удалить категорию") { h.confirm("Удалить категорию?", "Продукты и история останутся. В библиотеке у них будет «Без категории».", "Удалить") { h.command("delete-category", category.id, category.revision) } } } }; Action("＋ Создать категорию") { h.openForm("category") }
        }
        "reminders" -> {
            ScreenHeading("Мягкие напоминания", "Три повода заглянуть в дневник. Доставка зависит от разрешений и ограничений Android.")
            DietCard { Text(h.mediaState.notificationStatus); if (!h.mediaState.notificationEnabled) Action("Разрешить уведомления", secondary = true) { h.media.requestNotifications() }; TextButton(onClick = { h.media.openNotificationSettings() }) { Text("Системные настройки уведомлений") } }
            h.state.reminders.forEach { reminder -> DietCard(when (reminder.kind) { "food" -> "Питание"; "weight" -> "Утренний вес"; else -> "Сон" }) { Pill(if (reminder.enabled) "Запрошено около %02d:%02d".format(reminder.minuteOfDay / 60, reminder.minuteOfDay % 60) else "Выключено"); Text(notificationAvailability(h.mediaState, reminder.kind)); TextButton(onClick = { h.openForm("reminder", reminder.id) }) { Text("Время и дни →") } } }
        }
        else -> PersonalSettingsHome()
    }
}

/** Personal summary always uses today and the latest saved measurement, not a browsed past date. */
@Composable private fun PersonalSettingsHome() {
    val h = LocalUi.current
    val selectedToday = today()
    var day by remember(selectedToday) { mutableStateOf<ReportDay?>(null) }
    var latestWeight by remember { mutableStateOf<WeightEntity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val readOwner = remember { arrayOfNulls<Any>(1) }
    LaunchedEffect(selectedToday, h.state.dataRevision) {
        val token = Any(); readOwner[0] = token
        loading = true; error = null
        try {
            val date = LocalDate.parse(selectedToday)
            val nextDay = h.repository.pageReportDays(date, date, 0, 1).days.single()
            val nextWeight = h.repository.latestWeight(selectedToday)
            if (readOwner[0] === token) { day = nextDay; latestWeight = nextWeight }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (readOwner[0] === token) { day = null; latestWeight = null; error = app.diet.messages.userMessage(e, "Не удалось открыть ваши показатели") } }
        finally { if (readOwner[0] === token) loading = false }
    }
    ScreenLoad(loading)
    Text(h.state.profile?.name?.takeIf { it.isNotBlank() } ?: "Мой профиль", style = MaterialTheme.typography.headlineSmall)
    ErrorNotice(error)
    if (error != null) TextButton(onClick = { h.launch { h.repository.initialize() } }) { Text("Повторить чтение") }
    val nutrition = day?.food
    val actual = nutrition?.takeIf { it.entryCount > 0 || it.completed }?.nutrients?.kcal
    DietCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Сегодня", style = MaterialTheme.typography.titleMedium)
            Muted(dateLabel(selectedToday))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Muted("Нужно")
                Text(day?.goal?.kcal?.let { "${it.format(1)} ккал" } ?: if (loading) "— ккал" else "Цель не задана", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Muted("Внесено")
                Text(actual?.let { "${it.format(1)} ккал" } ?: "— ккал", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    DietCard {
        Text("Вес", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { h.date = selectedToday; h.openForm("weight") }, modifier = Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                    Muted("Мой вес")
                    Text(latestWeight?.let { "${decimal(it.centiKg.toLong(), 2)} кг" } ?: "Добавить", style = MaterialTheme.typography.titleLarge)
                    latestWeight?.let { Text(dateLabel(it.localDate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            TextButton(onClick = {
                if ("weightTarget" in h.preferences.unsupportedDraftGroups) {
                    h.error = "Черновик желаемого веса из новой версии. Откройте настройки для подробностей."
                } else {
                    h.preferenceForm = UiPreferenceSession.open(h, "weightTarget", mapOf("kg" to h.preferences.desiredWeightCentiKg?.let { decimal(it, 2) }.orEmpty()))
                    h.push(UiRoute("preference-resume"))
                }
            }, modifier = Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                    Muted("Желаемый вес")
                    Text(h.preferences.desiredWeightCentiKg?.let { "${decimal(it, 2)} кг" } ?: "Указать", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.End)
                }
            }
        }
    }
    DietCard(modifier = Modifier.clickable { h.navigate("goals") }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Мои калории и цели", style = MaterialTheme.typography.titleMedium)
                Muted(day?.goal?.let { "${it.kcal.format(1)} ккал · Б ${it.protein.format(1)} · Ж ${it.fat.format(1)} · У ${it.carbs.format(1)} г" } ?: "Указать свои значения")
            }
            Text("→", color = MaterialTheme.colorScheme.primary)
        }
    }
    CollapsiblePanel("Настройки", "Инструменты, оформление и приложение") { SettingsDetails() }
}

@Composable private fun SettingsDetails() {
    val h = LocalUi.current
    val prefs = h.preferences
    WidgetSettingsCard()
    DietCard("Записать · ${dateLabel(h.date)}") {
        Action("Утренний вес", secondary = true) { h.openForm("weight") }
        Action("Суммарный сон", secondary = true) { h.openForm("sleep") }
        Action("Фото, настроение и заметка", secondary = true) { h.openForm("moment") }
    }
    listOf("catalog" to "Библиотека продуктов и рецептов", "templates" to "Шаблоны приёмов пищи", "plans" to "План питания", "photo-feed" to "Фотолента еды", "search" to "Поиск по дневнику", "calendar" to "Календарь", "profile" to "Профиль", "goals" to "Ручные цели", "home-layout" to "Настраиваемая главная", "appearance" to "Акцент и обложка", "meals" to "Приёмы пищи", "categories" to "Категории", "game" to "Названия и уровни", "reminders" to "Напоминания", "drafts" to "Все черновики").forEach { (route, label) -> Action("$label  →", secondary = true) { h.navigate(route) } }
    DietCard("Как вам комфортнее") {
        Choices(listOf("system" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная"), prefs.theme) { h.preference("theme", mapOf("theme" to it)) }
        ToggleRow("Крупнее текст в приложении", prefs.largeText) { h.preference("font", mapOf("large" to it.toString())) }
        Muted("Системный размер текста и настройка движения также учитываются.")
    }
    if (prefs.drafts.isNotEmpty()) DietCard("Черновики настроек") {
        prefs.drafts.forEach { draft -> TextButton(onClick = {
            if (draft.group in listOf("home", "appearance")) h.navigate(if (draft.group == "home") "home-layout" else "appearance")
            else { h.preferenceForm = UiPreferenceSession.open(h, draft.group, emptyMap()); h.push(UiRoute("preference-resume")) }
        }) { Text("Продолжить: ${preferenceGroupLabel(draft.group)}") } }
    }
    prefs.unsupportedDrafts.forEach { draft -> DietCard("Черновик из новой версии", preferenceGroupLabel(draft.group)) {
        Muted("Эта группа настроек временно недоступна. Неизвестный черновик сохранён без изменений; остальные группы можно использовать.")
        DeleteLink("Удалить только этот черновик") { h.confirm("Удалить неизвестный черновик?", "Удалится только черновик «${preferenceGroupLabel(draft.group)}». Применённые настройки и дневник сохранятся. Операцию нельзя отменить.", "Удалить черновик") { h.launch { h.visual.discardUnsupportedDraft(draft.group, draft.rawFingerprint) } } }
    } }
    DietCard("На этом телефоне") {
        Muted("Офлайн · без аккаунта и рекламы. Личные записи и фотографии не отправляются на сервер. Удаление приложения с его данными удалит дневник; экспорта и восстановления нет.")
        Muted("Начальный справочник: USDA FoodData Central / SR Legacy April2018; справочные значения на 100 г. Свои продукты можно исправить по этикетке.")
        TextButton(onClick = { h.navigate("sources") }) { Text("Источники и лицензии →") }
    }
}

@Composable private fun SourcesScreen() {
    val resources = LocalResources.current; var text by remember { mutableStateOf<String?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resources) { try { text = withContext(Dispatchers.IO) { listOf(app.diet.R.raw.catalog_sources, app.diet.R.raw.unicode_license).joinToString("\n\n") { id -> resources.openRawResource(id).bufferedReader(Charsets.UTF_8).use { it.readText() } } } } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "Не удалось прочитать сведения об источниках" } }
    ScreenHeading("Источники и лицензии", "Локальная справка: подключение к сети не требуется."); ErrorNotice(error); text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable private fun DraftsScreen() {
    val h = LocalUi.current
    var page by remember { mutableIntStateOf(0) }; var refresh by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<DraftHeader>>(emptyList()) }; var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(true) }
    LaunchedEffect(page, refresh, h.state.drafts) { busy = true; try { items = h.repository.queryDraftHeaders(page * 20L, 20); error = null } catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e) } finally { busy = false } }
    ScreenHeading("Ваши черновики", "Сохранённые поля ещё не являются записью в дневнике. Исходы подтверждённых действий проверяются перед продолжением.")
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()); ErrorNotice(error)
    if (!busy && items.isEmpty()) EmptyState("Незаконченных форм нет", "Можно начать новую запись в дневнике.")
    items.forEach { header -> DietCard(formLabel(header.kind), header.selectedLocalDate?.let(::dateLabel)) {
        Action("Продолжить", secondary = true) { h.resume(header) }
        DeleteLink("Удалить черновик") { h.confirm("Удалить незаконченные изменения?", "Сохранённые факты не удаляются. Сначала приложение проверит исход прежнего подтверждения.", "Удалить черновик") { h.launch {
            val current = h.repository.freshDraft(header.id) ?: throw DataFailure.Missing()
            val committed = current.operationId?.let { h.repository.recover(it) }
            if (committed != null) h.message = "Это действие уже сохранено; подтверждённая запись не удалена"
            else if (current.status == "OPEN") { h.repository.discardDraft(current.id, current.draftRevision); h.message = "Черновик удалён" }
            refresh++
        } } }
    } }
    PageControls(page, items.size == 20, busy) { page = it }
}

@Composable private fun DirectPreferenceEditor() {
    val h = LocalUi.current; val s = h.preferenceForm ?: return
    val weightTarget = s.draft.group == "weightTarget"
    val validation = if (weightTarget) runCatching { Numbers.parseOptionalWeightCentiKg(s.fields["kg"].orEmpty()) }.exceptionOrNull()?.let { app.diet.messages.userMessage(it, "Проверьте вес") } else null
    ScreenHeading(if (weightTarget) "Желаемый вес" else "Черновик настройки", if (weightTarget) "Ваш личный ориентир. Пустое поле убирает цель; измерения в дневнике сохраняются." else "Проверьте значение перед повторным сохранением.")
    when (s.draft.group) {
        "weightTarget" -> {
            OutlinedTextField(s.fields["kg"].orEmpty(), { value ->
                if (value.codePointCount(0, value.length) <= 64) s.change("kg", value)
                else s.error = "Слишком длинное число. Предыдущее значение сохранено."
            }, Modifier.fillMaxWidth(), label = { Text("Вес, кг") }, singleLine = true, enabled = s.editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = validation != null)
            ErrorNotice(validation)
        }
        "theme" -> Choices(listOf("system" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная"), s.fields["theme"].orEmpty()) { s.change("theme", it) }
        "font" -> ToggleRow("Крупнее текст", s.fields["large"] == "true", !s.busy) { s.change("large", it.toString()) }
        "gameNames", "gameLevels" -> ToggleRow(if (s.draft.group == "gameNames") "Игровые названия" else "Уровни дневника", s.fields["enabled"] == "true", !s.busy) { s.change("enabled", it.toString()) }
        else -> s.fields.forEach { (key, value) -> Text("$key: $value") }
    }
    ErrorNotice(s.error); PreferenceRecovery(s)
    Action("Сохранить", enabled = s.editable && validation == null) { h.launch { s.busy = true; try { s.apply(); h.forgetPreference(s); h.preferenceForm = null; h.message = "Настройка сохранена"; h.pop() } catch (e: CancellationException) { throw e } catch (e: Exception) { s.error = app.diet.messages.userMessage(e) } finally { s.busy = false } } }; Action("Отмена", secondary = true, enabled = !s.busy && !s.uncertain) { h.back() }
}

private fun reorderMeal(h: UiHost, from: Int, to: Int) { val list = h.state.meals.toMutableList(); val item = list.removeAt(from); list.add(to, item); h.command("reorder-meals", fields = mapOf("order" to list.joinToString(";") { "${it.id}:${it.revision}" })) }

@Composable private fun PreferenceEditor(screen: String) {
    val h = LocalUi.current; val appearance = screen == "appearance"; val group = if (appearance) "appearance" else "home"
    if (group in h.preferences.unsupportedDraftGroups) { EmptyState("Черновик этой группы пока недоступен", "Файл сохранён. Можно обновить приложение или удалить только этот черновик в настройках."); return }
    LaunchedEffect(group) { h.preferenceForm = UiPreferenceSession.open(h, group, if (appearance) mapOf("accent" to h.preferences.accent, "cover" to h.preferences.cover) else mapOf("order" to h.preferences.homeOrder.joinToString(","), "hidden" to h.preferences.hiddenHome.joinToString(","))) }
    val session = h.preferenceForm ?: return
    LaunchedEffect(session.fields) { if (appearance) h.preferencePreview = session.fields }
    ScreenHeading(if (appearance) "Ваше оформление" else "Ваша главная", "Посмотрите изменения, затем сохраните или отмените.")
    if (appearance) {
        DietCard("Цветовой акцент") { Choices(listOf("fern" to "Лесной", "berry" to "Ягодный", "ocean" to "Морской"), session.fields["accent"].orEmpty()) { session.change("accent", it) } }
        DietCard("Обложка дневника") { Choices((0..5).map { it.toString() to "Обложка ${it + 1}" }, session.fields["cover"].orEmpty()) { session.change("cover", it) }; CoverArt(session.fields["cover"].orEmpty(), title = "В вашем стиле.") }
    } else {
        val order = session.fields["order"].orEmpty().split(','); val visible = order.filter { it in HomeLabels }; val hidden = session.fields["hidden"].orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
        visible.forEachIndexed { index, block -> DietCard(HomeLabels.getValue(block)) { ToggleRow("Показывать на главной", block !in hidden, !session.busy) { checked -> session.change("hidden", (if (checked) hidden - block else hidden + block).joinToString(",")) }; Row { if (index > 0) TextButton(onClick = { session.change("order", swapHomeBlocks(order, block, visible[index - 1]).joinToString(",")) }) { Text("↑ Выше") }; if (index < visible.lastIndex) TextButton(onClick = { session.change("order", swapHomeBlocks(order, block, visible[index + 1]).joinToString(",")) }) { Text("↓ Ниже") } } } }
        Muted("Настройка вида не удаляет записи. Все функции доступны через «Ещё → Настройки».")
    }
    ErrorNotice(session.error); Muted(session.message)
    PreferenceRecovery(session)
    Action(if (session.busy) "Сохраняем…" else "Сохранить", enabled = session.editable) { h.launch { session.busy = true; try { session.apply(); h.forgetPreference(session); h.preferenceForm = null; h.preferencePreview = null; h.message = "Настройки сохранены"; h.pop() } catch (e: CancellationException) { throw e } catch (e: Exception) { session.error = app.diet.messages.userMessage(e, "Не удалось сохранить настройки") } finally { session.busy = false } } }
    Action("Отмена", enabled = !session.busy && !session.uncertain, secondary = true) { h.back() }
}

/** Removed decorative blocks keep their persisted slots; reordering the remaining cards is lossless. */
internal fun swapHomeBlocks(order: List<String>, first: String, second: String): List<String> = order.map { when (it) { first -> second; second -> first; else -> it } }

@Composable internal fun PreferenceReadStatus() { val h = LocalUi.current; if (h.preferences.error != null) { ErrorNotice(h.preferences.error); Action("Повторить чтение настроек", secondary = true) { h.visual.retryObservation() } } }
internal fun notificationAvailability(state: UiMediaState, kind: String): String = when {
    !state.notificationEnabled -> "Показ уведомлений приложения запрещён в Android. Сохранённое желание получать их не меняется."
    state.notificationChannels[kind] == false -> "Канал этого вида выключен в Android. Другие виды могут работать."
    state.notificationChannels[kind] == true -> "Android разрешает этот вид. Доставка около выбранного времени зависит от энергосбережения."
    else -> "Проверяем доступность этого вида уведомлений."
}

@Composable private fun PreferenceRecovery(session: UiPreferenceSession) {
    val h = LocalUi.current
    if (session.uncertain && !session.busy) Action("Проверить результат сохранения") { h.launch { session.busy = true; try { if (session.recover()) { h.forgetPreference(session); h.preferenceForm = null; h.preferencePreview = null; h.message = "Ранее сохранённые настройки найдены; повторного применения нет"; h.pop() } else session.message = "Изменения ещё не применены. Проверьте их и сохраните." } catch (e: CancellationException) { throw e } catch (_: Exception) { /* The session retains the precise recovery/obsolete error. */ } finally { session.busy = false } } }
}
