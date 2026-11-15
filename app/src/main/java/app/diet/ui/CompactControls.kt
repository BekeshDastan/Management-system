package app.diet.ui

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Equal width tabs with the selected underline; text can grow with the system font. */
@Composable internal fun FullWidthTabs(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.Bottom) {
            options.forEach { (key, label) ->
                val active = selected == key
                Column(Modifier.weight(1f).selectable(active, onClick = { onSelect(key) }, role = Role.Tab), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 2.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(thickness = 3.dp, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A date remains an ISO value in storage, while the interface uses a calendar caption. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DateChoice(label: String, value: String, min: LocalDate, max: LocalDate, onChange: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
        Icon(Icons.Outlined.CalendarMonth, contentDescription = null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(dateLabel(value), style = MaterialTheme.typography.titleMedium)
        }
        Text("▾")
    }
    if (open) {
        val russian = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag("ru-RU")) }
        CompositionLocalProvider(LocalConfiguration provides russian) {
            val initial = runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf { it in min..max }
            val allowed = remember(min, max) { object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() in min..max
                override fun isSelectableYear(year: Int): Boolean = year in min.year..max.year
            } }
            val state = rememberDatePickerState(initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(), yearRange = min.year..max.year, selectableDates = allowed)
            AdaptiveDialog("Выбрать дату", label, { open = false }) {
                // On narrow screens the calendar scrolls horizontally rather than clipping days.
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    DatePicker(state = state, modifier = Modifier.width(360.dp).semantics { contentDescription = "Календарь: $label" }, title = null, showModeToggle = false,
                        headline = {
                            val caption = state.selectedDateMillis?.let { dateLabel(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) } ?: "Выберите день"
                            Text(caption, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall)
                        })
                }
                Action("Выбрать", enabled = state.selectedDateMillis != null) {
                    state.selectedDateMillis?.let { millis ->
                        val next = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        if (next in min..max) { onChange(next.toString()); open = false }
                    }
                }
                Action("Отмена", secondary = true) { open = false }
            }
        }
    }
}
