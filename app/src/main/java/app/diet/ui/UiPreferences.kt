package app.diet.ui

import androidx.compose.runtime.*
import app.diet.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class UiPreferenceSession(val repository: VisualPreferencesRepository, val scope: CoroutineScope, initial: PreferenceDraft, exists: Boolean) {
    var draft by mutableStateOf(initial); private set
    var fields by mutableStateOf(initial.rawFields.filterKeys { !it.startsWith("__") }); private set
    var token by mutableStateOf(initial.rawFields["__operation"] ?: newId()); private set
    var busy by mutableStateOf(false)
    var uncertain by mutableStateOf(initial.rawFields["__pending"] == "true"); private set
    var obsolete by mutableStateOf(false); private set
    val editable: Boolean get() = !busy && !uncertain && !obsolete
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf(if (exists) "Черновик настроек восстановлен" else "Предпросмотр")
    private var saved = exists
    private var acknowledgedFields = initial.rawFields
    private var pending = initial.rawFields["__pending"] == "true"
    private var job: Job? = null
    private val mutex = Mutex()
    fun change(key: String, value: String) {
        if (!editable || fields[key] == value) return
        fields = fields + (key to value); token = newId(); pending = false; error = null; message = "Сохраняем черновик…"
        job?.cancel(); job = scope.launch { delay(350); try { checkpoint() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = app.diet.messages.userMessage(e, "Черновик настроек не сохранён") } }
    }
    suspend fun checkpoint() = mutex.withLock {
        val raw = fields + mapOf("__operation" to token, "__pending" to pending.toString())
        val latest = repository.freshPreferenceDraft(draft.group)
        if (latest?.id == draft.id) {
            if (latest.status != "OPEN") throw DataFailure.ClosedDraft()
            if (latest.baseRevision != draft.baseRevision) throw DataFailure.Conflict()
            val samePayload = latest.rawFields["__operation"] == token && latest.rawFields.filterKeys { !it.startsWith("__") } == fields
            if (latest.draftRevision != draft.draftRevision && latest.rawFields != acknowledgedFields && !samePayload) throw DataFailure.Conflict()
            if (latest.rawFields == raw) { draft = latest; acknowledgedFields = raw; saved = true; message = "Черновик сохранён на устройстве"; return@withLock }
        } else if (saved || latest?.status == "OPEN") throw DataFailure.Conflict()
        draft = repository.checkpoint(draft.copy(rawFields = raw), latest?.takeIf { it.id == draft.id }?.draftRevision)
        acknowledgedFields = raw; saved = true; message = "Черновик сохранён на устройстве"
    }
    suspend fun recover(): Boolean = try {
        repository.recoverPreference(draft.group, token, fields, draft.baseRevision).also { found -> if (!found) { uncertain = false; pending = false }; obsolete = false }
    } catch (e: DataFailure.Conflict) {
        obsolete = true; uncertain = false; error = "Этот редактор устарел. Настройки изменились другим действием; закройте редактор и откройте актуальные значения."; throw e
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { uncertain = true; error = "Результат сохранения не проверен. Поля и ID удерживаются до повторной проверки."; throw e }
    suspend fun apply() {
        job?.cancelAndJoin()
        if (recover()) return
        pending = true; uncertain = true
        try { checkpoint(); repository.apply(draft.group, fields, draft.baseRevision, token, draft.id, draft.draftRevision); uncertain = false }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (recover()) return
            pending = false; uncertain = false; throw e
        }
    }
    suspend fun discard() { job?.cancelAndJoin(); if (saved) repository.discard(draft.group, draft.id, draft.draftRevision) }
    companion object {
        fun open(h: UiHost, group: String, fields: Map<String, String>): UiPreferenceSession {
            require(group !in h.preferences.unsupportedDraftGroups) { "Черновик этой группы из новой версии; файл сохранён" }
            val old = h.preferences.drafts.firstOrNull { it.group == group }
            return UiPreferenceSession(h.visual, h.scope, old ?: PreferenceDraft(newId(), group, h.preferences.revisions[group] ?: 1, rawFields = fields), old != null)
        }
    }
}
