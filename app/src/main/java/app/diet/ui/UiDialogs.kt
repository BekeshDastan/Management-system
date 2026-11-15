package app.diet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One bounded scroll surface: long text and large fonts never push an action outside it. */
@Composable internal fun AdaptiveDialog(title: String, message: String, onDismiss: () -> Unit, actions: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    actions()
                }
            }
        }
    }
}

@Composable internal fun EditorExitDialog(h: UiHost) {
    AdaptiveDialog("Выйти из редактора?", "Сохраните черновик, чтобы продолжить позже. В дневник он пока не добавляется.", h::cancelNavigation) {
        Action("Продолжить редактирование", onClick = h::cancelNavigation)
        Action("Оставить черновик", secondary = true, onClick = h::keepDraftAndLeave)
        TextButton(onClick = h::discardAndLeave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Удалить черновик и выйти", color = MaterialTheme.colorScheme.error)
        }
    }
}
