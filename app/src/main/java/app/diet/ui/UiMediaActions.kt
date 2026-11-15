package app.diet.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.diet.data.PhotoInput
import kotlinx.coroutines.flow.StateFlow

data class UiOpenAction(val id: String, val kind: String, val localDate: String)

/** Owns only prepared asset IDs/metadata; never bytes, Bitmap or provider URI. */
data class UiMediaState(
    val draftId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val photos: List<PhotoInput> = emptyList(),
    val notificationEnabled: Boolean = false,
    val notificationChannels: Map<String, Boolean> = emptyMap(),
    val notificationStatus: String = "Уведомления выключены",
    val openAction: UiOpenAction? = null,
)

/** Implemented by the application: actual picker, bounded decoder and system permissions. */
interface UiMediaActions {
    val state: StateFlow<UiMediaState>
    fun pickPhotos(draftId: String, date: String, freeSlots: Int)
    fun cancelImport(draftId: String)
    fun clearError()
    fun requestNotifications()
    fun openNotificationSettings()
    fun consumeOpenAction(id: String)

    @Composable
    fun Thumbnail(assetId: String, modifier: Modifier, description: String)

    @Composable
    fun Viewer(assetId: String, modifier: Modifier, description: String) = Thumbnail(assetId, modifier, description)
}
