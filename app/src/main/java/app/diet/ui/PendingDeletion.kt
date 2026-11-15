package app.diet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class PendingDeletion(val action: String, val id: String?, val revision: Long?, val fields: Map<String, String>, val editorDraftId: String?, val editorRevision: Long?, val editorKind: String?, val editorDate: String?)

internal fun UiHost.queueDeletion(action: String, id: String?, revision: Long?, fields: Map<String, String>) {
    if (pendingDeletion != null || pendingPhotoRemoval != null || deleting) { message = "Сначала отмените или дождитесь текущего удаления"; return }
    if (form?.editable == false) return
    val editor = form
    editor?.busy = true
    launch {
        try {
            val checkpoint = editor?.checkpoint(); editor?.stopDebounce()
            if (form === editor && editor != null) leaveForm()
            pendingDeletion = PendingDeletion(action, id, revision, fields, checkpoint?.id, checkpoint?.draftRevision, editor?.entity?.kind, editor?.data?.get("date"))
        } finally { editor?.busy = false }
    }
}

internal fun UiHost.undoDeletion() { if (pendingDeletion != null) { pendingDeletion = null; message = "Удаление отменено" } }

internal fun UiHost.commitDeletion(pending: PendingDeletion) {
    if (pendingDeletion !== pending || deleting) return
    pendingDeletion = null; deleting = true
    // Independent command: the countdown must never replace a newly opened editor.
    launch {
        val session = UiFormSession.create(repository, scope, "command", pending.id, pending.revision, pending.fields + ("action" to pending.action))
        try {
            val op = session.freeze()
            commitUiForm(this, session, op)
            val draftId = pending.editorDraftId
            if (draftId != null && form?.entity?.id != draftId) {
                // Only the captured version may be cleaned up; a reopened draft belongs to the user.
                try { repository.discardDraft(draftId, requireNotNull(pending.editorRevision)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep a newer or already closed draft unchanged. */ }
            }
            message = "Удалено"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            try {
                if (session.recover() != null) message = "Удалено"
                else { session.notCommitted(); error = app.diet.messages.userMessage(e, "Удаление не выполнено. Данные сохранены; повторите действие.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Не удалось проверить удаление. Черновик действия сохранён; откройте его в «Ещё → Черновики»." }
        } finally { session.stopDebounce(); deleting = false }
    }
}

@Composable internal fun PendingDeletionNotice(h: UiHost) {
    val pending = h.pendingDeletion
    val photo = h.pendingPhotoRemoval
    if (pending == null && photo == null) return
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val accessibility = LocalAccessibilityManager.current
    val timeout = accessibility?.calculateRecommendedTimeoutMillis(8_000, containsText = true, containsControls = true) ?: 8_000
    LaunchedEffect(pending, photo, resumed, h.form) {
        if (photo != null && h.form !== photo.session) {
            h.pendingPhotoRemoval = null
            h.message = "Удаление фото отменено"
        } else if (resumed) {
            delay(timeout)
            if (pending != null) h.commitDeletion(pending)
            else if (photo != null && h.pendingPhotoRemoval === photo) {
                h.pendingPhotoRemoval = null
                h.removeMomentPhoto(photo.session, photo.assetId)
            }
        }
    }
    Surface(Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(if (photo != null) "Фото будет убрано через несколько секунд" else "Удаление через несколько секунд", color = MaterialTheme.colorScheme.inverseOnSurface)
            TextButton(onClick = { if (photo != null) { h.pendingPhotoRemoval = null; h.message = "Удаление фото отменено" } else h.undoDeletion() }) { Text("Отменить удаление", color = MaterialTheme.colorScheme.inversePrimary) }
        }
    }
}


internal data class PendingPhotoRemoval(val session: UiFormSession, val assetId: String)

internal fun UiHost.queuePhotoRemoval(session: UiFormSession, assetId: String) {
    if (pendingDeletion != null || pendingPhotoRemoval != null || deleting || form !== session || !session.editable) return
    pendingPhotoRemoval = PendingPhotoRemoval(session, assetId)
}
