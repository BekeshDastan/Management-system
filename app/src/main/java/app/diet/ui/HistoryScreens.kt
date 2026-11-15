package app.diet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.diet.data.*
import app.diet.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable internal fun HistoryScreen() {
    val h = LocalUi.current; val feed = h.route.name == "photo-feed"
    if (h.route.name == "photo-detail") { PhotoDetail(); return }
    val prefix = if (feed) "photos" else "history"
    val query = h.view["$prefix.query"].orEmpty(); val start = h.view["$prefix.start"] ?: "1900-01-01"; val end = h.view["$prefix.end"] ?: today(); val meal = h.view["$prefix.meal"].orEmpty(); val page = h.view["$prefix.page"]?.toIntOrNull() ?: 0
    var food by remember { mutableStateOf<List<FoodEntryEntity>>(emptyList()) }; var photos by remember { mutableStateOf<List<PhotoEntity>>(emptyList()) }; var dates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }; var completed by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var busy by remember { mutableStateOf(true) }; var error by remember { mutableStateOf<String?>(null) }
    fun field(key: String, value: String) { h.context("$prefix.$key", value); h.context("$prefix.page", "0") }
    LaunchedEffect(prefix, query, start, end, meal, page, h.state.dataRevision) {
        busy = true; error = null; food = emptyList(); photos = emptyList(); delay(150)
        try {
            val first = DomainDates.parse(start); val last = DomainDates.parse(end); require(first <= last && last <= LocalDate.now()) { "Проверьте диапазон дат" }
            if (feed) { photos = h.repository.queryPhotos(start, end, query, meal.ifEmpty { null }, page * 12L, 12); dates = photos.map { it.momentId }.distinct().associateWith { id -> h.repository.loadMoment(id)?.localDate ?: throw DataFailure.Missing() } }
            else { food = h.repository.queryHistory(start, end, query, meal.ifEmpty { null }, page * 12L, 12); completed = food.map { it.localDate }.distinct().associateWith { h.repository.dayComplete(it) } }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e, "Не удалось прочитать историю") } finally { busy = false }
    }
    ScreenHeading(if (feed) "Моменты, которые остались." else "Найти в своём дневнике.", if (feed) "Только ваши сохранённые фотографии. Обложки и черновики сюда не попадают." else "Фактическая еда с историческими названиями, количеством и КБЖУ.")
    OutlinedTextField(query, { if (it.codePointCount(0, it.length) <= 100) field("query", it) }, Modifier.fillMaxWidth(), label = { Text(if (feed) "Найти по подписи" else "Что искать?") }, singleLine = true)
    CollapsiblePanel("Фильтры", "Период и приём пищи", initiallyOpen = false) {
        OutlinedTextField(start, { field("start", it) }, Modifier.fillMaxWidth(), label = { Text("С ГГГГ-ММ-ДД") }, singleLine = true)
        OutlinedTextField(end, { field("end", it) }, Modifier.fillMaxWidth(), label = { Text("По ГГГГ-ММ-ДД") }, singleLine = true)
        Choices(listOf("" to "Все приёмы") + h.state.meals.map { it.id to (it.name + if (it.archived) " · архив" else "") }, meal) { field("meal", it) }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()); ErrorNotice(error)
    if (!busy && error == null && (if (feed) photos.isEmpty() else food.isEmpty())) EmptyState(if (feed) "Первый кадр ещё впереди" else "Ничего не нашлось", if (feed) "Добавьте фотографию через «Мой день» на главной." else "Попробуйте другое название, дату или приём пищи.")
    if (feed) photos.forEach { photo -> DietCard {
        h.media.Thumbnail(photo.assetId, Modifier.fillMaxWidth().height(240.dp), photo.caption.ifBlank { "Фото дня" })
        Text(photo.caption.ifBlank { "Момент дня" }, style = MaterialTheme.typography.titleMedium)
        Muted("${dateLabel(dates[photo.momentId].orEmpty())} · ${h.state.meals.find { it.id == photo.mealId }?.name ?: "Момент дня"}")
        TextButton(onClick = { h.navigate("photo-detail", photo.id) }) { Text("Открыть фотографию →") }
    } }
    else food.forEach { entry -> DietCard(entry.displayName(), "${dateLabel(entry.localDate)} · ${h.state.meals.find { it.id == entry.mealId }?.name ?: "Приём"}") {
        if (entry.kind == "quick") Muted("Быстрая запись · одна условная порция") else Muted("${decimal(requireNotNull(entry.quantityMicros))} ${unitLabel(requireNotNull(entry.snapshot).unit, entry.snapshot.portionName)}")
        NutritionText(entry.nutrients(), true); Pill(if (completed[entry.localDate] == true) "День заполнен" else "Частично заполнен")
        TextButton(onClick = { h.date = entry.localDate; h.navigate("home") }) { Text("Открыть день") }; TextButton(onClick = { h.openForm("entry", entry.id) }) { Text("Исправить запись") }
    } }
    PageControls(page, (if (feed) photos.size else food.size) == 12, busy) { h.context("$prefix.page", it.toString()) }
}

@Composable private fun PhotoDetail() {
    val h = LocalUi.current; var photo by remember { mutableStateOf<PhotoEntity?>(null) }; var date by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(h.route.id) { try { photo = h.repository.loadPhoto(requireNotNull(h.route.id)) ?: throw DataFailure.Missing(); date = h.repository.loadMoment(requireNotNull(photo).momentId)?.localDate ?: throw DataFailure.Missing() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e) } }
    ErrorNotice(error)
    photo?.let { p -> ScreenHeading(p.caption.ifBlank { "Момент дня" }, "${dateLabel(date)} · ${h.state.meals.find { it.id == p.mealId }?.name ?: "Без приёма пищи"}"); h.media.Viewer(p.assetId, Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 620.dp), p.caption.ifBlank { "Фото дня" }); Action("Открыть этот день", secondary = true) { h.date = date; h.navigate("home") }; Action("Изменить подпись или убрать фото", secondary = true) { h.date = date; h.launch { h.repository.setVisibleDate(date); h.openForm("moment") } } }
}
