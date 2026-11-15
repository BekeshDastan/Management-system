package app.diet.data

import android.content.Context
import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import app.diet.domain.FoodUnit
import app.diet.domain.FoodSourceKind
import app.diet.domain.NameKeys
import app.diet.domain.Nutrients
import app.diet.domain.NutritionSnapshot
import app.diet.domain.Rational
import app.diet.domain.TextRules
import app.diet.domain.DomainDates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

sealed class DataFailure(message: String) : IllegalStateException(message) {
    class Conflict : DataFailure("Данные изменились. Откройте предпросмотр заново.")
    class Missing : DataFailure("Запись больше не существует.")
    class ClosedDraft : DataFailure("Черновик уже закрыт.")
    class Invalid(message: String) : DataFailure(message)
}

/** Retain this value in the durable form; retry MUST reuse its id. */
data class Operation(val id: String = newId(), val draftId: String? = null, val draftRevision: Long? = null)
data class CommitResult(val operationId: String, val entityIds: List<String>, val replayed: Boolean)
data class LineInput(val snapshot: SnapshotColumns, val quantityMicros: Long, val sourceItemId: String? = null)
data class PhotoInput(val id: String = newId(), val assetId: String, val caption: String = "", val mealId: String? = null)

/** A long-lived repository must observe timezone changes; injected test clocks remain fixed. */
internal object SystemLocalClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)
    override fun instant(): Instant = Instant.now()
    override fun millis(): Long = System.currentTimeMillis()
}

class DietRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: Clock = SystemLocalClock,
    internal val database: DietDatabase = DietDatabase.get(context),
) {
    private val dao = database.dao()
    private val writer = sharedWriter
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow(DietState())
    val state: StateFlow<DietState> = mutableState.asStateFlow()
    val media = MediaMetadataRepository(this)
    /** Synthetic read fault seam; never receives or logs user values. */
    @Volatile internal var beforeRefresh: (() -> Unit)? = null
    private var watching = false
    @Volatile private var visibleDate: String = LocalDate.now(clock).toString()

    internal suspend fun <T> metadataTransaction(block: suspend (DietDao) -> T): T = writer.withLock {
        database.useWriterConnection { c -> c.immediateTransaction { block(dao) } }
    }

    suspend fun initialize() {
        writer.withLock {
            database.useWriterConnection { connection -> connection.immediateTransaction {
                if (dao.allProfile().isEmpty()) {
                    dao.put(ProfileEntity()); dao.put(OnboardingEntity()); dao.put(GoalTimelineEntity())
                    listOf("Завтрак", "Обед", "Ужин", "Перекус").forEachIndexed { i, name ->
                        dao.put(MealEntity(name = name, nameKey = NameKeys.normalize(name), ordinal = i.toLong()))
                    }
                    listOf("food" to 20 * 60, "weight" to 8 * 60, "sleep" to 9 * 60).forEach { (kind, minute) -> dao.put(ReminderEntity(kind = kind, minuteOfDay = minute)) }
                }
                check(dao.allProfile().size == 1 && dao.allOnboarding().size == 1 && dao.allGoalTimeline().size == 1) { "Повреждены служебные данные" }
                check(dao.allMeal().any { !it.archived }) { "Нет активного приёма пищи" }
            } }
            refresh()
            if (!watching) {
                watching = true
                scope.launch(Dispatchers.IO) {
                    try { database.invalidationTracker.createFlow(*DOMAIN_TABLES, emitInitialState = true).collect { changed ->
                        try { refresh(changed.any { it !in MEDIA_DRAFT_TABLES }) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* refresh already published its read error under the mutex. */ }
                    } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "Не удалось прочитать локальные данные. Повторите открытие.") }
                }
            }
        }
    }

    private suspend fun refresh(domainChanged: Boolean = true, knownCommit: Boolean = false) = refreshMutex.withLock {
        try {
        beforeRefresh?.invoke()
        val currentDate = visibleDate
        val next = database.useReaderConnection { connection -> connection.deferredTransaction {
            DietState(ready = true, profile = dao.allProfile().singleOrNull(), onboarding = dao.allOnboarding().singleOrNull(),
                meals = dao.allMeal().sortedBy { it.ordinal }, categories = dao.allCategory().sortedBy { it.ordinal },
                catalog = dao.catalogPage("", null, 0, 40), ingredients = emptyList(), recents = dao.recentPage(),
                entries = dao.entriesPage(currentDate, currentDate, 0, 40), confirmations = listOfNotNull(dao.confirmationOn(currentDate)), goals = dao.allGoal().sortedBy { it.startDate },
                goalTimelineRevision = dao.allGoalTimeline().singleOrNull()?.revision ?: 1,
                weights = listOfNotNull(dao.weightOn(currentDate)), sleeps = listOfNotNull(dao.sleepOn(currentDate)),
                moments = listOfNotNull(dao.momentOn(currentDate)), photos = dao.photosOn(currentDate), assets = dao.assetsForDateAndDrafts(currentDate),
                templates = dao.templatesPage(0, 40), templateLines = emptyList(), plans = dao.plansOn(currentDate), planLines = emptyList(),
                transfers = emptyList(), quickPortionGroups = dao.allQuickPortionGroup(), quickPortions = dao.allQuickPortion(),
                reminders = dao.allReminder(), drafts = dao.draftHeaders(0, 20), draftMedia = dao.recentDraftMedia(), visibleDate = currentDate, completedDayCount = dao.completedDayCount(), dataRevision = if (domainChanged) increment(mutableState.value.dataRevision) else mutableState.value.dataRevision)
        } }
        // Invalid historical numbers are an explicit read failure, never zero.
        next.catalog.forEach { it.snapshot.toDomain() }
        next.goals.forEach { it.nutrients.validate(true) }
        next.entries.forEach { it.nutrients() }
        next.ingredients.forEach { it.snapshot.toDomain() }
        next.templateLines.forEach { it.snapshot.toDomain() }
        next.planLines.forEach { it.snapshot.toDomain() }
        mutableState.value = next
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { readError(committed = knownCommit); throw e }
    }
    private fun readError(committed: Boolean = false) {
        mutableState.value = mutableState.value.copy(error = if (committed) "Изменения сохранены, но не удалось обновить экран. Повторите открытие." else "Не удалось прочитать локальные данные. Повторите открытие.")
    }

    private fun uuid(id: String) { require(UUID.fromString(id).toString() == id) { "Некорректный ID" } }
    private fun revision(current: Long?, expected: Long?) { if (current != expected) throw DataFailure.Conflict() }
    private fun increment(value: Long) = Math.addExact(value, 1L)
    private fun date(value: String, fact: Boolean = true): LocalDate {
        val parsed = LocalDate.parse(value)
        require(parsed.toString() == value && parsed in LocalDate.of(1900, 1, 1)..LocalDate.of(9999, 12, 31)) { "Недопустимая дата" }
        if (fact) DomainDates.validateFactDate(parsed, LocalDate.now(clock))
        return parsed
    }
    private fun quantity(value: Long) { require(value in 1..1_000_000_000_000L) { "Количество вне допустимого диапазона" } }
    private suspend fun activeMeal(id: String) { if (dao.getMeal(id)?.archived != false) throw DataFailure.Invalid("Выберите активный приём пищи") }
    private fun line(input: LineInput) { quantity(input.quantityMicros); input.snapshot.toDomain().portion(input.quantityMicros); TextRules.name(input.snapshot.name) }
    private fun lines(input: List<LineInput>) { require(input.size in 1..500); input.forEach(::line) }
    private suspend fun availableSource(id: String?): String? = id?.takeIf { dao.getCatalogItem(it) != null }

    private fun fingerprint(kind: String, parts: List<Any?>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun put(value: Any?) {
            when (value) {
                null -> digest.update(byteArrayOf(0))
                is List<*> -> { digest.update(byteArrayOf(1)); put(value.size.toString()); value.forEach(::put) }
                is MicroNutrients -> put(listOf(value.kcal, value.protein, value.fat, value.carbs))
                is SnapshotColumns -> put(listOf(value.name, value.unit, value.basisMicros, value.portionName, value.kcalNumerator, value.kcalDenominator, value.proteinNumerator, value.proteinDenominator, value.fatNumerator, value.fatDenominator, value.carbsNumerator, value.carbsDenominator, value.snapshotFormatVersion, value.calcContractVersion, value.sourceKind))
                is LineInput -> put(listOf(value.snapshot, value.quantityMicros, value.sourceItemId))
                else -> { val bytes = value.toString().toByteArray(Charsets.UTF_8); digest.update(byteArrayOf(2)); digest.update(bytes.size.toString().toByteArray()); digest.update(':'.code.toByte()); digest.update(bytes) }
            }
        }
        put(kind); put(parts)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Receipt lookup is inside the serial writer and precedes ALL mutable validations. */
    private suspend fun commit(kind: String, operation: Operation, payload: List<Any?>, effect: suspend () -> List<Pair<String, String>>): CommitResult = writer.withLock {
        uuid(operation.id)
        val fp = fingerprint(kind, payload)
        val result = database.useWriterConnection { connection -> connection.immediateTransaction {
            val old = dao.getReceipt(operation.id)
            if (old != null) {
                if (old.kind != kind || old.payloadFingerprint != fp) throw DataFailure.Conflict()
                return@immediateTransaction CommitResult(operation.id, dao.results(operation.id).map { it.entityId }, true)
            }
            val draft = operation.draftId?.let { dao.getDraft(it) ?: throw DataFailure.Missing() }
            if (draft != null) {
                if (draft.status != "OPEN") throw DataFailure.ClosedDraft()
                require(draft.draftFormatVersion == 1) { "Неизвестная версия черновика; данные сохранены" }
                revision(draft.draftRevision, operation.draftRevision)
                if (draft.operationId != null && draft.operationId != operation.id) throw DataFailure.Conflict()
            }
            val ids = effect()
            dao.put(ReceiptEntity(operation.id, kind, fp, operation.draftId, operation.draftRevision, clock.millis()))
            ids.forEach { (type, id) -> dao.put(OperationResultEntity(operationId = operation.id, entityKind = type, entityId = id)) }
            if (draft != null) {
                dao.put(draft.copy(status = "COMMITTED", rawPayload = "{}", draftRevision = increment(draft.draftRevision), updatedAtMillis = clock.millis()))
                dao.clearDraftMedia(draft.id)
            }
            CommitResult(operation.id, ids.map { it.second }, false)
        } }
        try { refresh(knownCommit = true) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The transaction result remains authoritative; refresh exposed a separate read error. */ }
        result
    }

    suspend fun recover(operationId: String): Pair<ReceiptEntity, List<OperationResultEntity>>? = writer.withLock {
        database.useReaderConnection { c -> c.deferredTransaction { dao.getReceipt(operationId)?.let { it to dao.results(operationId) } } }
    }

    suspend fun saveProfile(name: String, age: Int?, heightCentiCm: Int?, initialWeightCentiKg: Int?, direction: String?, baseRevision: Long, operation: Operation): CommitResult = commit("profile", operation, listOf(name, age, heightCentiCm, initialWeightCentiKg, direction, baseRevision)) {
        val old = dao.allProfile().single(); revision(old.revision, baseRevision)
        require(age == null || age in 1..1000); require(heightCentiCm == null || heightCentiCm in 1..100_000); require(initialWeightCentiKg == null || initialWeightCentiKg in 1..1_000_000)
        require(direction == null || direction in listOf("lose", "maintain", "gain"))
        dao.put(old.copy(name = TextRules.optionalName(name, 40), age = age, heightCentiCm = heightCentiCm, initialWeightCentiKg = initialWeightCentiKg, direction = direction, revision = increment(old.revision)))
        listOf("profile" to old.id)
    }

    suspend fun completeOnboarding(operation: Operation): CommitResult = commit("onboarding", operation, emptyList()) {
        val old = dao.allOnboarding().single(); if (!old.completed) dao.put(old.copy(completed = true, step = "complete", revision = increment(old.revision)))
        listOf("onboarding" to old.id)
    }
    suspend fun saveOnboardingProfile(name: String, age: Int?, heightCentiCm: Int?, initialWeightCentiKg: Int?, direction: String?, profileBaseRevision: Long, onboardingBaseRevision: Long, operation: Operation) = commit("onboarding-profile", operation, listOf(name, age, heightCentiCm, initialWeightCentiKg, direction, profileBaseRevision, onboardingBaseRevision)) {
        val profile = dao.allProfile().single(); val onboarding = dao.allOnboarding().single()
        revision(profile.revision, profileBaseRevision); revision(onboarding.revision, onboardingBaseRevision); require(!onboarding.completed)
        require(age == null || age in 1..1000); require(heightCentiCm == null || heightCentiCm in 1..100_000); require(initialWeightCentiKg == null || initialWeightCentiKg in 1..1_000_000)
        require(direction == null || direction in listOf("lose", "maintain", "gain"))
        dao.put(profile.copy(name = TextRules.optionalName(name, 40), age = age, heightCentiCm = heightCentiCm, initialWeightCentiKg = initialWeightCentiKg, direction = direction, revision = increment(profile.revision)))
        dao.put(onboarding.copy(step = "goals", draftId = null, revision = increment(onboarding.revision)))
        listOf("profile" to profile.id, "onboarding" to onboarding.id)
    }
    suspend fun saveOnboardingStep(step: String, draftId: String?, baseRevision: Long, operation: Operation): CommitResult = commit("onboarding-step", operation, listOf(step, draftId, baseRevision)) {
        require(step in listOf("welcome", "profile", "goals"))
        val old = dao.allOnboarding().single(); revision(old.revision, baseRevision)
        require(!old.completed) { "Первый запуск уже завершён" }
        draftId?.let { require(dao.getDraft(it)?.status == "OPEN") }
        dao.put(old.copy(step = step, draftId = draftId, revision = increment(old.revision))); listOf("onboarding" to old.id)
    }
    suspend fun completeOnboardingWithGoal(startDate: String, nutrients: MicroNutrients, timelineRevision: Long, operation: Operation): CommitResult = commit("onboarding-goal", operation, listOf(startDate, nutrients, timelineRevision)) {
        val onboarding = dao.allOnboarding().single(); require(!onboarding.completed)
        date(startDate, false); nutrients.validate(true)
        val chain = dao.allGoalTimeline().single(); revision(chain.revision, timelineRevision)
        require(dao.goalOn(startDate) == null) { "На эту дату уже есть цель" }
        val goal = GoalEntity(startDate = startDate, nutrients = nutrients); dao.put(goal); dao.put(chain.copy(revision = increment(chain.revision)))
        dao.put(onboarding.copy(completed = true, step = "complete", draftId = null, revision = increment(onboarding.revision)))
        listOf("onboarding" to onboarding.id, "goal" to goal.id)
    }

    suspend fun saveWeight(localDate: String, centiKg: Int, baseRevision: Long?, operation: Operation): CommitResult = commit("weight", operation, listOf(localDate, centiKg, baseRevision)) {
        date(localDate); require(centiKg in 1..1_000_000); val old = dao.weightOn(localDate); revision(old?.revision, baseRevision)
        val value = WeightEntity(old?.id ?: newId(), localDate, centiKg, old?.revision?.let(::increment) ?: 1); dao.put(value); listOf("weight" to value.id)
    }
    suspend fun saveSleep(localDate: String, minutes: Int, confirmedZero: Boolean, baseRevision: Long?, operation: Operation): CommitResult = commit("sleep", operation, listOf(localDate, minutes, confirmedZero, baseRevision)) {
        date(localDate); require(minutes in 0..59_999 && (minutes > 0 || confirmedZero)); val old = dao.sleepOn(localDate); revision(old?.revision, baseRevision)
        val value = SleepEntity(old?.id ?: newId(), localDate, minutes, old?.revision?.let(::increment) ?: 1); dao.put(value); listOf("sleep" to value.id)
    }
    suspend fun deleteWeight(id: String, baseRevision: Long, operation: Operation) = commit("delete-weight", operation, listOf(id, baseRevision)) { val old = dao.getWeight(id) ?: throw DataFailure.Missing(); revision(old.revision, baseRevision); dao.deleteWeight(id); listOf("weight" to id) }
    suspend fun deleteSleep(id: String, baseRevision: Long, operation: Operation) = commit("delete-sleep", operation, listOf(id, baseRevision)) { val old = dao.getSleep(id) ?: throw DataFailure.Missing(); revision(old.revision, baseRevision); dao.deleteSleep(id); listOf("sleep" to id) }

    suspend fun saveGoal(startDate: String, nutrients: MicroNutrients, baseRevision: Long?, timelineRevision: Long, operation: Operation): CommitResult = commit("goal", operation, listOf(startDate, nutrients, baseRevision, timelineRevision)) {
        date(startDate, false); nutrients.validate(true); val chain = dao.allGoalTimeline().single(); revision(chain.revision, timelineRevision)
        val old = dao.goalOn(startDate); revision(old?.revision, baseRevision)
        val value = GoalEntity(old?.id ?: newId(), startDate, nutrients, old?.revision?.let(::increment) ?: 1); dao.put(value); dao.put(chain.copy(revision = increment(chain.revision))); listOf("goal" to value.id)
    }
    suspend fun saveDeferredOnboardingGoal(startDate: String, nutrients: MicroNutrients, baseRevision: Long?, timelineRevision: Long, operation: Operation): CommitResult = commit("deferred-manual-goal", operation, listOf(startDate, nutrients, baseRevision, timelineRevision)) {
        require(dao.allOnboarding().single().completed) { "Сначала завершите первый запуск" }
        date(startDate, false); nutrients.validate(true)
        val chain = dao.allGoalTimeline().single(); revision(chain.revision, timelineRevision)
        val old = dao.goalOn(startDate); revision(old?.revision, baseRevision)
        val value = GoalEntity(old?.id ?: newId(), startDate, nutrients, old?.revision?.let(::increment) ?: 1)
        dao.put(value); dao.put(chain.copy(revision = increment(chain.revision)))
        listOf("goal" to value.id)
    }
    suspend fun deleteGoal(id: String, baseRevision: Long, timelineRevision: Long, operation: Operation) = commit("delete-goal", operation, listOf(id, baseRevision, timelineRevision)) {
        val old = dao.getGoal(id) ?: throw DataFailure.Missing(); val chain = dao.allGoalTimeline().single(); revision(old.revision, baseRevision); revision(chain.revision, timelineRevision)
        dao.deleteGoal(id); dao.put(chain.copy(revision = increment(chain.revision))); listOf("goal" to id)
    }

    suspend fun saveProduct(id: String?, name: String, unit: FoodUnit, portionName: String?, nutrients: MicroNutrients, categoryId: String? = null, baseRevision: Long?, operation: Operation): CommitResult = commit("product", operation, listOf(id, name, unit.name, portionName, nutrients, categoryId, baseRevision)) {
        nutrients.validate(); val clean = TextRules.name(name); val label = if (unit == FoodUnit.PORTION) TextRules.name(requireNotNull(portionName), 30) else null
        val old = id?.let { dao.getCatalogItem(it) ?: throw DataFailure.Missing() }; revision(old?.revision, baseRevision); require(old == null || old.kind == "product")
        val snapshot = SnapshotColumns.from(NutritionSnapshot(clean, unit, if (unit == FoodUnit.PORTION) 1_000_000 else 100_000_000, nutrients.toDomain(), label))
        val item = CatalogItemEntity(old?.id ?: newId(), "product", clean, NameKeys.normalize(clean), categoryId, old?.favorite ?: false, unit.name, label, nutrients, null, snapshot, old?.revision?.let(::increment) ?: 1)
        dao.put(item.copy(sourceFdcId = old?.sourceFdcId, sourceDescription = old?.sourceDescription, sourceRelease = old?.sourceRelease, sourceVerifiedRevision = old?.sourceVerifiedRevision)); listOf("catalog" to item.id)
    }

    suspend fun saveRecipe(id: String?, name: String, yieldMicros: Long, ingredients: List<LineInput>, categoryId: String? = null, baseRevision: Long?, operation: Operation): CommitResult = commit("recipe", operation, listOf(id, name, yieldMicros, ingredients, categoryId, baseRevision)) {
        quantity(yieldMicros); lines(ingredients); val clean = TextRules.name(name)
        val old = id?.let { dao.getCatalogItem(it) ?: throw DataFailure.Missing() }; revision(old?.revision, baseRevision); require(old == null || old.kind == "recipe")
        ingredients.forEach { i -> require(i.snapshot.sourceKind != "RECIPE") { "Вложенные рецепты не поддерживаются" }; if (i.sourceItemId != null) require(dao.getCatalogItem(i.sourceItemId)?.kind != "recipe") { "Вложенные рецепты не поддерживаются" } }
        var kcal = Rational.of(0); var protein = Rational.of(0); var fat = Rational.of(0); var carbs = Rational.of(0)
        ingredients.forEach { val n = it.snapshot.toDomain().portion(it.quantityMicros); kcal += n.kcal; protein += n.protein; fat += n.fat; carbs += n.carbs }
        val scale = Rational.of(100_000_000) / Rational.of(yieldMicros)
        val snapshot = SnapshotColumns.from(NutritionSnapshot(clean, FoodUnit.GRAM, 100_000_000, Nutrients(kcal * scale, protein * scale, fat * scale, carbs * scale), sourceKind = FoodSourceKind.RECIPE))
        snapshot.toDomain()
        val item = CatalogItemEntity(old?.id ?: newId(), "recipe", clean, NameKeys.normalize(clean), categoryId, old?.favorite ?: false, "GRAM", null, null, yieldMicros, snapshot, old?.revision?.let(::increment) ?: 1)
        dao.put(item.copy(sourceFdcId = old?.sourceFdcId, sourceDescription = old?.sourceDescription, sourceRelease = old?.sourceRelease, sourceVerifiedRevision = old?.sourceVerifiedRevision)); dao.clearIngredients(item.id)
        ingredients.forEachIndexed { i, input -> dao.put(RecipeIngredientEntity(recipeId = item.id, ordinal = i.toLong(), quantityMicros = input.quantityMicros, snapshot = input.snapshot, sourceItemId = availableSource(input.sourceItemId))) }
        listOf("catalog" to item.id)
    }
    suspend fun toggleFavorite(id: String, favorite: Boolean, baseRevision: Long, operation: Operation) = commit("favorite", operation, listOf(id, favorite, baseRevision)) { val old = dao.getCatalogItem(id) ?: throw DataFailure.Missing(); revision(old.revision, baseRevision); val nextRevision = increment(old.revision); dao.put(old.copy(favorite = favorite, revision = nextRevision, sourceVerifiedRevision = if (old.sourceVerifiedRevision == old.revision) nextRevision else old.sourceVerifiedRevision)); listOf("catalog" to id) }
    suspend fun saveCategory(id: String?, name: String, baseRevision: Long?, operation: Operation) = commit("category", operation, listOf(id, name, baseRevision)) {
        val clean = TextRules.name(name, 40); val old = id?.let { dao.getCategory(it) ?: throw DataFailure.Missing() }; revision(old?.revision, baseRevision)
        require(dao.allCategory().none { it.id != id && NameKeys.normalize(it.name) == NameKeys.normalize(clean) }) { "Категория с таким именем уже есть" }
        val item = CategoryEntity(old?.id ?: newId(), old?.code ?: newId(), clean, old?.ordinal ?: (dao.allCategory().maxOfOrNull { it.ordinal }?.let(::increment) ?: 0), old?.revision?.let(::increment) ?: 1); dao.put(item); listOf("category" to item.id)
    }
    suspend fun deleteCategory(id: String, baseRevision: Long, operation: Operation) = commit("delete-category", operation, listOf(id, baseRevision)) { val old = dao.getCategory(id) ?: throw DataFailure.Missing(); revision(old.revision, baseRevision); dao.deleteCategory(id); listOf("category" to id) }
    suspend fun deleteCatalogItem(id: String, baseRevision: Long, operation: Operation) = commit("delete-catalog", operation, listOf(id, baseRevision)) { val old = dao.getCatalogItem(id) ?: throw DataFailure.Missing(); revision(old.revision, baseRevision); dao.deleteCatalogItem(id); listOf("catalog" to id) }

    suspend fun addFood(localDate: String, mealId: String, input: LineInput, operation: Operation): CommitResult = commit("food", operation, listOf(localDate, mealId, input)) { insertFood(localDate, mealId, listOf(input), operation.id) }
    /** One durable intent and one transaction for the whole reviewed meal. */
    suspend fun addFoods(localDate: String, mealId: String, inputs: List<LineInput>, operation: Operation): CommitResult =
        commit("food-batch", operation, listOf(localDate, mealId, inputs)) {
            insertFood(localDate, mealId, inputs, operation.id)
        }

    /** A move keeps the original ID, historical nutrition and operation provenance. */
    suspend fun moveFood(id: String, baseRevision: Long, targetDate: String, targetMealId: String, operation: Operation): CommitResult =
        commit("move-food", operation, listOf(id, baseRevision, targetDate, targetMealId)) {
            val old = dao.getFoodEntry(id) ?: throw DataFailure.Missing()
            revision(old.revision, baseRevision)
            date(targetDate); activeMeal(targetMealId)
            if (old.localDate == targetDate && old.mealId == targetMealId) return@commit listOf("food" to id)
            val ordinal = dao.lastMealOrdinal(targetDate, targetMealId)?.let(::increment) ?: 0
            old.nutrients()
            dao.put(old.copy(localDate = targetDate, mealId = targetMealId, ordinal = ordinal, revision = increment(old.revision)))
            if (old.localDate != targetDate && !dao.hasFood(old.localDate)) dao.unconfirm(old.localDate)
            listOf("food" to id)
        }

    /** Intentional repeat is a new ID; retries of the same operation never duplicate it. */
    suspend fun repeatFood(id: String, baseRevision: Long, targetDate: String, targetMealId: String, operation: Operation): CommitResult =
        commit("repeat-food", operation, listOf(id, baseRevision, targetDate, targetMealId)) {
            val old = dao.getFoodEntry(id) ?: throw DataFailure.Missing()
            revision(old.revision, baseRevision)
            date(targetDate); activeMeal(targetMealId); old.nutrients()
            val ordinal = dao.lastMealOrdinal(targetDate, targetMealId)?.let(::increment) ?: 0
            val repeated = old.copy(id = newId(), localDate = targetDate, mealId = targetMealId, ordinal = ordinal,
                revision = 1, sourceItemId = availableSource(old.sourceItemId), createdByOperationId = operation.id)
            dao.put(repeated)
            repeated.sourceItemId?.let { itemId ->
                val recent = dao.recentForItem(itemId)
                dao.put(RecentEntity(recent?.id ?: newId(), itemId, clock.millis()))
            }
            listOf("food" to repeated.id)
        }

    private suspend fun insertFood(localDate: String, mealId: String, inputs: List<LineInput>, operationId: String): List<Pair<String, String>> {
        date(localDate); activeMeal(mealId); lines(inputs)
        val start = dao.lastMealOrdinal(localDate, mealId)?.let(::increment) ?: 0
        return inputs.mapIndexed { i, input ->
            val entry = FoodEntryEntity(localDate = localDate, mealId = mealId, ordinal = Math.addExact(start, i.toLong()), snapshot = input.snapshot, quantityMicros = input.quantityMicros, sourceItemId = availableSource(input.sourceItemId), createdByOperationId = operationId)
            dao.put(entry)
            entry.sourceItemId?.let { itemId -> val recent = dao.recentForItem(itemId); dao.put(RecentEntity(recent?.id ?: newId(), itemId, clock.millis())) }
            "food" to entry.id
        }
    }
    suspend fun addQuickFood(localDate: String, mealId: String, name: String, nutrients: MicroNutrients, operation: Operation): CommitResult = commit("quick-food", operation, listOf(localDate, mealId, name, nutrients)) {
        date(localDate); activeMeal(mealId); nutrients.validate(); val clean = TextRules.optionalName(name, 80)
        val entry = FoodEntryEntity(localDate = localDate, mealId = mealId, ordinal = dao.lastMealOrdinal(localDate, mealId)?.let(::increment) ?: 0, kind = "quick", quickName = clean, quick = nutrients, createdByOperationId = operation.id, quickNameKey = NameKeys.normalize(clean.ifEmpty { "Быстрая запись" }))
        dao.put(entry); listOf("food" to entry.id)
    }
    suspend fun editFood(id: String, quantityMicros: Long?, quick: MicroNutrients?, name: String?, baseRevision: Long, operation: Operation) = commit("edit-food", operation, listOf(id, quantityMicros, quick, name, baseRevision)) {
        val old = dao.getFoodEntry(id) ?: throw DataFailure.Missing(); revision(old.revision, baseRevision)
        val updated = if (old.kind == "quick") { requireNotNull(quick).validate(); old.copy(quick = quick, quickName = TextRules.optionalName(name ?: "", 80), quickNameKey = NameKeys.normalize(TextRules.optionalName(name ?: "", 80).ifEmpty { "Быстрая запись" }), revision = increment(old.revision)) }
            else { quantity(requireNotNull(quantityMicros)); old.copy(quantityMicros = quantityMicros, revision = increment(old.revision)) }
        updated.nutrients(); dao.put(updated); listOf("food" to id)
    }
    suspend fun replaceFoodSnapshot(id: String, input: LineInput, baseRevision: Long, operation: Operation): CommitResult = commit("replace-food-snapshot", operation, listOf(id, input, baseRevision)) {
        val old = dao.getFoodEntry(id) ?: throw DataFailure.Missing(); revision(old.revision, baseRevision)
        line(input)
        val source = availableSource(input.sourceItemId)
        val updated = old.copy(kind = "portion", snapshot = input.snapshot, quantityMicros = input.quantityMicros,
            quickName = null, quickNameKey = null, quick = null, sourceItemId = source, revision = increment(old.revision))
        updated.nutrients(); dao.put(updated)
        source?.let { itemId -> val recent = dao.recentForItem(itemId); dao.put(RecentEntity(recent?.id ?: newId(), itemId, clock.millis())) }
        listOf("food" to old.id)
    }
    suspend fun deleteFood(id: String, baseRevision: Long, operation: Operation) = commit("delete-food", operation, listOf(id, baseRevision)) {
        val old = dao.getFoodEntry(id) ?: throw DataFailure.Missing(); revision(old.revision, baseRevision); dao.deleteFoodEntry(id)
        if (!dao.hasFood(old.localDate)) dao.unconfirm(old.localDate)
        listOf("food" to id)
    }
    suspend fun completeDay(localDate: String, completed: Boolean, confirmedEmpty: Boolean, operation: Operation) = commit("complete-day", operation, listOf(localDate, completed, confirmedEmpty)) {
        if (completed) { date(localDate); require(dao.hasFood(localDate) || confirmedEmpty) { "Подтвердите отсутствие еды" }; if (dao.confirmationOn(localDate) == null) dao.put(DayConfirmationEntity(localDate = localDate)) } else dao.unconfirm(localDate)
        emptyList()
    }

    fun foodAsLine(entry: FoodEntryEntity): LineInput = if (entry.kind == "portion") LineInput(requireNotNull(entry.snapshot), requireNotNull(entry.quantityMicros), entry.sourceItemId) else LineInput(SnapshotColumns.from(NutritionSnapshot(entry.displayName(), FoodUnit.PORTION, 1_000_000, requireNotNull(entry.quick).toDomain(), "порция", sourceKind = FoodSourceKind.QUICK)), 1_000_000)
    suspend fun copyMeal(sourceDate: String, sourceMealId: String, targetDate: String, targetMealId: String, sourceIdsAndRevisions: List<Pair<String, Long>>, operation: Operation) = commit("copy-meal", operation, listOf(sourceDate, sourceMealId, targetDate, targetMealId, sourceIdsAndRevisions.map { listOf(it.first, it.second) })) {
        require(sourceIdsAndRevisions.size in 1..500)
        val current = dao.mealCopyEntries(sourceDate, sourceMealId)
        if (current.map { it.id to it.revision } != sourceIdsAndRevisions) throw DataFailure.Conflict()
        insertFood(targetDate, targetMealId, current.map(::foodAsLine), operation.id)
    }

    suspend fun saveTemplate(id: String?, name: String, inputs: List<LineInput>, baseRevision: Long?, operation: Operation) = commit("template", operation, listOf(id, name, inputs, baseRevision)) {
        lines(inputs); val clean = TextRules.name(name); val old = id?.let { dao.getTemplate(it) ?: throw DataFailure.Missing() }; revision(old?.revision, baseRevision)
        val item = TemplateEntity(old?.id ?: newId(), clean, old?.revision?.let(::increment) ?: 1); dao.put(item); dao.clearTemplateLines(item.id)
        inputs.forEachIndexed { i, input -> dao.put(TemplateLineEntity(templateId = item.id, ordinal = i.toLong(), snapshot = input.snapshot, quantityMicros = input.quantityMicros, sourceItemId = availableSource(input.sourceItemId))) }; listOf("template" to item.id)
    }
    suspend fun deleteTemplate(id: String, baseRevision: Long, operation: Operation) = commit("delete-template", operation, listOf(id, baseRevision)) { val old = dao.getTemplate(id) ?: throw DataFailure.Missing(); revision(old.revision, baseRevision); dao.deleteTemplate(id); listOf("template" to id) }
    suspend fun applyTemplate(id: String, baseRevision: Long, targetDate: String, mealId: String, operation: Operation) = commit("apply-template", operation, listOf(id, baseRevision, targetDate, mealId)) {
        val template = dao.getTemplate(id) ?: throw DataFailure.Missing(); revision(template.revision, baseRevision)
        val inputs = dao.templateLinesIn(id).map { LineInput(it.snapshot, it.quantityMicros, it.sourceItemId) }
        insertFood(targetDate, mealId, inputs, operation.id)
    }
    suspend fun savePlan(id: String?, name: String, planDate: String, mealId: String, inputs: List<LineInput>, baseRevision: Long?, operation: Operation) = commit("plan", operation, listOf(id, name, planDate, mealId, inputs, baseRevision)) {
        val day = date(planDate, false); require(day <= DomainDates.latestPlanDate(LocalDate.now(clock))); activeMeal(mealId); lines(inputs)
        val old = id?.let { dao.getPlan(it) ?: throw DataFailure.Missing() }; revision(old?.revision, baseRevision)
        val item = PlanEntity(old?.id ?: newId(), planDate, mealId, TextRules.name(name), old?.revision?.let(::increment) ?: 1); dao.put(item); dao.clearPlanLines(item.id)
        inputs.forEachIndexed { i, input -> dao.put(PlanLineEntity(planId = item.id, ordinal = i.toLong(), snapshot = input.snapshot, quantityMicros = input.quantityMicros, sourceItemId = availableSource(input.sourceItemId))) }; listOf("plan" to item.id)
    }
    suspend fun deletePlan(id: String, baseRevision: Long, operation: Operation) = commit("delete-plan", operation, listOf(id, baseRevision)) { val old = dao.getPlan(id) ?: throw DataFailure.Missing(); revision(old.revision, baseRevision); dao.deletePlan(id); listOf("plan" to id) }
    suspend fun transferPlan(id: String, baseRevision: Long, targetDate: String, mealId: String, operation: Operation) = commit("transfer-plan", operation, listOf(id, baseRevision, targetDate, mealId)) {
        val plan = dao.getPlan(id) ?: throw DataFailure.Missing(); revision(plan.revision, baseRevision)
        val inputs = dao.planLinesIn(id).map { LineInput(it.snapshot, it.quantityMicros, it.sourceItemId) }
        val result = insertFood(targetDate, mealId, inputs, operation.id)
        val transfer = TransferEntity(operationId = operation.id, sourcePlanId = id, sourcePlanName = plan.name, localDate = targetDate, mealId = mealId, committedAtMillis = clock.millis()); dao.put(transfer)
        result + ("transfer" to transfer.id)
    }

    suspend fun saveMoment(localDate: String, mood: String?, note: String, baseRevision: Long?, operation: Operation) = commit("moment", operation, listOf(localDate, mood, note, baseRevision)) {
        date(localDate); require(mood == null || mood in listOf("great", "good", "neutral", "tired")); val clean = TextRules.note(note)
        val old = dao.momentOn(localDate); revision(old?.revision, baseRevision)
        val item = MomentEntity(old?.id ?: newId(), localDate, mood, clean, old?.revision?.let(::increment) ?: 1, old?.mediaRevision ?: 1)
        dao.put(item); listOf("moment" to item.id)
    }

    suspend fun saveMomentWithPhotos(localDate: String, mood: String?, note: String, photos: List<PhotoInput>, baseRevision: Long?, mediaRevision: Long?, operation: Operation) = commit("moment-photos", operation, listOf(localDate, mood, note, photos.map { listOf(it.id, it.assetId, it.caption, it.mealId) }, baseRevision, mediaRevision)) {
        date(localDate); require(photos.size <= 4 && photos.map { it.id }.toSet().size == photos.size && photos.map { it.assetId }.toSet().size == photos.size)
        require(mood == null || mood in listOf("great", "good", "neutral", "tired"))
        val old = dao.momentOn(localDate); revision(old?.revision, baseRevision); revision(old?.mediaRevision, mediaRevision)
        val moment = MomentEntity(old?.id ?: newId(), localDate, mood, TextRules.note(note), old?.revision?.let(::increment) ?: 1, old?.mediaRevision?.let(::increment) ?: 1)
        val prior = old?.let { dao.photosIn(it.id) } ?: emptyList()
        photos.forEach { input ->
            uuid(input.id); val asset = dao.getMediaAsset(input.assetId) ?: throw DataFailure.Missing()
            require(asset.state == "READY" && asset.integrity == "VERIFIED") { "Фото ещё не подготовлено или повреждено" }
            TextRules.caption(input.caption)
            input.mealId?.let { require(dao.getMeal(it) != null) }
            val existing = dao.getPhoto(input.id)
            require(existing == null || existing.momentId == moment.id && existing.assetId == input.assetId)
            if (existing == null) require(operation.draftId != null && dao.draftMedia(operation.draftId).any { it.assetId == input.assetId }) { "Фото не принадлежит черновику" }
        }
        dao.put(moment)
        prior.forEach { dao.deletePhoto(it.id) }
        photos.forEachIndexed { i, input -> dao.put(PhotoEntity(input.id, moment.id, input.assetId, TextRules.caption(input.caption), input.mealId, i.toLong(), prior.find { it.id == input.id }?.revision?.let(::increment) ?: 1)) }
        (prior.map { it.assetId } - photos.map { it.assetId }.toSet()).forEach { id -> val asset = requireNotNull(dao.getMediaAsset(id)); val existing = dao.cleanupForAsset(id); dao.put(MediaCleanupEntity(existing?.id ?: newId(), assetId = id, generation = asset.generation)) }
        listOf("moment" to moment.id) + photos.map { "photo" to it.id }
    }

    suspend fun saveMeal(id: String?, name: String, archived: Boolean, baseRevision: Long?, operation: Operation) = commit("meal", operation, listOf(id, name, archived, baseRevision)) {
        val clean = TextRules.name(name, 40); val old = id?.let { dao.getMeal(it) ?: throw DataFailure.Missing() }; revision(old?.revision, baseRevision)
        if (archived) require(dao.allMeal().any { it.id != id && !it.archived }) { "Должен остаться хотя бы один активный приём" }
        val item = MealEntity(old?.id ?: newId(), clean, NameKeys.normalize(clean), ordinal = old?.ordinal ?: (dao.allMeal().maxOfOrNull { it.ordinal }?.let(::increment) ?: 0), archived = archived, revision = old?.revision?.let(::increment) ?: 1)
        dao.put(item); listOf("meal" to item.id)
    }
    suspend fun reorderMeals(idsAndRevisions: List<Pair<String, Long>>, operation: Operation) = commit("meal-order", operation, listOf(idsAndRevisions.map { listOf(it.first, it.second) })) {
        val all = dao.allMeal(); require(idsAndRevisions.map { it.first }.toSet() == all.map { it.id }.toSet() && idsAndRevisions.size == all.size)
        val start = increment(maxOf(all.maxOfOrNull { it.ordinal } ?: 0, all.size.toLong())); Math.addExact(start, all.size.toLong())
        idsAndRevisions.forEachIndexed { i, (id, rev) -> val old = all.first { it.id == id }; revision(old.revision, rev); dao.put(old.copy(ordinal = start + i)) }
        idsAndRevisions.forEachIndexed { i, (id, _) -> val old = all.first { it.id == id }; dao.put(old.copy(ordinal = i.toLong(), revision = increment(old.revision))) }; idsAndRevisions.map { "meal" to it.first }
    }

    suspend fun saveQuickPortions(itemId: String, amounts: List<Long>, baseRevision: Long?, operation: Operation) = commit("quick-portions", operation, listOf(itemId, amounts, baseRevision)) {
        require(amounts.size in 1..5 && amounts.toSet().size == amounts.size); amounts.forEach(::quantity); val item = dao.getCatalogItem(itemId) ?: throw DataFailure.Missing()
        val old = dao.quickPortionGroup(itemId, item.unit, item.snapshot.basisMicros, item.portionName ?: ""); revision(old?.revision, baseRevision)
        val group = QuickPortionGroupEntity(old?.id ?: newId(), itemId, item.snapshot.basisMicros, item.unit, item.portionName ?: "", old?.revision?.let(::increment) ?: 1); dao.put(group); dao.clearQuickPortions(group.id)
        amounts.forEachIndexed { i, value -> dao.put(QuickPortionEntity(groupId = group.id, ordinal = i.toLong(), quantityMicros = value)) }; listOf("quick-portions" to group.id)
    }
    suspend fun resetQuickPortions(itemId: String, baseRevision: Long?, operation: Operation) = commit("reset-portions", operation, listOf(itemId, baseRevision)) {
        val item = dao.getCatalogItem(itemId) ?: throw DataFailure.Missing()
        val group = dao.quickPortionGroup(itemId, item.unit, item.snapshot.basisMicros, item.portionName ?: ""); revision(group?.revision, baseRevision)
        if (group != null) dao.deleteQuickPortionGroup(group.id)
        listOf("catalog" to itemId)
    }

    suspend fun saveReminder(id: String, enabled: Boolean, minuteOfDay: Int, weekDaysMask: Int, baseRevision: Long, operation: Operation) = commit("reminder", operation, listOf(id, enabled, minuteOfDay, weekDaysMask, baseRevision)) {
        require(minuteOfDay in 0..1439 && weekDaysMask in 1..127); val old = dao.getReminder(id) ?: throw DataFailure.Missing(); revision(old.revision, baseRevision)
        val item = old.copy(enabled = enabled, minuteOfDay = minuteOfDay, weekDaysMask = weekDaysMask, revision = increment(old.revision), generation = increment(old.generation)); dao.put(item)
        dao.put(ReminderOutboxEntity(reminderSpecId = id, generation = item.generation, action = if (enabled) "SCHEDULE" else "CANCEL")); listOf("reminder" to id)
    }
    suspend fun pendingReminders() = dao.pendingReminderOutbox()
    suspend fun acknowledgeReminder(id: String) = writer.withLock { database.useWriterConnection { c -> c.immediateTransaction { val old = dao.getReminderOutbox(id) ?: return@immediateTransaction; dao.put(old.copy(acknowledged = true)) } } }
    suspend fun reminderSpecs(): List<ReminderEntity> = dao.allReminder()
    suspend fun claimReminder(reminderId: String, localDate: String, generation: Long): ReminderOccurrenceEntity? = metadataTransaction { d ->
        val spec = d.getReminder(reminderId) ?: return@metadataTransaction null
        val day = date(localDate)
        if (!spec.enabled || spec.generation != generation || day != LocalDate.now(clock) || spec.weekDaysMask and (1 shl (day.dayOfWeek.value - 1)) == 0) return@metadataTransaction null
        if (d.reminderClaimed(reminderId, localDate)) return@metadataTransaction null
        val occurrence = ReminderOccurrenceEntity(reminderSpecId = reminderId, localDate = localDate, generation = generation, claimedAtMillis = clock.millis()); d.put(occurrence); occurrence
    }
    suspend fun finishReminderOccurrence(id: String, errorCode: String? = null) = metadataTransaction { d ->
        val old = d.getReminderOccurrence(id) ?: throw DataFailure.Missing()
        require(errorCode == null || errorCode.matches(Regex("[A-Z_]{1,64}")))
        d.put(old.copy(state = if (errorCode == null) "POSTED" else "FAILED", errorCode = errorCode))
    }

    suspend fun checkpointDraft(draft: DraftEntity, expectedRevision: Long?): DraftEntity = writer.withLock {
        database.useWriterConnection { c -> c.immediateTransaction {
            uuid(draft.id); require(draft.draftFormatVersion == 1 && draft.status == "OPEN")
            val old = dao.getDraft(draft.id); revision(old?.draftRevision, expectedRevision)
            if (old != null && old.status != "OPEN") throw DataFailure.ClosedDraft()
            require(old == null || old.draftFormatVersion == 1) { "Неизвестная версия черновика; данные сохранены" }
            val next = draft.copy(draftRevision = old?.draftRevision?.let(::increment) ?: 1, updatedAtMillis = clock.millis(), mediaRevision = if (old == null) 1 else if (old.selectedLocalDate != draft.selectedLocalDate || old.targetEntityId != draft.targetEntityId) increment(old.mediaRevision) else old.mediaRevision)
            require(Json.encodeToString(next).toByteArray(Charsets.UTF_8).size <= 2_097_152) { "Черновик слишком большой" }
            dao.put(next); next
        } }
    }
    suspend fun freshDraft(id: String): DraftEntity? = dao.getDraft(id)
    suspend fun queryDraftHeaders(offset: Long = 0, limit: Int = 20): List<DraftHeader> { require(offset >= 0 && limit in 1..100); return dao.draftHeaders(offset, limit) }
    suspend fun findOpenDraft(kind: String, targetEntityId: String? = null, selectedLocalDate: String? = null, mealId: String? = null): DraftHeader? { selectedLocalDate?.let { date(it, false) }; return dao.findOpenDraft(kind, targetEntityId, selectedLocalDate, mealId) }
    suspend fun setVisibleDate(localDate: String) { date(localDate, false); visibleDate = localDate; refresh() }
    suspend fun loadEntry(id: String): FoodEntryEntity? = dao.getFoodEntry(id)?.also { it.nutrients() }
    suspend fun dayComplete(localDate: String): Boolean { date(localDate, false); return dao.confirmationOn(localDate) != null }
    suspend fun mealsWithEntries(localDate: String): List<String> { date(localDate, false); return dao.mealsWithEntries(localDate) }
    suspend fun loadPhoto(id: String): PhotoEntity? = dao.getPhoto(id)
    suspend fun loadCatalogItem(id: String): CatalogItemEntity? = dao.getCatalogItem(id)?.also { it.snapshot.toDomain() }
    suspend fun recipeIngredients(id: String): List<RecipeIngredientEntity> = dao.ingredientsIn(id).also { list -> list.forEach { it.snapshot.toDomain() } }
    suspend fun loadTemplate(id: String): TemplateEntity? = dao.getTemplate(id)
    suspend fun templateLines(id: String): List<TemplateLineEntity> = dao.templateLinesIn(id)
    suspend fun loadPlan(id: String): PlanEntity? = dao.getPlan(id)
    suspend fun planLines(id: String): List<PlanLineEntity> = dao.planLinesIn(id)
    suspend fun queryCatalog(query: String = "", categoryId: String? = null, offset: Long = 0, limit: Int = 40, favoritesOnly: Boolean = false, kind: String? = null, recentOnly: Boolean = false): List<CatalogItemEntity> {
        require(offset >= 0 && limit in 1..100); require(TextRules.codePointCount(query) <= 100)
        return dao.catalogPage(NameKeys.normalize(query), categoryId, offset, limit, favoritesOnly, kind, recentOnly).also { items -> items.forEach { it.snapshot.toDomain() } }
    }
    suspend fun queryHistory(start: String, end: String, query: String = "", mealId: String? = null, offset: Long = 0, limit: Int = 12): List<FoodEntryEntity> {
        date(start, false); date(end, false); require(start <= end && offset >= 0 && limit in 1..100); require(TextRules.codePointCount(query) <= 100)
        return dao.historyPage(start, end, NameKeys.normalize(query), mealId, offset, limit).also { items -> items.forEach { it.nutrients() } }
    }
    suspend fun queryPhotos(start: String, end: String, query: String = "", mealId: String? = null, offset: Long = 0, limit: Int = 12): List<PhotoEntity> { date(start, false); date(end, false); require(start <= end && offset >= 0 && limit in 1..100); require(TextRules.codePointCount(query) <= 100); return dao.photosPage(start, end, NameKeys.normalize(query), mealId, offset, limit) }
    suspend fun loadMoment(id: String): MomentEntity? = dao.getMoment(id)
    suspend fun queryTemplates(offset: Long = 0, limit: Int = 40): List<TemplateEntity> { require(offset >= 0 && limit in 1..100); return dao.templatesPage(offset, limit) }
    suspend fun queryPlans(start: String, end: String, offset: Long = 0, limit: Int = 40): List<PlanEntity> { date(start, false); date(end, false); require(start <= end && offset >= 0 && limit in 1..100); return dao.plansPage(start, end, offset, limit) }
    suspend fun queryTransfers(planId: String, offset: Long = 0, limit: Int = 40): List<TransferEntity> { require(offset >= 0 && limit in 1..100); return dao.transfersPage(planId, offset, limit) }
    suspend fun readReport(start: LocalDate, endInclusive: LocalDate): ReportBundle = ReportQueries.read(database, start, endInclusive)
    suspend fun latestWeight(onOrBefore: String = LocalDate.now(clock).toString()): WeightEntity? {
        DomainDates.validateFactDate(DomainDates.parse(onOrBefore), LocalDate.now(clock))
        return withContext(Dispatchers.IO) { database.useReaderConnection { connection -> connection.deferredTransaction {
            dao.latestWeight(onOrBefore)?.also { app.diet.domain.WeightMeasurement(DomainDates.parse(it.localDate), it.centiKg.toLong()) }
        } } }
    }
    suspend fun pageReportDays(start: LocalDate, endInclusive: LocalDate, offset: Long = 0, limit: Int = 40): ReportDayPage = ReportQueries.pageReportDays(database, start, endInclusive, offset, limit)
    suspend fun catalogSeedV1Applied(): Boolean = writer.withLock {
        dao.getReceipt(CATALOG_SEED_V1)?.let { if (it.kind != "catalog-seed-v1") throw DataFailure.Conflict(); true } ?: false
    }
    /** Ensure a versioned installation marker, independent of later bundled manifest bytes. */
    suspend fun seedCatalogOnce(items: List<CatalogItemEntity>, categories: List<CategoryEntity>, sourceHash: String): CommitResult = writer.withLock {
        val result = database.useWriterConnection { connection -> connection.immediateTransaction {
            val installed = dao.getReceipt(CATALOG_SEED_V1)
            if (installed != null) {
                if (installed.kind != "catalog-seed-v1") throw DataFailure.Conflict()
                return@immediateTransaction CommitResult(CATALOG_SEED_V1, dao.results(CATALOG_SEED_V1).map { it.entityId }, true)
            }
            require(sourceHash.matches(Regex("[0-9a-f]{64}")))
            require(items.isNotEmpty() && items.size <= 500 && items.map { it.id }.toSet().size == items.size)
            categories.forEach { uuid(it.id); require(dao.getCategory(it.id) == null); TextRules.name(it.name, 40); dao.put(it) }
            items.forEach { item ->
                uuid(item.id); require(dao.getCatalogItem(item.id) == null && item.kind == "product" && item.revision == 1L)
                requireNotNull(item.input).validate(); item.snapshot.toDomain(); require(item.sourceFdcId != null && item.sourceDescription != null && item.sourceRelease != null && item.sourceVerifiedRevision == 1L)
                require(item.snapshot.toDomain().nutrientsPerBasis == item.input.toDomain())
                dao.put(item)
            }
            dao.put(ReceiptEntity(CATALOG_SEED_V1, "catalog-seed-v1", fingerprint("catalog-seed-v1", listOf(sourceHash)), null, null, clock.millis()))
            items.forEach { dao.put(OperationResultEntity(operationId = CATALOG_SEED_V1, entityKind = "catalog", entityId = it.id)) }
            CommitResult(CATALOG_SEED_V1, items.map { it.id }, false)
        } }
        try { refresh(knownCommit = true) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Installed marker remains durable even when its UI cannot refresh. */ }
        result
    }
    suspend fun discardDraft(id: String, expectedRevision: Long) = writer.withLock { database.useWriterConnection { c -> c.immediateTransaction {
        val old = dao.getDraft(id) ?: throw DataFailure.Missing(); revision(old.draftRevision, expectedRevision); if (old.status != "OPEN") throw DataFailure.ClosedDraft()
        dao.put(old.copy(status = "DISCARDED", rawPayload = "{}", draftRevision = increment(old.draftRevision), updatedAtMillis = clock.millis())); dao.clearDraftMedia(id)
    } } }
    companion object {
        private val sharedWriter = Mutex()
        private const val CATALOG_SEED_V1 = "d1e70000-0000-4000-8000-000000000001"
        private val MEDIA_DRAFT_TABLES = setOf("media_assets", "drafts", "draft_media")
        private val DOMAIN_TABLES = arrayOf("local_profile", "onboarding_state", "meals", "catalog_categories", "catalog_items", "catalog_recents", "food_entries", "food_day_confirmations", "goals", "goal_timeline_state", "weight_records", "sleep_records", "day_moments", "photos", "media_assets", "meal_templates", "meal_plans", "quick_portion_groups", "quick_portion_values", "reminder_specs", "drafts", "draft_media")
    }
}
