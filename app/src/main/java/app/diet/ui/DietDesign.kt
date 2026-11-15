package app.diet.ui

import android.provider.Settings
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diet.data.VisualPreferences
import app.diet.domain.Nutrients

internal data class DietColors(val forest: Color, val lime: Color, val peach: Color, val lavender: Color, val ocean: Color)
internal val LocalDietColors = staticCompositionLocalOf { DietColors(Color(0xFF163F2E), Color(0xFFD2EF75), Color(0xFFF4D4B4), Color(0xFFE9E0F7), Color(0xFFDCE9EE)) }
internal val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun DietTheme(preferences: VisualPreferences, preview: Map<String, String>? = null, content: @Composable () -> Unit) {
    val dark = when (preferences.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val accent = preview?.get("accent") ?: preferences.accent
    val primary = when (accent) { "berry" -> if (dark) Color(0xFFF0BAD3) else Color(0xFF823F61); "ocean" -> if (dark) Color(0xFFA5D9E8) else Color(0xFF236678); else -> if (dark) Color(0xFFC2E88C) else Color(0xFF275E43) }
    val soft = when (accent) { "berry" -> if (dark) Color(0xFF483240) else Color(0xFFF2E2E9); "ocean" -> if (dark) Color(0xFF29434B) else Color(0xFFE0EDF0); else -> if (dark) Color(0xFF304532) else Color(0xFFE9EEDC) }
    val scheme = if (dark) darkColorScheme(primary = primary, onPrimary = Color(0xFF193125), primaryContainer = soft, onPrimaryContainer = Color(0xFFF0F2E6), background = Color(0xFF17251E), onBackground = Color(0xFFF0F2E6), surface = Color(0xFF22332A), onSurface = Color(0xFFF0F2E6), surfaceVariant = soft, onSurfaceVariant = Color(0xFFB2C0AE), outline = Color(0xFF8DA78F), error = Color(0xFFFFB0A6))
        else lightColorScheme(primary = primary, onPrimary = Color.White, primaryContainer = soft, onPrimaryContainer = Color(0xFF1C382B), background = Color(0xFFF5F5EB), onBackground = Color(0xFF1C382B), surface = Color(0xFFFFFEF8), onSurface = Color(0xFF1C382B), surfaceVariant = soft, onSurfaceVariant = Color(0xFF4D6253), outline = Color(0xFF697D6B), error = Color(0xFFA03D37))
    val context = LocalContext.current
    val window = remember(context) { context.dietActivity()?.window }
    SideEffect { window?.let { WindowCompat.getInsetsController(it, it.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } } }
    val reduced = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDietColors provides DietColors(Color(0xFF163F2E), Color(0xFFD2EF75), if (dark) Color(0xFF5A4330) else Color(0xFFF4D4B4), if (dark) Color(0xFF423A50) else Color(0xFFE9E0F7), if (dark) Color(0xFF2B444B) else Color(0xFFDCE9EE)), LocalReducedMotion provides reduced,
        LocalDensity provides Density(density.density, density.fontScale * if (preferences.largeText) 1.15f else 1f)) {
        MaterialTheme(colorScheme = scheme.copy(secondary = primary, onSecondary = scheme.onPrimary, secondaryContainer = soft, onSecondaryContainer = scheme.onPrimaryContainer), shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(16.dp), medium = RoundedCornerShape(22.dp), large = RoundedCornerShape(28.dp)), typography = Typography(
            displaySmall = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.8).sp),
            headlineLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-1).sp),
            headlineSmall = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-.6).sp),
            titleLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
            titleMedium = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp), bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp), labelSmall = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.sp)), content = content)
    }
}

private tailrec fun Context.dietActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> if (baseContext === this) null else baseContext.dietActivity(); else -> null }

@Composable
internal fun DietCard(modifier: Modifier = Modifier, title: String? = null, subtitle: String? = null, color: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(25.dp), color = color, tonalElevation = 0.dp) {
        Column(Modifier.padding(20.dp).animateContentSize(tween(if (LocalReducedMotion.current) 0 else 180)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
            subtitle?.let { Muted(it) }; content()
        }
    }
}

@Composable internal fun DietCard(title: String, subtitle: String? = null, color: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) = DietCard(modifier = Modifier, title = title, subtitle = subtitle, color = color, content = content)

@Composable internal fun Muted(text: String, modifier: Modifier = Modifier) = Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
@Composable internal fun Eyebrow(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
@Composable internal fun Pill(text: String, modifier: Modifier = Modifier) { Surface(modifier, color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) { Text(text, Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) } }
@Composable internal fun Action(text: String, enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    if (secondary) OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(16.dp)) { Text(text) }
    else Button(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(16.dp)) { Text(text) }
}
@Composable internal fun EmptyState(title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    DietCard(color = MaterialTheme.colorScheme.primaryContainer) { Text("✦", style = MaterialTheme.typography.headlineLarge); Text(title, style = MaterialTheme.typography.titleLarge); Muted(message); action?.let { Action(it, onClick = onAction) } }
}
@Composable internal fun ErrorNotice(message: String?) { message?.let { Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium) } } }
@Composable internal fun NutritionText(value: Nutrients?, compact: Boolean = false) {
    if (value == null) Muted("Пока нет данных") else {
        Text("${value.kcal.format(1)} ккал", style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall)
        Muted("Б ${value.protein.format(1)} · Ж ${value.fat.format(1)} · У ${value.carbs.format(1)} г")
    }
}

/** Local, scalable decorative fallback. It is never a personal food photo. */
@Composable internal fun CoverArt(cover: String, modifier: Modifier = Modifier, title: String = "Маленькие шаги.\nВаш ритм.") {
    val palette = listOf(Color(0xFFF4D4B4), Color(0xFFE4E5AF), Color(0xFFDCE9EE), Color(0xFFE9E0F7), Color(0xFFE9D6BF), Color(0xFFD5E3C0))
    val tint = palette[cover.toIntOrNull()?.coerceIn(0, 5) ?: 5]
    val bitmap = ImageBitmap.imageResource(app.diet.R.drawable.food_editorial)
    val tile = cover.toIntOrNull()?.coerceIn(0, 5) ?: 5
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(tint, Color(0xFF365540))))) {
        Canvas(Modifier.matchParentSize()) {
            val sourceWidth = bitmap.width / 3; val sourceHeight = bitmap.height / 2
            // Crop a single decorative tile; never used as a journal asset.
            val ratio = size.width / size.height.coerceAtLeast(1f)
            val cropWidth = minOf(sourceWidth, (sourceHeight * ratio).toInt()).coerceAtLeast(1)
            val cropHeight = minOf(sourceHeight, (sourceWidth / ratio).toInt()).coerceAtLeast(1)
            drawImage(bitmap, srcOffset = IntOffset(tile % 3 * sourceWidth + (sourceWidth - cropWidth) / 2, tile / 3 * sourceHeight + (sourceHeight - cropHeight) / 2), srcSize = IntSize(cropWidth, cropHeight), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .08f), Color(0xFF123629).copy(alpha = .80f))))
        }
        Column(Modifier.padding(24.dp).padding(top = 52.dp, end = 50.dp)) { Text("DIET · ДЛЯ СЕБЯ", style = MaterialTheme.typography.labelSmall, color = Color(0xFFD2EF75)); Spacer(Modifier.height(12.dp)); Text(title, style = MaterialTheme.typography.headlineLarge, color = Color.White) }
    }
}

/** Project illustrations only: never diary facts, food values or personal photo assets. */
@Composable internal fun DecorativeFoodStrip() {
    val bitmap = ImageBitmap.imageResource(app.diet.R.drawable.food_classics)
    CollapsiblePanel("Немного вдохновения", "Проектные иллюстрации еды · 6 кадров") {
        Muted("Для настроения. Эти изображения не добавляются в дневник и не описывают пищевую ценность.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(6) { tile -> Canvas(Modifier.size(180.dp).clip(RoundedCornerShape(20.dp)).semantics { contentDescription = "Декоративная иллюстрация еды ${tile + 1}" }) {
                val w = bitmap.width / 3; val h = bitmap.height / 2; val side = minOf(w, h)
                drawImage(bitmap, srcOffset = IntOffset(tile % 3 * w + (w - side) / 2, tile / 3 * h + (h - side) / 2), srcSize = IntSize(side, side), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            } }
        }
    }
}
