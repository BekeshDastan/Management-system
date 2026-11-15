package app.diet.ui

import androidx.compose.foundation.layout.*
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

internal val HomeLabels = mapOf("hero" to "Обложка", "nutrition" to "Питание и полнота дня", "meals" to "Приёмы пищи", "journal" to "Мой день", "prompt" to "Доброе напоминание", "measurements" to "Вес и сон", "level" to "Уровень дневника")

@Composable internal fun SettingsScreen() {
    val h = LocalUi.current; val prefs = h.preferences
    when (h.route.name) {
        "sources" -> SourcesScreen()
        "drafts" -> DraftsScreen()
        "preference-resume" -> DirectPreferenceEditor()
        "appearance", "home-layout" -> PreferenceEditor(h.route.name)
        "game" -> {
            ScreenHeading("Игра в своём темпе", "Названия — образы диапазонов, а не оценка тела или качества сна.")
            DietCard { ToggleRow("Игровые названия веса и сна", prefs.gameNames) { h.preference("gameNames", mapOf("enabled" to it.toString())) }; ToggleRow("Уровни за заполнение дневника", prefs.gameLevels) { h.preference("gameLevels", mapOf("enabled" to it.toString())) }; Muted("Нет streak, штрафов за пропуски или наград за снижение веса и калорий.") }
            DietCard("Вес · просто названия") { listOf("До 60 кг — Искра", "60–<70 — Вихрь", "70–<80 — Метеор", "80–<90 — Шторм", "90–95 включительно — Машина", ">95–<100 — Титан", "100 кг и больше — Богатырь").forEach { Text(it) } }
            DietCard("Сон · просто названия") { listOf("Больше 0 и меньше 6 ч — Короткий привал", "6–<8 ч — Ловец снов", "8–<10 ч — Соня", "10 ч и больше — Хранитель подушки", "0 ч — без игрового названия").forEach { Text(it) } }
            DietCard("Растём за привычку записывать") { listOf(0L, 1, 2, 7, 14, 30, 60).forEach { count -> Text("$count дат · ${GameRules.level(count).name}") }; Muted("Одна заполненная дата считается один раз. Изменение полноты пересчитывает уровень.") }
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
        else -> {
            ScreenHeading("Больше вашего.", "Удобные инструменты и оформление под себя.")
            DietCard("Записать · ${dateLabel(h.date)}") { Action("Утренний вес", secondary = true) { h.openForm("weight") }; Action("Суммарный сон", secondary = true) { h.openForm("sleep") }; Action("Фото, настроение и заметка", secondary = true) { h.openForm("moment") }; Muted("Эти действия доступны, даже если карточки скрыты на главной.") }
            listOf("catalog" to "Библиотека продуктов и рецептов", "templates" to "Шаблоны приёмов пищи", "plans" to "План питания", "photo-feed" to "Фотолента еды", "search" to "Поиск по дневнику", "calendar" to "Календарь", "profile" to "Профиль", "goals" to "Ручные цели", "home-layout" to "Настраиваемая главная", "appearance" to "Акцент и обложка", "meals" to "Приёмы пищи", "categories" to "Категории", "game" to "Названия и уровни", "reminders" to "Напоминания", "drafts" to "Все черновики").forEach { (route, label) -> Action("$label  →", secondary = true) { h.navigate(route) } }
            DietCard("Как вам комфортнее") { Choices(listOf("system" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная"), prefs.theme) { h.preference("theme", mapOf("theme" to it)) }; ToggleRow("Крупнее текст в приложении", prefs.largeText) { h.preference("font", mapOf("large" to it.toString())) }; Muted("Системный размер текста и настройка движения также учитываются.") }
            if (prefs.drafts.isNotEmpty()) DietCard("Черновики настроек") { prefs.drafts.forEach { draft -> TextButton(onClick = { if (draft.group !in listOf("home", "appearance")) h.preferenceForm = UiPreferenceSession.open(h, draft.group, emptyMap()); h.navigate(if (draft.group == "home") "home-layout" else if (draft.group == "appearance") "appearance" else "preference-resume") }) { Text("Продолжить: ${preferenceGroupLabel(draft.group)}") } } }
            prefs.unsupportedDrafts.forEach { draft -> DietCard("Черновик из новой версии", preferenceGroupLabel(draft.group)) { Muted("Эта группа настроек временно недоступна. Неизвестный черновик сохранён без изменений; остальные группы можно использовать."); DeleteLink("Удалить только этот черновик") { h.confirm("Удалить неизвестный черновик?", "Удалится только черновик «${preferenceGroupLabel(draft.group)}». Применённые настройки и дневник сохранятся. Операцию нельзя отменить.", "Удалить черновик") { h.launch { h.visual.discardUnsupportedDraft(draft.group, draft.rawFingerprint) } } } } }
            DietCard("На этом телефоне") { Muted("Офлайн · без аккаунта и рекламы. Личные записи и фотографии не отправляются на сервер. Удаление приложения с его данными удалит дневник; экспорта и восстановления нет."); Muted("Начальный справочник: USDA FoodData Central / SR Legacy April2018; справочные значения на 100 г. Конкретные свои продукты можно исправить по этикетке. Оформление — проектные иллюстрации."); TextButton(onClick = { h.navigate("sources") }) { Text("Источники и лицензии →") } }
        }
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
    ScreenHeading("Черновик настройки", "Проверьте значение перед повторным сохранением.")
    when (s.draft.group) {
        "theme" -> Choices(listOf("system" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная"), s.fields["theme"].orEmpty()) { s.change("theme", it) }
        "font" -> ToggleRow("Крупнее текст", s.fields["large"] == "true", !s.busy) { s.change("large", it.toString()) }
        "gameNames", "gameLevels" -> ToggleRow(if (s.draft.group == "gameNames") "Игровые названия" else "Уровни дневника", s.fields["enabled"] == "true", !s.busy) { s.change("enabled", it.toString()) }
        else -> s.fields.forEach { (key, value) -> Text("$key: $value") }
    }
    ErrorNotice(s.error); PreferenceRecovery(s)
    Action("Сохранить", enabled = s.editable) { h.launch { s.busy = true; try { s.apply(); h.forgetPreference(s); h.preferenceForm = null; h.message = "Настройка сохранена"; h.pop() } catch (e: CancellationException) { throw e } catch (e: Exception) { s.error = app.diet.messages.userMessage(e) } finally { s.busy = false } } }; Action("Отмена", secondary = true, enabled = !s.busy && !s.uncertain) { h.back() }
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
        val order = session.fields["order"].orEmpty().split(','); val hidden = session.fields["hidden"].orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
        order.forEachIndexed { index, block -> DietCard(HomeLabels[block] ?: block) { ToggleRow("Показывать на главной", block !in hidden, !session.busy) { checked -> session.change("hidden", (if (checked) hidden - block else hidden + block).joinToString(",")) }; Row { if (index > 0) TextButton(onClick = { val next = order.toMutableList(); next.removeAt(index); next.add(index - 1, block); session.change("order", next.joinToString(",")) }) { Text("↑ Выше") }; if (index < order.lastIndex) TextButton(onClick = { val next = order.toMutableList(); next.removeAt(index); next.add(index + 1, block); session.change("order", next.joinToString(",")) }) { Text("↓ Ниже") } } } }
        Muted("Настройка вида не удаляет записи. Добавление еды и остальные функции остаются доступны через инструменты.")
    }
    ErrorNotice(session.error); Muted(session.message)
    PreferenceRecovery(session)
    Action(if (session.busy) "Сохраняем…" else "Сохранить", enabled = session.editable) { h.launch { session.busy = true; try { session.apply(); h.forgetPreference(session); h.preferenceForm = null; h.preferencePreview = null; h.message = "Настройки сохранены"; h.pop() } catch (e: CancellationException) { throw e } catch (e: Exception) { session.error = app.diet.messages.userMessage(e, "Не удалось сохранить настройки") } finally { session.busy = false } } }
    Action("Отмена", enabled = !session.busy && !session.uncertain, secondary = true) { h.back() }
}

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
