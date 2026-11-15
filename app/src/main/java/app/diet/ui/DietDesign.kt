package app.diet.ui

import android.provider.Settings
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.compose.ui.unit.TextUnit
import app.diet.data.VisualPreferences
import app.diet.domain.Nutrients

internal data class DietColors(val forest: Color, val lime: Color, val peach: Color, val lavender: Color, val ocean: Color)
internal val LocalDietColors = staticCompositionLocalOf { DietColors(Color(0xFF163F2E), Color(0xFFD2EF75), Color(0xFFF4D4B4), Color(0xFFE9E0F7), Color(0xFFDCE9EE)) }
internal val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun DietTheme(preferences: VisualPreferences, preview: Map<String, String>? = null, content: @Composable () -> Unit) {
    val dark = when (preferences.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    // The primary palette is fixed; appearance accents only tint secondary surfaces.
    val accent = preview?.get("accent") ?: preferences.accent
    val secondaryTint = when (accent) { "berry" -> if (dark) Color(0xFF332633) else Color(0xFFF2E2E9); "ocean" -> if (dark) Color(0xFF222D34) else Color(0xFFDCE9EE); else -> if (dark) Color(0xFF2C2826) else Color(0xFFF4E5D9) }
    val primary = if (dark) Color(0xFFB8F36B) else Color(0xFF3C6016)
    val soft = if (dark) Color(0xFF292D33) else Color(0xFFE7EDD9)
    val scheme = if (dark) darkColorScheme(
        primary = primary, onPrimary = Color(0xFF0E1013), primaryContainer = soft, onPrimaryContainer = Color.White,
        background = Color(0xFF0E1013), onBackground = Color.White,
        surface = Color(0xFF1D2025), onSurface = Color.White,
        surfaceVariant = Color(0xFF292D33), onSurfaceVariant = Color(0xFFA1A1AA),
        surfaceContainerLowest = Color(0xFF0E1013), surfaceContainerLow = Color(0xFF1D2025),
        surfaceContainer = Color(0xFF1D2025), surfaceContainerHigh = Color(0xFF292D33), surfaceContainerHighest = Color(0xFF292D33),
        surfaceDim = Color(0xFF0E1013), surfaceBright = Color(0xFF292D33),
        outline = Color(0xFF7E8590), outlineVariant = Color(0xFF393D43),
        secondary = Color(0xFF68C8F1), tertiary = Color(0xFFFFAB80),
        error = Color(0xFFFF9299), errorContainer = Color(0xFF49252C), onErrorContainer = Color(0xFFFFDADF)
    ) else lightColorScheme(primary = primary, onPrimary = Color.White, primaryContainer = soft, onPrimaryContainer = Color(0xFF243316), background = Color(0xFFF4F5F2), onBackground = Color(0xFF171B20), surface = Color.White, onSurface = Color(0xFF171B20), surfaceVariant = Color(0xFFE8EBE5), onSurfaceVariant = Color(0xFF565D66), outline = Color(0xFF717984), outlineVariant = Color(0xFFD1D6DC), secondary = Color(0xFF21677C), tertiary = Color(0xFF9B4A25), error = Color(0xFFA03D37))
    val context = LocalContext.current
    val window = remember(context) { context.dietActivity()?.window }
    SideEffect { window?.let { WindowCompat.getInsetsController(it, it.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } } }
    val reduced = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDietColors provides DietColors(scheme.surface, primary, secondaryTint, if (dark) Color(0xFF292735) else Color(0xFFE9E0F7), if (dark) Color(0xFF222D34) else Color(0xFFDCE9EE)), LocalReducedMotion provides reduced,
        LocalDensity provides Density(density.density, density.fontScale * if (preferences.largeText) 1.15f else 1f)) {
        MaterialTheme(colorScheme = scheme.copy(secondaryContainer = soft, onSecondaryContainer = scheme.onPrimaryContainer), shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)), typography = Typography(
            displaySmall = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.8).sp),
            headlineLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-1).sp),
            headlineSmall = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-.6).sp),
            titleLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
            titleMedium = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp), bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp), labelSmall = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.sp)).compact(), content = content)
    }
}

private fun Typography.compact(): Typography {
    fun TextUnit.small() = if (this == TextUnit.Unspecified) this else this * .88f
    fun androidx.compose.ui.text.TextStyle.small() = copy(fontSize = fontSize.small(), lineHeight = lineHeight.small(), letterSpacing = letterSpacing.small())
    return copy(displayLarge = displayLarge.small(), displayMedium = displayMedium.small(), displaySmall = displaySmall.small(),
        headlineLarge = headlineLarge.small(), headlineMedium = headlineMedium.small(), headlineSmall = headlineSmall.small(),
        titleLarge = titleLarge.small(), titleMedium = titleMedium.small(), titleSmall = titleSmall.small(),
        bodyLarge = bodyLarge.small(), bodyMedium = bodyMedium.small(), bodySmall = bodySmall.small(),
        labelLarge = labelLarge.small(), labelMedium = labelMedium.small(), labelSmall = labelSmall.small())
}

private tailrec fun Context.dietActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> if (baseContext === this) null else baseContext.dietActivity(); else -> null }

@Composable
internal fun DietCard(modifier: Modifier = Modifier, title: String? = null, subtitle: String? = null, color: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = color, tonalElevation = 0.dp) {
        Column(Modifier.padding(16.dp).animateContentSize(tween(if (LocalReducedMotion.current) 0 else 180)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
            subtitle?.let { Muted(it) }; content()
        }
    }
}

@Composable internal fun DietCard(title: String, subtitle: String? = null, color: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) = DietCard(modifier = Modifier, title = title, subtitle = subtitle, color = color, content = content)

@Composable internal fun Muted(text: String, modifier: Modifier = Modifier) = Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
@Composable internal fun Eyebrow(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
@Composable internal fun Pill(text: String, modifier: Modifier = Modifier) { Surface(modifier, color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) { Text(text, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) } }
@Composable internal fun Action(text: String, enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(if (pressed && !reduced) .98f else 1f, tween(if (reduced) 0 else 90), label = "button press")
    val haptic = LocalHapticFeedback.current
    val click = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).scale(scale)
    if (secondary) OutlinedButton(click, modifier, enabled = enabled, shape = RoundedCornerShape(12.dp), interactionSource = interaction) { Text(text) }
    else Button(click, modifier, enabled = enabled, shape = RoundedCornerShape(12.dp), interactionSource = interaction) { Text(text) }
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
        Column(Modifier.padding(24.dp).padding(top = 28.dp, end = 24.dp)) { Text("DIET · ДЛЯ СЕБЯ", style = MaterialTheme.typography.labelSmall, color = Color(0xFFD2EF75)); Spacer(Modifier.height(12.dp)); Text(title, style = MaterialTheme.typography.headlineLarge, color = Color.White) }
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
