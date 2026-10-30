package cassandraclient.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cassandraclient.ui.res.Res
import cassandraclient.ui.res.inter_regular
import cassandraclient.ui.res.inter_semibold
import cassandraclient.ui.res.jetbrains_mono_bold
import cassandraclient.ui.res.jetbrains_mono_regular
import org.jetbrains.compose.resources.Font

@Immutable
data class AppColors(
    val isDark: Boolean,
    val bg: Color,
    val bg2: Color,
    val bg3: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val ok: Color,
    val warn: Color,
    val warnSoft: Color,
    val changed: Color,
    val newRow: Color,
    val selected: Color,
    val nullText: Color,
    val syntaxKeyword: Color,
    val syntaxType: Color,
    val syntaxString: Color,
    val syntaxNumber: Color,
)

val LightColors = AppColors(
    isDark = false,
    bg = Color(0xFFFFFFFF),
    bg2 = Color(0xFFF7F8FA),
    bg3 = Color(0xFFEEF0F4),
    border = Color(0xFFE3E6EB),
    borderStrong = Color(0xFFCFD4DC),
    text = Color(0xFF1C2230),
    muted = Color(0xFF6B7385),
    accent = Color(0xFF2563EB),
    accentSoft = Color(0xFFE8EFFF),
    danger = Color(0xFFDC2626),
    dangerSoft = Color(0xFFFDECEC),
    ok = Color(0xFF16A34A),
    warn = Color(0xFFB45309),
    warnSoft = Color(0xFFFFF7E6),
    changed = Color(0xFFFFF4C2),
    newRow = Color(0xFFEAFAF0),
    selected = Color(0xFFEEF3FF),
    nullText = Color(0xFFA0A7B5),
    syntaxKeyword = Color(0xFF7C3AED),
    syntaxType = Color(0xFF0E7490),
    syntaxString = Color(0xFF15803D),
    syntaxNumber = Color(0xFFB45309),
)

val DarkColors = AppColors(
    isDark = true,
    bg = Color(0xFF15181F),
    bg2 = Color(0xFF1B1F27),
    bg3 = Color(0xFF242936),
    border = Color(0xFF2B313D),
    borderStrong = Color(0xFF3A4252),
    text = Color(0xFFE3E7EF),
    muted = Color(0xFF8B93A5),
    accent = Color(0xFF5B8CFF),
    accentSoft = Color(0xFF1F2B4A),
    danger = Color(0xFFF26B6B),
    dangerSoft = Color(0xFF3A1F23),
    ok = Color(0xFF4ADE80),
    warn = Color(0xFFF5B84B),
    warnSoft = Color(0xFF3A2F1A),
    changed = Color(0xFF4A4019),
    newRow = Color(0xFF17321F),
    selected = Color(0xFF1E2A44),
    nullText = Color(0xFF5F6778),
    syntaxKeyword = Color(0xFFC792EA),
    syntaxType = Color(0xFF56B6C2),
    syntaxString = Color(0xFF98C379),
    syntaxNumber = Color(0xFFD19A66),
)

@Immutable
data class AppFonts(val sans: FontFamily, val mono: FontFamily)

val LocalColors = staticCompositionLocalOf { LightColors }
val LocalFonts = staticCompositionLocalOf { AppFonts(FontFamily.SansSerif, FontFamily.Monospace) }

val colors: AppColors
    @Composable get() = LocalColors.current

val Radius = RoundedCornerShape(6.dp)

/** "system" follows the OS; Linux desktops rarely report a preference, hence the manual override. */
@Composable
fun AppTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkColors else LightColors
    val fonts = AppFonts(
        sans = FontFamily(
            Font(Res.font.inter_regular, FontWeight.Normal),
            Font(Res.font.inter_semibold, FontWeight.SemiBold),
        ),
        mono = FontFamily(
            Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
            Font(Res.font.jetbrains_mono_bold, FontWeight.SemiBold),
        ),
    )
    val scrollbar = ScrollbarStyle(
        minimalHeight = 24.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 150,
        unhoverColor = palette.borderStrong,
        hoverColor = palette.muted,
    )
    CompositionLocalProvider(
        LocalColors provides palette,
        LocalFonts provides fonts,
        LocalScrollbarStyle provides scrollbar,
        content = content,
    )
}

/** Parses the `#rrggbb` colors stored on connection profiles. */
fun parseHexColor(hex: String?, fallback: Color = Color(0xFF64748B)): Color {
    val value = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000 or value)
}
