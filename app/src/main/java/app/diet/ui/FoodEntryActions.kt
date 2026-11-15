package app.diet.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.diet.data.FoodEntryEntity

/** The same operations in the diary and search always target this immutable entry ID/revision. */
@Composable internal fun FoodEntryActions(entry: FoodEntryEntity) {
    val h = LocalUi.current
    var expanded by remember(entry.id) { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "Действия с ${entry.displayName()}" }) { Text("•••") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(if (entry.kind == "quick") "Изменить запись" else "Изменить порцию") }, onClick = { expanded = false; h.openForm("entry", entry.id) })
            DropdownMenuItem(text = { Text("Повторить порцию") }, onClick = { expanded = false; h.openForm("repeat-entry", entry.id) })
            DropdownMenuItem(text = { Text("Перенести в другой приём") }, onClick = { expanded = false; h.openForm("move-entry", entry.id) })
            DropdownMenuItem(text = { Text("Удалить запись") }, onClick = {
                expanded = false
                h.confirm("Удалить ${entry.displayName()}?", "Удаление можно отменить в появившейся плашке. Остальные записи сохранятся.", "Удалить") {
                    h.command("delete-food", entry.id, entry.revision)
                }
            })
        }
    }
}
