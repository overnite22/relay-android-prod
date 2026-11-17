package io.github.overnite22.relay.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.overnite22.relay.kit.EntryKind

/** Bleu Relay (accent de l'interface web). */
val RelayAccent = Color(0xFF2B7DE9)
val RelayGreen = Color(0xFF3FB950)
val RelayRed = Color(0xFFE5534B)
val RelayYellow = Color(0xFFE3B341)

private val Light = lightColorScheme(
    primary = RelayAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E6FB),
    secondaryContainer = Color(0xFFD8E6FB),
    onPrimaryContainer = Color(0xFF0A2A57),
    secondary = Color(0xFF52627A),
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFF6F7F9),
    surfaceContainer = Color(0xFFFFFFFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5B9BF0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF173A6B),
    secondaryContainer = Color(0xFF173A6B),
    onPrimaryContainer = Color(0xFFD8E6FB),
    secondary = Color(0xFF9AA8BD),
    background = Color(0xFF0E1116),
    surface = Color(0xFF0E1116),
    surfaceContainer = Color(0xFF161B22),
)

@Composable
fun RelayTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

val EntryKind.color: Color
    get() = when (this) {
        EntryKind.RDP -> Color(0xFF297DE8)
        EntryKind.SSH -> Color(0xFF40BA4F)
        EntryKind.VNC -> Color(0xFFA370F7)
        EntryKind.WEB -> Color(0xFFE67000)
        EntryKind.PVE -> Color(0xFFE65C00)
        EntryKind.OTHER -> Color.Gray
    }
