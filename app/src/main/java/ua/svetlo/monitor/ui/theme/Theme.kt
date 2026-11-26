package ua.svetlo.monitor.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import ua.svetlo.monitor.R

/** Цвета статусов. Одинаковые в светлой и тёмной теме, чтобы карта читалась одинаково. */
object Status {
    val Green = Color(0xFF2BB673)
    val Yellow = Color(0xFFF5B800)
    val Maybe = Color(0xFFFBE7A1)
    val Red = Color(0xFFE5383B)
    val Black = Color(0xFF1C1C1F)
    val Blue = Color(0xFF3B82F6)
    val Orange = Color(0xFFF97316)
    val Forecast = Color(0xFFD9D7CF)
    val NoData = Color(0xFFD9D6CD)
}

@Immutable
data class SvetloColors(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val line: Color,
    val divider: Color,
    val track: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val greenSoft: Color,
    val greenInk: Color,
    val yellowSoft: Color,
    val yellowInk: Color,
    val redSoft: Color,
    val redInk: Color,
    val blackSoft: Color,
    val mapBg: Color,
    val mapRoad: Color,
    val mapPark: Color,
    val mapWater: Color,
    val inverse: Color,
    val onInverse: Color,
)

val LightColors = SvetloColors(
    dark = false,
    bg = Color(0xFFF6F5F1), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF6F5F1),
    ink = Color(0xFF16181D), ink2 = Color(0xFF4A4E57), muted = Color(0xFF5B5F68),
    line = Color(0xFFE4E2DA), divider = Color(0xFFF1EFE8), track = Color(0xFFECEAE3),
    accent = Color(0xFF4F46E5), accentSoft = Color(0xFFEEEDFC), onAccent = Color.White,
    greenSoft = Color(0xFFDDF4E6), greenInk = Color(0xFF166B3E),
    yellowSoft = Color(0xFFFDF0C2), yellowInk = Color(0xFF6B4E00),
    redSoft = Color(0xFFFCE1E1), redInk = Color(0xFFA3181B),
    blackSoft = Color(0xFFE4E3E0),
    mapBg = Color(0xFFEEECE6), mapRoad = Color(0xFFFFFFFF), mapPark = Color(0xFFDCEBD5), mapWater = Color(0xFFCFE0EE),
    // Выделение выбранного: мягкий нейтрально-индиговый вместо чёрного.
    inverse = Color(0xFFE4E2F8), onInverse = Color(0xFF2E2899),
)

val DarkColors = SvetloColors(
    dark = true,
    bg = Color(0xFF0F1014), surface = Color(0xFF1A1C22), surface2 = Color(0xFF22252C),
    ink = Color(0xFFF2F2F4), ink2 = Color(0xFFC9CCD3), muted = Color(0xFFA0A4AE),
    line = Color(0xFF2A2D35), divider = Color(0xFF2A2D35), track = Color(0xFF2A2D35),
    accent = Color(0xFF8B85FF), accentSoft = Color(0xFF24233F), onAccent = Color(0xFF0F1014),
    greenSoft = Color(0xFF12301F), greenInk = Color(0xFF7FE0A9),
    yellowSoft = Color(0xFF3A3010), yellowInk = Color(0xFFFFD866),
    redSoft = Color(0xFF3A1416), redInk = Color(0xFFFFB3B4),
    blackSoft = Color(0xFF2A2D35),
    mapBg = Color(0xFF15171C), mapRoad = Color(0xFF2A2D35), mapPark = Color(0xFF16251B), mapWater = Color(0xFF14212E),
    inverse = Color(0xFF363A4A), onInverse = Color(0xFFF2F2F4),
)

// Статические начертания, нарезанные из вариативных шрифтов: на части телефонов вариативный шрифт
// из ресурсов кэшируется одним начертанием, и весь текст становится тонким.

/** Onest: заголовки и цифры. */
val Onest = FontFamily(
    Font(R.font.onest_500, FontWeight.Medium), Font(R.font.onest_600, FontWeight.SemiBold),
    Font(R.font.onest_700, FontWeight.Bold), Font(R.font.onest_800, FontWeight.ExtraBold),
)

/** Manrope: основной текст. */
val Manrope = FontFamily(
    Font(R.font.manrope_400, FontWeight.Normal), Font(R.font.manrope_500, FontWeight.Medium), Font(R.font.manrope_600, FontWeight.SemiBold),
    Font(R.font.manrope_700, FontWeight.Bold), Font(R.font.manrope_800, FontWeight.ExtraBold),
)

object Type {
    val display = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp)
    val h1 = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp)
    val h2 = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp)
    val number = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
    val bigNumber = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-0.6).sp)
    val section = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyBold = body.copy(fontWeight = FontWeight.Bold)
    val small = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val smallBold = small.copy(fontWeight = FontWeight.Bold)
    val caption = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp)
    val tiny = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp)
}

val LocalColors = staticCompositionLocalOf { LightColors }

/** true — включён режим крупного шрифта (упрощённая главная, контрастные цвета). */
val LocalLargeText = staticCompositionLocalOf { false }

enum class ThemeMode { System, Light, Dark }

@Composable
fun SvetloTheme(
    mode: ThemeMode = ThemeMode.System,
    largeText: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val c = if (dark) DarkColors else LightColors
    val m3 = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, surface = c.surface,
        onBackground = c.ink, onSurface = c.ink, outline = c.line, surfaceVariant = c.surface2,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, surface = c.surface,
        onBackground = c.ink, onSurface = c.ink, outline = c.line, surfaceVariant = c.surface2,
    )
    val density = LocalDensity.current
    // Крупный шрифт в приложении работает поверх системного масштаба шрифта.
    val scaled = if (largeText) Density(density.density, density.fontScale * 1.25f) else density
    // Значки строки состояния и навигации — тёмные на светлой теме и светлые на тёмной.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    CompositionLocalProvider(
        LocalColors provides c,
        LocalLargeText provides largeText,
        LocalDensity provides scaled,
    ) {
        MaterialTheme(colorScheme = m3, content = content)
    }
}

object Svetlo {
    val colors: SvetloColors @Composable get() = LocalColors.current
}
