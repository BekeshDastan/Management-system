package app.diet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.diet.widget.DietWidgetController

@Composable internal fun GuidanceInvite() {
    val h = LocalUi.current
    if (h.view["guide.dismissed"] != "true") DietCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Text("Освоимся за минуту?", style = MaterialTheme.typography.titleMedium)
        Muted("Три коротких шага: записать, посмотреть, настроить.")
        Row {
            TextButton(onClick = { h.context("guide.dismissed", "true"); h.navigate("guide") }) { Text("Показать") }
            TextButton(onClick = { h.context("guide.dismissed", "true") }) { Text("Позже") }
        }
    }
}

@Composable internal fun GuidanceScreen() {
    val h = LocalUi.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    ScreenHeading("Знакомство с Diet", "Можно пропустить и открыть снова в разделе «Ещё».")
    Choices(listOf("0" to "Записать", "1" to "Посмотреть", "2" to "Настроить"), step.toString()) { step = it.toInt() }
    DietCard(title = listOf("Добавление всегда рядом", "Ваш ритм на одном графике", "Главная под ваши привычки")[step], color = MaterialTheme.colorScheme.primaryContainer) {
        Text(listOf("＋", "▥", "✦")[step], style = MaterialTheme.typography.displaySmall)
        Text(listOf(
            "Нажмите «＋ Добавить еду». Поиск и «＋ Создать» закреплены сверху. «Выбрать несколько» собирает продукты в один приём; порции можно поправить перед сохранением.",
            "В «Прогрессе» выберите период и питание, вес или сон. Нажмите столбик, чтобы увидеть подробности дня. Пропуски остаются пропусками, а детали раскрываются по желанию.",
            "В «Ещё» можно переставить карточки главной, выбрать оформление и добавить виджет. После редактора вы вернётесь к прежнему месту. Удаление можно отменить в нижней плашке."
        )[step])
        Action(listOf("Попробовать добавить еду", "Открыть прогресс", "Настроить главную")[step]) {
            h.context("guide.dismissed", "true")
            h.navigate(listOf("add-food", "reports", "home-layout")[step])
        }
    }
    if (step < 2) Action("Следующий шаг", secondary = true) { step++ }
    Action(if (step == 2) "Готово" else "Пропустить", secondary = true) { h.context("guide.dismissed", "true"); h.back() }
}

@Composable internal fun WidgetSettingsCard() {
    val h = LocalUi.current
    val context = LocalContext.current
    DietCard("Diet на рабочем столе", "Сегодняшние показатели и быстрые кнопки еды, веса и сна.") {
        Action("Добавить виджет", secondary = true) {
            val requested = runCatching { DietWidgetController.requestPin(context) }.getOrDefault(false)
            h.message = if (requested) "Подтвердите добавление виджета на рабочий стол" else "Удерживайте пустое место на рабочем столе → Виджеты → Diet"
        }
        TextButton(onClick = { h.navigate("guide") }) { Text("Знакомство с приложением →") }
    }
}
