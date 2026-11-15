package app.diet.ui

import androidx.compose.runtime.*
import app.diet.data.*
import app.diet.domain.FoodUnit
import app.diet.domain.Numbers
import app.diet.domain.NutritionSnapshot
import app.diet.domain.Rational
import app.diet.domain.Nutrients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.time.LocalDate

@Serializable
internal data class UiSnapshot(val name: String, val unit: String, val basis: Long, val portion: String?, val pairs: List<String>, val snapshotFormatVersion: Int = 1, val calcContractVersion: Int = 1, val sourceKind: String = "PRODUCT", val nameKey: String = app.diet.domain.NameKeys.normalize(name)) {
    fun columns() = SnapshotColumns(name, unit, basis, portion, pairs[0], pairs[1], pairs[2], pairs[3], pairs[4], pairs[5], pairs[6], pairs[7], snapshotFormatVersion, calcContractVersion, sourceKind, nameKey)
    companion object { fun from(s: SnapshotColumns) = UiSnapshot(s.name, s.unit, s.basisMicros, s.portionName, listOf(s.kcalNumerator, s.kcalDenominator, s.proteinNumerator, s.proteinDenominator, s.fatNumerator, s.fatDenominator, s.carbsNumerator, s.carbsDenominator), s.snapshotFormatVersion, s.calcContractVersion, s.sourceKind, s.nameKey) }
}

@Serializable
internal data class UiLine(val id: String = newId(), val snapshot: UiSnapshot, val quantity: String, val sourceId: String? = null) {
    fun input() = LineInput(snapshot.columns(), Numbers.parseQuantityMicros(quantity), sourceId)
    companion object { fun from(l: LineInput) = UiLine(snapshot = UiSnapshot.from(l.snapshot), quantity = Numbers.formatQuantityMicros(l.quantityMicros), sourceId = l.sourceItemId) }
}

@Serializable
internal data class UiPhoto(val id: String = newId(), val assetId: String, val caption: String = "", val mealId: String? = null) {
    fun input() = PhotoInput(id, assetId, caption, mealId)
}

@Serializable
internal data class UiFormData(
    val fields: Map<String, String> = emptyMap(),
    val lines: List<UiLine> = emptyList(),
    val photos: List<UiPhoto> = emptyList(),
    val removedAssets: Set<String> = emptySet(),
    val operationId: String = newId(),
    /** A submitted command may be unknown; this is NOT food-day completion or a saved fact. */
    val submissionPending: Boolean = false,
) {
    operator fun get(key: String): String = fields[key].orEmpty()
}

internal val DraftJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

/** Raw form is checkpointed independently from its explicit fact commit. */
internal class UiFormSession(
    val repository: DietRepository,
    private val scope: CoroutineScope,
    initial: DraftEntity,
    initialData: UiFormData,
    private val alreadySaved: Boolean,
) {
    var entity by mutableStateOf(initial); private set
    var data by mutableStateOf(initialData); private set
    var error by mutableStateOf<String?>(null)
    var checkpointMessage by mutableStateOf(if (alreadySaved) "Черновик восстановлен" else "Готовим черновик…"); private set
    var busy by mutableStateOf(false)
    var uncertain by mutableStateOf(initialData.submissionPending); private set
    val editable: Boolean get() = !busy && !uncertain
    var dirty by mutableStateOf(false); private set
    private var saved = alreadySaved
    private var debounce: Job? = null
    private val mutex = Mutex()

    init { if (!saved) schedule() }
    fun field(key: String, value: String) { if (data[key] != value) change(data.copy(fields = data.fields + (key to value))) }
    fun fields(values: Map<String, String>) = change(data.copy(fields = data.fields + values))
    fun numericField(key: String, value: String) { if (acceptNumeric(value)) field(key, value) }
    private fun acceptNumeric(value: String): Boolean {
        if (!editable) return false
        if (value.codePointCount(0, value.length) > Numbers.MAX_RAW_CODE_POINTS) { error = "В числовом поле допустимо до 64 знаков. Прежнее значение сохранено."; return false }
        return true
    }
    fun lineQuantity(id: String, value: String) { if (acceptNumeric(value)) change(data.copy(lines = data.lines.map { if (it.id == id) it.copy(quantity = value) else it })) }
    fun replaceEntry(input: LineInput) = change(data.copy(lines = listOf(UiLine.from(input)), fields = data.fields + mapOf("replace" to "true", "quick" to "false", "replacementName" to input.snapshot.name)))
    fun addLine(input: LineInput) = addLines(listOf(input))
    fun addLines(inputs: List<LineInput>) { if (data.lines.size + inputs.size > 500) { error = "В составе может быть не больше 500 строк"; return }; change(data.copy(lines = data.lines + inputs.map(UiLine::from))) }
    fun removeLine(id: String) = change(data.copy(lines = data.lines.filterNot { it.id == id }))
    fun replaceLines(lines: List<UiLine>, fields: Map<String, String> = emptyMap()) { require(lines.size <= 500); change(data.copy(lines = lines, fields = data.fields + fields)) }
    fun photo(id: String, caption: String? = null, mealId: String? = null, changeMeal: Boolean = false) = change(data.copy(photos = data.photos.map { if (it.id == id) it.copy(caption = caption ?: it.caption, mealId = if (changeMeal) mealId else it.mealId) else it }))
    /** Called by the serialized photo-removal workflow while controls stay busy. */
    fun recordPhotoRemovalIntent(assetId: String) = change(data.copy(photos = data.photos.filterNot { it.assetId == assetId }, removedAssets = data.removedAssets + assetId), allowBusy = true)
    fun mergePhotos(photos: List<PhotoInput>) {
        val additions = photos.filter { p -> p.assetId !in data.removedAssets && data.photos.none { it.assetId == p.assetId } }
        if (additions.isNotEmpty()) change(data.copy(photos = data.photos + additions.map { UiPhoto(it.id, it.assetId, it.caption, it.mealId ?: data["photoDefaultMeal"].ifEmpty { null }) }))
    }
    private fun change(next: UiFormData, allowBusy: Boolean = false) {
        if (uncertain || busy && !allowBusy || next == data) return
        data = next.copy(operationId = newId(), submissionPending = false); dirty = true; error = null; checkpointMessage = "Изменения в черновике…"; schedule()
    }
    private fun schedule() {
        debounce?.cancel()
        debounce = scope.launch { delay(350); try { checkpoint() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e, "Не удалось сохранить черновик. Повторите попытку."); checkpointMessage = "Черновик не подтверждён — повторите" } }
    }
    suspend fun checkpoint(): DraftEntity = mutex.withLock {
        val latest = repository.freshDraft(entity.id)
        if (saved && latest == null) throw DataFailure.Missing()
        if (latest != null && latest.status != "OPEN") throw DataFailure.ClosedDraft()
        val raw = DraftJson.encodeToString(data)
        // A cancelled continuation can lose an acknowledgement AFTER a durable checkpoint.
        // Adopt only the same raw/op, or a media-only advance of our previously read raw.
        if (latest != null) {
            if (latest.kind != entity.kind || latest.targetEntityId != entity.targetEntityId || latest.baseRevision != entity.baseRevision) throw DataFailure.Conflict()
            val acknowledgedCurrent = latest.rawPayload == raw && latest.operationId == data.operationId
            val samePayload = latest.operationId == data.operationId && runCatching { DraftJson.decodeFromString<UiFormData>(latest.rawPayload).copy(submissionPending = data.submissionPending) == data }.getOrDefault(false)
            if ((!saved || latest.draftRevision != entity.draftRevision) && latest.rawPayload != entity.rawPayload && !samePayload) throw DataFailure.Conflict()
            if (acknowledgedCurrent) { entity = latest; saved = true; checkpointMessage = "Черновик сохранён на устройстве"; return@withLock latest }
        }
        val base = latest ?: entity
        val next = base.copy(rawPayload = raw, operationId = data.operationId, selectedLocalDate = data["date"].ifBlank { base.selectedLocalDate }, mealId = data["meal"].ifBlank { base.mealId })
        entity = repository.checkpointDraft(next, latest?.draftRevision)
        saved = true; checkpointMessage = "Черновик сохранён на устройстве"; entity
    }
    suspend fun freeze(): Operation {
        debounce?.cancelAndJoin()
        repository.recover(data.operationId)?.let { (receipt, _) ->
            require(receipt.originDraftId == entity.id) { "Операция принадлежит другому черновику" }
            return Operation(data.operationId, receipt.originDraftId, receipt.confirmedDraftRevision)
        }
        data = data.copy(submissionPending = true)
        uncertain = true
        val persisted = checkpoint()
        return Operation(data.operationId, persisted.id, persisted.draftRevision)
    }
    suspend fun discard() {
        debounce?.cancel()
        val current = repository.freshDraft(entity.id)
        if (current != null && current.status == "OPEN") repository.discardDraft(current.id, current.draftRevision)
    }
    fun stopDebounce() { debounce?.cancel() }
    fun requireRecovery() { uncertain = true; error = "Проверяем результат предыдущего сохранения…" }
    suspend fun recover(): Pair<ReceiptEntity, List<OperationResultEntity>>? = repository.recover(data.operationId)?.also { (receipt, _) -> require(receipt.originDraftId == entity.id) { "Операция принадлежит другому черновику" } }
    fun notCommitted(message: String? = null) { uncertain = false; data = data.copy(submissionPending = false); error = message; checkpointMessage = "Изменения черновика ещё не применены"; schedule() }
    fun unknown(message: String) { uncertain = true; error = message; checkpointMessage = "Изменения заблокированы до проверки результата сохранения" }

    companion object {
        fun create(repository: DietRepository, scope: CoroutineScope, kind: String, targetId: String? = null, baseRevision: Long? = null, fields: Map<String, String> = emptyMap(), lines: List<UiLine> = emptyList(), photos: List<UiPhoto> = emptyList()): UiFormSession {
            val data = UiFormData(fields, lines, photos)
            val entity = DraftEntity(kind = kind, targetEntityId = targetId, baseRevision = baseRevision, selectedLocalDate = fields["date"], mealId = fields["meal"], rawPayload = DraftJson.encodeToString(data), operationId = data.operationId, updatedAtMillis = System.currentTimeMillis())
            return UiFormSession(repository, scope, entity, data, false)
        }
        fun restore(repository: DietRepository, scope: CoroutineScope, entity: DraftEntity): UiFormSession {
            require(entity.draftFormatVersion == 1 && entity.status == "OPEN") { "Этот черновик нельзя открыть" }
            val data = DraftJson.decodeFromString<UiFormData>(entity.rawPayload)
            require(data.lines.size <= 500 && data.lines.all { it.snapshot.pairs.size == 8 } && data.photos.size <= 4) { "Структура черновика повреждена; данные сохранены" }
            data.lines.forEach { it.snapshot.columns().toDomain() }
            if (entity.kind in setOf("portion", "move-entry", "repeat-entry") || entity.kind == "entry" && data["quick"] != "true") require(data.lines.size == 1) { "Снимок записи повреждён; черновик сохранён" }
            return UiFormSession(repository, scope, entity, data, true)
        }
    }
}

internal fun microsFields(n: MicroNutrients?) = if (n == null) mapOf("kcal" to "", "protein" to "", "fat" to "", "carbs" to "") else mapOf("kcal" to decimal(n.kcal), "protein" to decimal(n.protein), "fat" to decimal(n.fat), "carbs" to decimal(n.carbs))
internal fun decimal(value: Long, scale: Int = 6) = java.math.BigDecimal.valueOf(value, scale).stripTrailingZeros().toPlainString().replace('.', ',')
internal fun microNutrients(d: UiFormData, goal: Boolean = false) = MicroNutrients(if (goal) Numbers.parseGoalKcalMicros(d["kcal"]) else Numbers.parseNutrientMicros(d["kcal"]), Numbers.parseNutrientMicros(d["protein"]), Numbers.parseNutrientMicros(d["fat"]), Numbers.parseNutrientMicros(d["carbs"]))
internal fun unitLabel(unit: String, portion: String? = null) = when (unit) { "GRAM" -> "г"; "MILLILITRE" -> "мл"; else -> portion ?: "порция" }
internal fun today() = LocalDate.now().toString()
