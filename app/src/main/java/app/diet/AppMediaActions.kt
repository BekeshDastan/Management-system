package app.diet

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.diet.data.PhotoInput
import app.diet.data.newId
import app.diet.media.PhotoEngine
import app.diet.media.PhotoFailure
import app.diet.messages.userMessage
import app.diet.ui.UiMediaActions
import app.diet.ui.UiMediaState
import app.diet.ui.UiOpenAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class AppMediaActions(private val app: DietApplication) : UiMediaActions {
    private val mutable = MutableStateFlow(UiMediaState())
    override val state: StateFlow<UiMediaState> = mutable.asStateFlow()
    private data class Selection(val draftId: String, val date: String, val freeSlots: Int, val ticket: String = newId())
    @Volatile private var pending: Selection? = null
    @Volatile private var importing: Job? = null
    private var owner: String? = null
    private var launchPicker: ((Int) -> Unit)? = null
    private var launchPermission: (() -> Unit)? = null

    fun attach(id: String, picker: (Int) -> Unit, permission: () -> Unit) { owner = id; launchPicker = picker; launchPermission = permission }
    fun detach(id: String) { if (owner == id) { owner = null; launchPicker = null; launchPermission = null } }

    override fun pickPhotos(draftId: String, date: String, freeSlots: Int) {
        if (state.value.busy) return
        if (freeSlots <= 0) { mutable.update { it.copy(error = "На дату можно добавить до четырёх фото.") }; return }
        mutable.update { it.copy(draftId = draftId, busy = true, error = null, photos = emptyList()) }
        val request = Selection(draftId, date, freeSlots.coerceAtMost(4))
        pending = request
        app.backgroundScope.launch {
            try {
                val draft = app.repository.freshDraft(draftId) ?: error("Черновик недоступен")
                require(draft.status == "OPEN" && draft.selectedLocalDate == date) { "Редактор изменился. Выберите фото заново." }
                val actualSlots = 4 - app.repository.media.draftAssets(draftId).size
                require(actualSlots == request.freeSlots) { "Набор фото изменился. Повторите выбор." }
                withContext(Dispatchers.Main) { if (pending == request) (launchPicker ?: error("Вернитесь в приложение для выбора фото"))(request.freeSlots) }
            } catch (e: Exception) {
                if (pending == request) {
                    pending = null
                    mutable.update { it.copy(busy = false, error = userMessage(e, "Не удалось открыть выбор фото. Повторите попытку.")) }
                }
            }
        }
    }

    fun selected(uris: List<Uri>) {
        val request = pending ?: return
        pending = null
        if (uris.isEmpty()) { mutable.update { it.copy(busy = false) }; return }
        if (uris.size > request.freeSlots) { mutable.update { it.copy(busy = false, error = "Выбрано больше свободных мест. Повторите выбор целиком.") }; return }
        importing = app.backgroundScope.launch {
            val priorAssets = runCatching { app.repository.media.draftPhotoInputs(request.draftId).map { it.assetId }.toSet() }.getOrDefault(emptySet())
            try {
                app.photos.import(request.draftId, request.date, uris)
                val photos = app.repository.media.draftPhotoInputs(request.draftId)
                mutable.update { it.copy(draftId = request.draftId, photos = photos, error = null) }
            } catch (_: CancellationException) {
                // A cancellation can race the atomic READY checkpoint. Resolve IDs, never
                // call a successfully published batch lost merely because a callback was lost.
                val recovered = withContext(NonCancellable) {
                    runCatching { withTimeout(5_000) {
                        val draft = app.repository.freshDraft(request.draftId)
                        if (draft?.status == "OPEN") app.repository.media.draftPhotoInputs(request.draftId) else emptyList()
                    } }.getOrNull()
                }
                val ready = recovered?.takeIf { refs -> refs.any { it.assetId !in priorAssets } }
                mutable.update { current ->
                    if (ready != null) current.copy(draftId = request.draftId, photos = ready, error = null)
                    else current.copy(error = if (recovered == null) "Не удалось проверить черновик фото. Откройте его заново." else "Добавление фото прервано. Выберите их снова.")
                }
            }
            catch (_: OutOfMemoryError) { mutable.update { it.copy(error = "Недостаточно памяти для обработки фото. Прежние данные сохранены.") } }
            catch (e: Exception) { mutable.update { it.copy(error = userMessage(e, "Не удалось подготовить фото. Проверьте формат и повторите.")) } }
            finally { mutable.update { it.copy(busy = false) }; importing = null }
        }
    }

    override fun cancelImport(draftId: String) {
        if (state.value.draftId != draftId) return
        pending = null
        app.photos.cancelImport()
        if (importing == null) mutable.update { it.copy(busy = false) }
    }
    fun hostStopped() { if (pending == null && importing != null) cancelImport(state.value.draftId ?: return) }
    override fun clearError() { mutable.update { it.copy(error = null) } }

    override fun requestNotifications() { launchPermission?.invoke() ?: mutable.update { it.copy(error = "Вернитесь в приложение для разрешения уведомлений") } }
    override fun openNotificationSettings() { app.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun refreshNotifications() {
        val (allowed, text) = app.reminders.status()
        mutable.update { it.copy(notificationEnabled = allowed, notificationStatus = text, notificationChannels = listOf("food", "weight", "sleep").associateWith { kind -> app.reminders.channelAllowed(kind) }) }
    }
    fun opened(kind: String?) {
        if (kind !in setOf("food", "weight", "sleep")) return
        mutable.update { it.copy(openAction = UiOpenAction(newId(), requireNotNull(kind), LocalDate.now().toString())) }
    }
    override fun consumeOpenAction(id: String) { mutable.update { if (it.openAction?.id == id) it.copy(openAction = null) else it } }

    @Composable override fun Thumbnail(assetId: String, modifier: Modifier, description: String) = app.photos.Thumbnail(assetId, modifier, description)
    @Composable override fun Viewer(assetId: String, modifier: Modifier, description: String) {
        val loaded by produceState<Result<PhotoEngine.BitmapHandle>?>(null, assetId) {
            var handle: PhotoEngine.BitmapHandle? = null
            try { val decoded = app.photos.decodeViewer(assetId); handle = decoded; value = Result.success(decoded) }
            catch (e: CancellationException) { handle?.close(); throw e }
            catch (e: OutOfMemoryError) { value = Result.failure(PhotoFailure.Memory()) }
            catch (e: Exception) { value = Result.failure(e) }
            awaitDispose { handle?.close() }
        }
        val handle = loaded?.getOrNull()
        if (handle != null) Image(handle.bitmap.asImageBitmap(), description, modifier, contentScale = ContentScale.Fit)
        else Text(if (loaded?.isFailure == true) "Фото недоступно или повреждено" else "Открываем фото…", modifier)
    }
}
