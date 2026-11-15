package app.diet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.diet.data.newId
import app.diet.messages.userMessage
import app.diet.ui.DietApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app: DietApplication get() = application as DietApplication
    private val hostId = newId()
    private val singlePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> app.mediaActions.selected(listOfNotNull(uri)) }
    private val multiplePicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris -> app.mediaActions.selected(uris) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        app.backgroundScope.launch { app.reminders.reconcile(); app.mediaActions.refreshNotifications() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.mediaActions.attach(hostId, picker = { slots ->
            if (slots == 1) singlePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            else multiplePicker.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).setMaxItems(slots).build())
        }, permission = {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else if (!app.reminders.appAllowed() || !app.mediaActions.state.value.notificationEnabled)
                app.mediaActions.openNotificationSettings()
            else app.mediaActions.refreshNotifications()
        })
        acceptIntent(intent)
        setContent {
            var prepared by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var retry by remember { mutableIntStateOf(0) }
            LaunchedEffect(retry) {
                try { app.prepareUi(); prepared = true }
                catch (e: Exception) { error = userMessage(e, "Не удалось открыть локальные данные. Повторите запуск.") }
            }
            if (prepared) DietApp(app.repository, app.visualPreferences, app.mediaActions)
            else MaterialTheme {
                Surface {
                    Column(Modifier.fillMaxSize().padding(28.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                        Text("Diet", style = MaterialTheme.typography.displayMedium)
                        if (error == null) CircularProgressIndicator()
                        else { Text(requireNotNull(error)); TextButton(onClick = { error = null; retry++ }) { Text("Повторить") } }
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        app.backgroundScope.launch { app.reminders.reconcile(); app.mediaActions.refreshNotifications() }
    }
    override fun onStop() { if (!isChangingConfigurations) app.mediaActions.hostStopped(); super.onStop() }
    override fun onDestroy() { app.mediaActions.detach(hostId); super.onDestroy() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent) }
    private fun acceptIntent(intent: Intent?) {
        if (intent?.action == "app.diet.OPEN_REMINDER") { app.mediaActions.opened(intent.getStringExtra("open_form")); intent.removeExtra("open_form") }
    }
}
