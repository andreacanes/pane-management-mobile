package com.andreacanes.panemgmt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Forced dark theme that mirrors the Tauri desktop app's palette so the
 * two surfaces look like one product. The decorative themes from upstream
 * (witching-hour, neon-shinjuku) were dropped during the Phase 1 cleanup;
 * this is the single canonical scheme on both sides.
 *
 * Color tokens here are kept in sync with the desktop's index.css `:root`
 * block: indigo accent, deep navy surfaces, semantic status colors that
 * match `--status-running/waiting/done/idle`.
 */
private val PaneMgmtDarkColors = darkColorScheme(
    primary             = Color(0xFF818CF8), // accent indigo
    onPrimary           = Color(0xFF1A1A2E),
    primaryContainer    = Color(0xFF312E81),
    onPrimaryContainer  = Color(0xFFE0E0E0),
    secondary           = Color(0xFFA5B4FC),
    onSecondary         = Color(0xFF1A1A2E),
    secondaryContainer  = Color(0xFF2A2A4A),
    onSecondaryContainer = Color(0xFFE0E0E0),
    tertiary            = Color(0xFFF59E0B), // amber for warnings/Bravura
    onTertiary          = Color(0xFF1A1A2E),
    background          = Color(0xFF0E0E1C),
    onBackground        = Color(0xFFE0E0E0),
    surface             = Color(0xFF16162A),
    onSurface           = Color(0xFFE0E0E0),
    surfaceVariant      = Color(0xFF1E1E36),
    onSurfaceVariant    = Color(0xFF9A9AB8),
    surfaceContainerLowest  = Color(0xFF0A0A14),
    surfaceContainerLow     = Color(0xFF14142A),
    surfaceContainer        = Color(0xFF1E1E36),
    surfaceContainerHigh    = Color(0xFF28284A),
    surfaceContainerHighest = Color(0xFF32325A),
    outline             = Color(0xFF3A3A5C),
    outlineVariant      = Color(0xFF2A2A4A),
    error               = Color(0xFFFF6B6B),
    onError             = Color(0xFF1A1A2E),
    errorContainer      = Color(0xFF4A1F1F),
    onErrorContainer    = Color(0xFFFFD0D0),
)

@Composable
fun PaneMgmtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PaneMgmtDarkColors,
        content = content,
    )
}
