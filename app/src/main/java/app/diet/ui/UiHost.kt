package app.diet.ui

import androidx.compose.runtime.*
import app.diet.data.*
import app.diet.domain.FoodUnit
import app.diet.domain.Numbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

internal data class UiRoute(val name: String, val id: String? = null)
internal data class ReturnAnchor(val screen: String, val key: String, val y: Float)
internal data class UiConfirmation(val title: String, val message: String, val confirmLabel: String, val action: () -> Unit)
internal val LocalUi = staticCompositionLocalOf<UiHost> { error("Diet UI host missing") }

internal class UiHost(val repository: DietRepository, val visual: VisualPreferencesRepository, val media: UiMediaActions, val scope: CoroutineScope) {
    var state by mutableStateOf(repository.state.value)
    var preferences by mutableStateOf(visual.state.value)
    var mediaState by mutableStateOf(media.state.value)
    var route by mutableStateOf(UiRoute("home")); private set
    var date by mutableStateOf(today())
    var focusMealId by mutableStateOf<String?>(null)
    val anchorPositions = mutableMapOf<String, Float>()
    var returnAnchor: ReturnAnchor? = null
    val screenKey get() = route.name + ":" + (if (route.name == "editor") form?.entity?.id else route.id).orEmpty()
    var scrollResetVersion by mutableLongStateOf(0L)
    fun resetScroll() { scrollResetVersion++ }
    var form by mutableStateOf<UiFormSession?>(null)
    var openingForm by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var pendingDeletion by mutableStateOf<PendingDeletion?>(null)
    var deleting by mutableStateOf(false)
    var pendingPhotoRemoval by mutableStateOf<PendingPhotoRemoval?>(null)
    var confirmation by mutableStateOf<UiConfirmation?>(null)
    var pendingNavigation by mutableStateOf<(() -> Unit)?>(null)
    var preferencePreview by mutableStateOf<Map<String, String>?>(null)
    var preferenceForm by mutableStateOf<UiPreferenceSession?>(null)
    var view by mutableStateOf<Map<String, String>>(emptyMap())
    var viewLoaded = false
    var pendingOpenActionId: String? = null
    private var viewJob: Job? = null
    private val viewMutex = Mutex()
    private val stack = mutableStateListOf<UiRoute>()
    private var supersededForm: UiFormSession? = null
    private val directPreferences = mutableMapOf<String, UiPreferenceSession>()
    val activeMeals get() = state.meals.filterNot { it.archived }
    val defaultMeal get() = activeMeals.firstOrNull()?.id.orEmpty()
    val currentEntries get() = state.entries.filter { it.localDate == date }
    val canGoBack get() = stack.isNotEmpty()
    val activeRoot get() = (stack.toList() + route).lastOrNull { it.name in setOf("home", "plans", "reports", "settings") }?.name ?: "home"

    fun context(key: String, value: String) {
        view = view + (key to value)
        viewJob?.cancel(); viewJob = scope.launch { delay(350); try { viewMutex.withLock { visual.apply("view", view, visual.state.value.revisions["view"] ?: 1, newId()) } } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "Не удалось сохранить настройку экрана. Повторите действие." } }
    }
    fun preference(group: String, fields: Map<String, String>) {
        if (group in preferences.unsupportedDraftGroups) { error = "Черновик этой группы из новой версии. Он сохранён без изменений; откройте настройки для подробностей."; return }
        val session = directPreferences[group] ?: UiPreferenceSession.open(this, group, fields).also { directPreferences[group] = it }
        if (session.busy) return
        session.busy = true
        launch {
            try {
                // Settle an earlier token before changing this group's payload/token.
                if (session.recover()) { directPreferences.remove(group); message = "Ранее сохранённые настройки найдены"; return@launch }
                session.busy = false
                fields.forEach { (key, value) -> session.change(key, value) }
                session.busy = true; session.apply(); directPreferences.remove(group)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { session.error = session.error ?: app.diet.messages.userMessage(e); preferenceForm = session; push(UiRoute("preference-resume")) }
            finally { session.busy = false }
        }
    }
    fun forgetPreference(session: UiPreferenceSession) { val old = directPreferences[session.draft.group]; if (old === session || old?.draft?.id == session.draft.id && old?.token == session.token) directPreferences.remove(session.draft.group) }
    suspend fun mealRows(sourceDate: String, mealId: String): List<FoodEntryEntity> {
        app.diet.domain.DomainDates.validateFactDate(app.diet.domain.DomainDates.parse(sourceDate), LocalDate.now())
        val rows = mutableListOf<FoodEntryEntity>()
        while (true) {
            val page = repository.queryHistory(sourceDate, sourceDate, mealId = mealId, offset = rows.size.toLong(), limit = 100)
            rows += page
            require(rows.size <= 500) { "В одном действии можно добавить до 500 строк. Выберите меньший состав." }
            if (page.size < 100) break
        }
        return rows.sortedWith(compareBy<FoodEntryEntity> { it.ordinal }.thenBy { it.id })
    }

    fun launch(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e) } } }
    fun navigate(name: String, id: String? = null) {
        if (openingForm || form?.busy == true) return
        if (form?.uncertain == true || preferenceForm?.uncertain == true) { error = "Сначала проверьте результат предыдущего сохранения"; return }
        val next = { push(UiRoute(name, id)); Unit }
        if (form != null && route.name == "editor" || preferenceForm != null) pendingNavigation = next else next()
    }
    fun push(next: UiRoute) { stack.add(route); route = next; error = null }
    fun root(name: String) {
        if (openingForm || form?.busy == true) return
        if (form?.uncertain == true || preferenceForm?.uncertain == true) { error = "Сначала проверьте результат предыдущего сохранения"; return }
        val next = { stack.clear(); route = UiRoute(name); error = null; Unit }
        if (form != null && route.name == "editor" || preferenceForm != null) pendingNavigation = next else next()
    }
    fun back() {
        if (openingForm || form?.busy == true || preferenceForm?.busy == true) return
        if (preferenceForm?.obsolete == true) { preferenceForm?.let(::forgetPreference); preferenceForm = null; preferencePreview = null; pop(); return }
        if (form?.uncertain == true || preferenceForm?.uncertain == true) { error = "Сначала проверьте результат предыдущего сохранения"; return }
        if (route.name == "editor" && form != null || preferenceForm != null) pendingNavigation = { }
        else pop()
    }
    fun pop() { route = if (stack.isEmpty()) UiRoute("home") else stack.removeAt(stack.lastIndex); error = null }
    fun leaveForm() { form?.stopDebounce(); form = null; pop() }
    fun keepDraftAndLeave() = finishEditor(false)
    fun discardAndLeave() = finishEditor(true)
    private fun finishEditor(discard: Boolean) {
        val after = pendingNavigation ?: return; pendingNavigation = null
        val oldForm = form; val oldPreference = preferenceForm
        oldForm?.busy = true; oldPreference?.busy = true
        launch {
            try {
                if (discard) { oldForm?.discard(); if (oldPreference?.obsolete != true) oldPreference?.discard() } else { oldForm?.checkpoint(); oldForm?.stopDebounce(); if (oldPreference?.obsolete != true) oldPreference?.checkpoint() }
                form = null; preferenceForm = null; preferencePreview = null
                oldPreference?.let(::forgetPreference)
                if (oldForm != null && route.name in setOf("line-picker", "replace-picker", "template-picker", "batch-picker")) pop()
                if (route.name == "editor" || oldPreference != null) pop()
                after()
            } finally { oldForm?.busy = false; oldPreference?.busy = false }
        }
    }
    fun confirm(title: String, message: String, label: String = "Подтвердить", action: () -> Unit) { confirmation = UiConfirmation(title, message, label, action) }
    fun cancelNavigation() { pendingNavigation = null; pendingOpenActionId?.let { media.consumeOpenAction(it) }; pendingOpenActionId = null }
    fun openNotification(action: UiOpenAction) {
        if (openingForm) return
        if (pendingOpenActionId == action.id) return
        val accept = { date = action.localDate; if (action.kind == "food") navigate("add-food") else openForm(if (action.kind == "weight") "weight" else "sleep"); media.consumeOpenAction(action.id); pendingOpenActionId = null; Unit }
        if (state.onboarding?.completed != true) { message = "Сначала завершите первый запуск"; media.consumeOpenAction(action.id) }
        else if (form != null || preferenceForm != null) { pendingOpenActionId = action.id; pendingNavigation = accept }
        else accept()
    }

    fun resume(draft: DraftHeader) { resumeId(draft.id) }
    fun resumeId(id: String, verifiedFields: Map<String, String> = emptyMap()) { launch {
        val latest = repository.freshDraft(id) ?: throw DataFailure.Missing()
        if (pendingDeletion?.editorDraftId == id) undoDeletion()
        if (latest.status != "OPEN") {
            val receipt = latest.operationId?.let { repository.recover(it) }
            if (receipt != null && receipt.first.originDraftId == id) { message = "Это действие уже сохранено на устройстве"; return@launch }
            throw DataFailure.ClosedDraft()
        }
        val restored = UiFormSession.restore(repository, scope, latest)
        restored.requireRecovery(); form = restored
        push(UiRoute("editor"))
        checkFormOutcome(verifiedFields)
    } }

    fun openForm(kind: String, id: String? = null, meal: String = defaultMeal, extra: Map<String, String> = emptyMap(), lines: List<UiLine>? = null) { launch {
        if (openingForm || form?.editable == false) return@launch
        if (pendingDeletion != null && (id != null && pendingDeletion?.id == id || kind in setOf("weight", "sleep") && pendingDeletion?.editorKind == kind && pendingDeletion?.editorDate == date)) undoDeletion()
        if (route.name == "home" && kind in setOf("weight", "sleep", "moment")) {
            val key = screenKey + ":" + kind
            anchorPositions[key]?.let { returnAnchor = ReturnAnchor(screenKey, key, it) }
        }
        openingForm = true
        try {
        val selectedDate = date
        val contextualMeal = kind in setOf("portion", "quick", "copy", "apply-template", "transfer", "plan", "template", "batch-food")
        val useDate = when { id != null && kind !in setOf("portion", "apply-template", "transfer") -> false; kind in setOf("profile", "onboarding-profile", "onboarding-goal", "product", "recipe", "category", "meal") -> false; else -> true }
        val existing = repository.findOpenDraft(kind, id, if (useDate) selectedDate else null, if (useDate && contextualMeal) meal else null)
        if (existing != null) { resumeId(existing.id, if (kind == "moment") extra.filterKeys { it == "photoDefaultMeal" } else emptyMap()); return@launch }
        // Notification/calendar selection can precede the composable's scoped-date effect.
        // Read this date explicitly before taking any revision/measurement/photo baseline.
        if (useDate) repository.setVisibleDate(selectedDate)
        var source = repository.state.value
        if (id != null) when (kind) {
            "product", "recipe", "portion", "quick-portions" -> { val item = repository.loadCatalogItem(id) ?: throw DataFailure.Missing(); source = source.copy(catalog = source.catalog.filterNot { it.id == id } + item, ingredients = if (kind == "recipe") repository.recipeIngredients(id) else source.ingredients) }
            "entry", "move-entry", "repeat-entry" -> { val item = repository.loadEntry(id) ?: throw DataFailure.Missing(); source = source.copy(entries = source.entries.filterNot { it.id == id } + item) }
            "template", "apply-template" -> { val item = repository.loadTemplate(id) ?: throw DataFailure.Missing(); source = source.copy(templates = source.templates.filterNot { it.id == id } + item, templateLines = repository.templateLines(id)) }
            "plan", "transfer" -> { val item = repository.loadPlan(id) ?: throw DataFailure.Missing(); source = source.copy(plans = source.plans.filterNot { it.id == id } + item, planLines = repository.planLines(id)) }
        }
        prepareForm(kind, id, meal, extra, lines, source, selectedDate)
        } finally { openingForm = false }
    } }

    private fun prepareForm(kind: String, id: String?, meal: String, extra: Map<String, String>, lines: List<UiLine>?, source: DietState, selectedDate: String) {
        val state = source
        val date = selectedDate
        var base: Long? = null
        var initial = mapOf("date" to date, "meal" to meal)
        var initialLines = lines ?: emptyList()
        var initialPhotos = emptyList<UiPhoto>()
        when (kind) {
            "move-entry", "repeat-entry" -> {
                val old = state.entries.find { it.id == id } ?: return
                base = old.revision
                initial += mapOf("date" to old.localDate, "meal" to (old.mealId.takeIf { candidate -> activeMeals.any { it.id == candidate } } ?: defaultMeal), "name" to old.displayName())
                initialLines = listOf(UiLine.from(repository.foodAsLine(old)))
            }
            "profile", "onboarding-profile" -> state.profile?.let { p -> base = p.revision; initial += mapOf("name" to p.name, "age" to (p.age?.toString() ?: ""), "height" to (p.heightCentiCm?.let { decimal(it.toLong(), 2) } ?: ""), "weight" to (p.initialWeightCentiKg?.let { decimal(it.toLong(), 2) } ?: ""), "direction" to (p.direction ?: "maintain"), "onboardingRevision" to (state.onboarding?.revision ?: 1).toString()) }
            "weight" -> { val old = state.weights.find { it.localDate == date }; base = old?.revision; initial += "weight" to (old?.centiKg?.let { decimal(it.toLong(), 2) } ?: "") }
            "sleep" -> { val old = state.sleeps.find { it.localDate == date }; base = old?.revision; initial += mapOf("hours" to (old?.minutes?.div(60)?.toString() ?: ""), "minutes" to (old?.minutes?.rem(60)?.toString() ?: ""), "zero" to "false") }
            "goal", "onboarding-goal" -> {
                val old = state.goals.find { it.id == id }; base = old?.revision
                val start = old?.startDate ?: if (kind == "onboarding-goal") today() else date
                initial += microsFields(old?.nutrients) + mapOf("date" to start, "timeline" to state.goalTimelineRevision.toString(), "goalBaseRevision" to old?.revision?.toString().orEmpty(), "goalPreviewDate" to start, "goalReplacementConfirmed" to (old != null || state.goals.none { it.startDate == start }).toString())
                if (kind == "onboarding-goal") initial += "initialToday" to today()
            }
            "product", "recipe" -> { val old = state.catalog.find { it.id == id }; base = old?.revision; initial += microsFields(old?.input) + mapOf("name" to (old?.name ?: ""), "unit" to (old?.unit ?: "GRAM"), "portion" to (old?.portionName ?: ""), "category" to (old?.categoryId ?: ""), "yield" to (old?.yieldMicros?.let(::decimal) ?: "")); if (kind == "recipe" && old != null) initialLines = state.ingredients.filter { it.recipeId == id }.sortedBy { it.ordinal }.map { UiLine.from(LineInput(it.snapshot, it.quantityMicros, it.sourceItemId)) } }
            "portion" -> { val item = state.catalog.find { it.id == id } ?: return; initialLines = listOf(UiLine.from(LineInput(item.snapshot, item.snapshot.basisMicros, item.id))); initial += "item" to item.id }
            "entry" -> { val old = state.entries.find { it.id == id } ?: return; base = old.revision; initial += mapOf("date" to old.localDate, "meal" to old.mealId, "quick" to (old.kind == "quick").toString(), "name" to old.displayName()); if (old.kind == "quick") initial += microsFields(old.quick) else initialLines = listOf(UiLine.from(repository.foodAsLine(old))) }
            "quick" -> initial += microsFields(null) + ("name" to "")
            "template", "apply-template" -> { val old = state.templates.find { it.id == id }; base = old?.revision; initial += "name" to (old?.name ?: ""); if (old != null) initialLines = state.templateLines.filter { it.templateId == id }.sortedBy { it.ordinal }.map { UiLine.from(LineInput(it.snapshot, it.quantityMicros, it.sourceItemId)) } }
            "plan", "transfer" -> { val old = state.plans.find { it.id == id }; base = old?.revision; initial += mapOf("name" to (old?.name ?: ""), "date" to (if (kind == "plan") old?.planDate ?: date else date), "meal" to (old?.mealId?.takeIf { m -> activeMeals.any { it.id == m } } ?: meal)); if (old != null) initialLines = state.planLines.filter { it.planId == id }.sortedBy { it.ordinal }.map { UiLine.from(LineInput(it.snapshot, it.quantityMicros, it.sourceItemId)) } }
            "copy" -> initial += mapOf("sourceDate" to maxOf(app.diet.domain.DomainDates.MIN, LocalDate.parse(date).minusDays(1)).toString(), "sourceMeal" to meal)
            "moment" -> { val old = state.moments.find { it.localDate == date }; base = old?.revision; initial += mapOf("note" to (old?.note ?: ""), "mood" to (old?.mood ?: ""), "momentMediaRevision" to (old?.mediaRevision?.toString() ?: "")); initialPhotos = state.photos.filter { it.momentId == old?.id }.sortedBy { it.ordinal }.map { UiPhoto(it.id, it.assetId, it.caption, it.mealId) } }
            "meal" -> { val old = state.meals.find { it.id == id }; base = old?.revision; initial += mapOf("name" to (old?.name ?: ""), "archived" to (old?.archived ?: false).toString()) }
            "category" -> { val old = state.categories.find { it.id == id }; base = old?.revision; initial += "name" to (old?.name ?: "") }
            "quick-portions" -> { val item = state.catalog.find { it.id == id } ?: return; val group = state.quickPortionGroups.find { it.itemId == id && it.unit == item.unit && it.basisMicros == item.snapshot.basisMicros && it.unitLabel == item.portionName.orEmpty() }; base = group?.revision; val values = state.quickPortions.filter { it.groupId == group?.id }.sortedBy { it.ordinal }; initial += (0..4).associate { "amount$it" to (values.getOrNull(it)?.quantityMicros?.let(::decimal) ?: "") }; initial += "name" to item.name }
            "reminder" -> { val old = state.reminders.find { it.id == id } ?: return; base = old.revision; initial += mapOf("kind" to old.kind, "enabled" to old.enabled.toString(), "time" to "%02d:%02d".format(old.minuteOfDay / 60, old.minuteOfDay % 60), "days" to old.weekDaysMask.toString()) }
        }
        initial += extra
        val session = UiFormSession.create(repository, scope, kind, id, base, initial, initialLines, initialPhotos)
        if (kind == "moment") session.busy = true
        form = session; push(UiRoute("editor"))
        if (kind == "batch-food") push(UiRoute("batch-picker"))
        if (kind == "moment") { launch { try { session.checkpoint(); ensureMomentRefs(session); session.checkpoint() } catch (e: CancellationException) { throw e } catch (_: Exception) { session.unknown("Не удалось подготовить связи фотографий. Проверьте состояние перед изменением или сохранением.") } finally { session.busy = false } } }
    }

    fun saveForm() {
        if (pendingPhotoRemoval != null) { message = "Дождитесь удаления фото или отмените его"; return }
        val session = form ?: return
        if (session.busy || mediaState.busy && mediaState.draftId == session.entity.id) return
        if (session.uncertain) { checkFormOutcome(); return }
        session.busy = true; session.error = null
        launch {
            try {
                if (session.entity.kind in setOf("goal", "onboarding-goal")) {
                    require(goalConfirmationError(session, state) == null) { goalConfirmationError(session, state).orEmpty() }
                }
                if (session.entity.kind == "transfer") {
                    val previous = repository.queryTransfers(requireNotNull(session.entity.targetEntityId), 0, 1).firstOrNull()
                    if (previous != null && session.data["repeatAcknowledged"] != previous.id) {
                        session.busy = false
                        confirm("Этот план уже переносили", "Последний перенос: ${dateLabel(previous.localDate)}. Продолжение добавит ещё один самостоятельный набор еды за ${dateLabel(session.data["date"])}; прежние строки останутся.", "Добавить повторно") { session.field("repeatAcknowledged", previous.id); saveForm() }
                        return@launch
                    }
                }
                if (session.entity.kind == "moment") ensureMomentRefs(session)
                val operation = session.freeze()
                finishSaved(session, commitUiForm(this, session, operation))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                try {
                    val recovered = session.recover()
                    if (recovered != null) finishSaved(session, recovered.let { (receipt, ids) -> CommitResult(receipt.operationId, ids.map { it.entityId }, true) })
                    else session.notCommitted(app.diet.messages.userMessage(e, "Не удалось сохранить. Проверьте поля и повторите."))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { session.unknown("Результат сохранения пока неизвестен. Проверьте его перед правкой или новым сохранением.") }
            }
            finally { session.busy = false }
        }
    }

    fun convertDeferredGoal() {
        val session = form ?: return
        if (!session.editable || session.entity.kind != "onboarding-goal" || state.onboarding?.completed != true) return
        session.busy = true
        launch {
            try {
                val recovered = session.recover()
                if (recovered != null) finishSaved(session, CommitResult(recovered.first.operationId, recovered.second.map { it.entityId }, true))
                else { session.busy = false; session.fields(mapOf("manualAfterOnboarding" to "true", "goalPreviewDate" to "", "goalReplacementConfirmed" to "false")); session.checkpoint() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { session.unknown("Не удалось проверить прежнее сохранение цели. Повторите проверку перед преобразованием.") }
            finally { session.busy = false }
        }
    }

    private suspend fun ensureMomentRefs(session: UiFormSession) {
        // Persisted removal intent wins over stale picker emissions and surviving refs.
        // Detach only after its raw checkpoint is durable; a restart retries this cleanup.
        var checkpoint = session.checkpoint()
        val removed = repository.media.draftAssets(checkpoint.id).filter { it.id in session.data.removedAssets }
        for (asset in removed) {
            checkpoint = repository.freshDraft(checkpoint.id) ?: throw DataFailure.Missing()
            repository.media.removeDraftAsset(checkpoint.id, checkpoint.mediaRevision, asset.id)
        }
        val owned = session.data.photos.mapNotNull { photo -> repository.loadPhoto(photo.id)?.takeIf { it.assetId == photo.assetId }?.assetId }
        val latest = session.checkpoint()
        repository.media.ensureExistingDraftAssets(latest.id, latest.mediaRevision, owned)
    }

    fun removeMomentPhoto(session: UiFormSession, assetId: String) {
        if (form !== session || !session.editable || mediaState.busy && mediaState.draftId == session.entity.id) return
        session.busy = true
        launch {
            try {
                session.recordPhotoRemovalIntent(assetId)
                session.checkpoint()
                ensureMomentRefs(session)
                session.checkpoint()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { session.unknown("Не удалось завершить удаление фото из черновика. Проверьте состояние перед новым действием.") }
            finally { session.busy = false }
        }
    }

    fun checkFormOutcome(verifiedFields: Map<String, String> = emptyMap()) {
        val session = form ?: return
        if (session.busy) return
        session.busy = true
        launch {
            try {
                val recovered = session.recover()
                if (recovered != null) finishSaved(session, recovered.let { (receipt, ids) -> CommitResult(receipt.operationId, ids.map { it.entityId }, true) })
                else {
                    session.notCommitted()
                    if (session.entity.kind == "moment") {
                        ensureMomentRefs(session)
                        val refs = repository.media.draftAssets(session.entity.id).filter { it.state == "READY" }
                        session.busy = false
                        session.fields(verifiedFields)
                        session.mergePhotos(refs.map { PhotoInput(assetId = it.id) })
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { session.unknown("Не удалось проверить результат. Поля заблокированы; повторите проверку.") }
            finally { session.busy = false }
        }
    }

    private suspend fun finishSaved(session: UiFormSession, result: CommitResult) {
        require(result.operationId == session.data.operationId)
        if (session.entity.kind == "command") { supersededForm?.let { old -> try { old.discard() } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "Действие сохранено, но старый черновик не удалён. Его можно удалить отдельно." } }; supersededForm = null }
        session.stopDebounce(); form = null; message = if (result.replayed) "Ранее сохранённое действие найдено; повторной записи нет" else "Сохранено на устройстве"
        if (session.entity.kind == "onboarding-profile") { stack.clear(); route = UiRoute("onboarding-goals") }
        else if (session.entity.kind == "onboarding-goal" && session.data["manualAfterOnboarding"] != "true" || session.entity.kind == "onboarding-skip") { stack.clear(); route = UiRoute("home") }
        else if (session.entity.kind in setOf("portion", "quick", "batch-food", "move-entry", "repeat-entry")) { date = session.data["date"]; focusMealId = session.data["meal"]; stack.clear(); route = UiRoute("home") }
        else {
            pop()
            if (session.entity.kind == "moment" && route.name == "photo-detail" && session.data.photos.none { it.id == route.id }) {
                pop()
                if (route.name != "photo-feed") { stack.clear(); route = UiRoute("photo-feed") }
                message = "Фото удалено. Остальные моменты сохранены."
            }
        }
    }

    fun command(action: String, id: String? = null, revision: Long? = null, fields: Map<String, String> = emptyMap()) {
        if (action.startsWith("delete-")) { queueDeletion(action, id, revision, fields); return }
        if (form?.editable == false) return
        val replacingEditor = route.name == "editor"
        supersededForm = form; supersededForm?.stopDebounce()
        form = UiFormSession.create(repository, scope, "command", id, revision, fields + ("action" to action))
        if (!replacingEditor) push(UiRoute("editor")); saveForm()
    }
}

internal suspend fun commitUiForm(h: UiHost, s: UiFormSession, op: Operation): CommitResult {
    val r = h.repository; val d = s.data; val e = s.entity; val id = e.targetEntityId; val rev = e.baseRevision
    return when (e.kind) {
        "profile" -> r.saveProfile(d["name"], Numbers.parseOptionalAge(d["age"]), Numbers.parseOptionalHeightCentiCm(d["height"])?.toInt(), Numbers.parseOptionalWeightCentiKg(d["weight"])?.toInt(), d["direction"].ifEmpty { null }, requireNotNull(rev), op)
        "onboarding-profile" -> r.saveOnboardingProfile(d["name"], Numbers.parseOptionalAge(d["age"]), Numbers.parseOptionalHeightCentiCm(d["height"])?.toInt(), Numbers.parseOptionalWeightCentiKg(d["weight"])?.toInt(), d["direction"].ifEmpty { null }, requireNotNull(rev), d["onboardingRevision"].toLong(), op)
        "weight" -> r.saveWeight(d["date"], Numbers.parseWeightCentiKg(d["weight"]).toInt(), rev, op)
        "sleep" -> r.saveSleep(d["date"], Numbers.parseSleepMinutes(d["hours"], d["minutes"], d["zero"] == "true"), d["zero"] == "true", rev, op)
        "goal" -> r.saveGoal(d["date"], microNutrients(d, true), d["goalBaseRevision"].toLongOrNull(), d["timeline"].toLong(), op)
        "onboarding-goal" -> if (d["manualAfterOnboarding"] == "true") r.saveDeferredOnboardingGoal(d["date"], microNutrients(d, true), d["goalBaseRevision"].toLongOrNull(), d["timeline"].toLong(), op) else r.completeOnboardingWithGoal(d["date"], microNutrients(d, true), d["timeline"].toLong(), op)
        "onboarding-skip" -> r.completeOnboarding(op)
        "product" -> r.saveProduct(id, d["name"], FoodUnit.valueOf(d["unit"]), d["portion"].takeIf { d["unit"] == "PORTION" }, microNutrients(d), d["category"].ifEmpty { null }, rev, op)
        "recipe" -> r.saveRecipe(id, d["name"], Numbers.parseQuantityMicros(d["yield"]), d.lines.map(UiLine::input), d["category"].ifEmpty { null }, rev, op)
        "batch-food" -> r.addFoods(d["date"], d["meal"], d.lines.map(UiLine::input), op)
        "move-entry" -> r.moveFood(requireNotNull(id), requireNotNull(rev), d["date"], d["meal"], op)
        "repeat-entry" -> r.repeatFood(requireNotNull(id), requireNotNull(rev), d["date"], d["meal"], op)
        "portion" -> r.addFood(d["date"], d["meal"], d.lines.single().input(), op)
        "quick" -> r.addQuickFood(d["date"], d["meal"], d["name"], microNutrients(d), op)
        "entry" -> if (d["replace"] == "true") r.replaceFoodSnapshot(requireNotNull(id), d.lines.single().input(), requireNotNull(rev), op) else r.editFood(requireNotNull(id), if (d["quick"] == "true") null else d.lines.single().input().quantityMicros, if (d["quick"] == "true") microNutrients(d) else null, if (d["quick"] == "true") d["name"] else null, requireNotNull(rev), op)
        "template" -> r.saveTemplate(id, d["name"], d.lines.map(UiLine::input), rev, op)
        "apply-template" -> r.applyTemplate(requireNotNull(id), requireNotNull(rev), d["date"], d["meal"], op)
        "plan" -> r.savePlan(id, d["name"], d["date"], d["meal"], d.lines.map(UiLine::input), rev, op)
        "transfer" -> r.transferPlan(requireNotNull(id), requireNotNull(rev), d["date"], d["meal"], op)
        "copy" -> { require(d["sourceDate"] == d["loadedDate"] && d["sourceMeal"] == d["loadedMeal"]) { "Обновите предпросмотр источника" }; r.copyMeal(d["sourceDate"], d["sourceMeal"], d["date"], d["meal"], d["sourceVersions"].split(';').filter { it.isNotEmpty() }.map { it.substringBefore(':') to it.substringAfter(':').toLong() }, op) }
        "moment" -> r.saveMomentWithPhotos(d["date"], d["mood"].ifEmpty { null }, d["note"], d.photos.map(UiPhoto::input), rev, d["momentMediaRevision"].toLongOrNull(), op)
        "meal" -> r.saveMeal(id, d["name"], d["archived"] == "true", rev, op)
        "category" -> r.saveCategory(id, d["name"], rev, op)
        "quick-portions" -> r.saveQuickPortions(requireNotNull(id), (0..4).mapNotNull { d["amount$it"].takeIf(String::isNotBlank)?.let(Numbers::parseQuantityMicros) }, rev, op)
        "reminder" -> { require(d["time"].matches(Regex("([01][0-9]|2[0-3]):[0-5][0-9]"))) { "Введите время ЧЧ:ММ" }; r.saveReminder(requireNotNull(id), d["enabled"] == "true", d["time"].substringBefore(':').toInt() * 60 + d["time"].substringAfter(':').toInt(), d["days"].toInt(), requireNotNull(rev), op) }
        "command" -> when (d["action"]) {
            "favorite" -> r.toggleFavorite(requireNotNull(id), d["favorite"] == "true", requireNotNull(rev), op)
            "delete-food" -> r.deleteFood(requireNotNull(id), requireNotNull(rev), op)
            "delete-weight" -> r.deleteWeight(requireNotNull(id), requireNotNull(rev), op)
            "delete-sleep" -> r.deleteSleep(requireNotNull(id), requireNotNull(rev), op)
            "delete-catalog" -> r.deleteCatalogItem(requireNotNull(id), requireNotNull(rev), op)
            "delete-template" -> r.deleteTemplate(requireNotNull(id), requireNotNull(rev), op)
            "delete-plan" -> r.deletePlan(requireNotNull(id), requireNotNull(rev), op)
            "delete-goal" -> r.deleteGoal(requireNotNull(id), requireNotNull(rev), d["timeline"].toLong(), op)
            "delete-category" -> r.deleteCategory(requireNotNull(id), requireNotNull(rev), op)
            "complete-day" -> r.completeDay(d["date"], d["completed"] == "true", d["empty"] == "true", op)
            "reset-portions" -> r.resetQuickPortions(requireNotNull(id), rev, op)
            "reorder-meals" -> r.reorderMeals(d["order"].split(';').map { it.substringBefore(':') to it.substringAfter(':').toLong() }, op)
            else -> error("Неизвестное действие. Черновик сохранён.")
        }
        else -> error("Неизвестная форма. Черновик сохранён.")
    }
}
