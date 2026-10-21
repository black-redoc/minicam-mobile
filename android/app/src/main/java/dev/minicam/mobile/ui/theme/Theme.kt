package dev.minicam.mobile.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens mirrored from minicam `app/static/css/main.css`
 * (docs/ARCHITECTURE.md "Theme").
 */
object CamColors {
    val Bg = Color(0xFF0F172A)
    val Surface = Color(0xFF1E293B)
    val Surface2 = Color(0xFF263348)
    val Border = Color(0xFF334155)
    val Accent = Color(0xFF0EA5E9)
    val AccentHover = Color(0xFF38BDF8)
    val Danger = Color(0xFFEF4444)
    val Success = Color(0xFF22C55E)
    val Text = Color(0xFFF8FAFC)
    val Muted = Color(0xFF94A3B8)

    /** `text-slate-950` on the sky primary button. */
    val OnAccent = Color(0xFF020617)

    /** `.ptz-btn--stop` background. */
    val PtzStop = Color(0xFF1F2937)
}

object CamDimens {
    val RadiusSm = 6.dp
    val Radius = 8.dp
    val RadiusLg = 12.dp
    val PtzSize = 56.dp
    val CardBorder = BorderStroke(1.dp, CamColors.Border)
}

private val colorScheme = darkColorScheme(
    primary = CamColors.Accent,
    onPrimary = CamColors.OnAccent,
    secondary = CamColors.AccentHover,
    background = CamColors.Bg,
    onBackground = CamColors.Text,
    surface = CamColors.Surface,
    onSurface = CamColors.Text,
    surfaceVariant = CamColors.Surface2,
    onSurfaceVariant = CamColors.Muted,
    outline = CamColors.Border,
    error = CamColors.Danger,
)

/** Dark only, like the desktop (`color-scheme: dark`). */
@Composable
fun MinicamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, content = content)
}
