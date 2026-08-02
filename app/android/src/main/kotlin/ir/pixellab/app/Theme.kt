package ir.pixellab.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The editor's chrome.
 *
 * Dark, and only dark. This is not a preference: a light interface around a design makes its colours
 * read darker than they are, and every professional tool is dark for that reason. The greys are
 * deliberately **neutral** — no blue or warm cast — because a tinted surround shifts colour
 * judgement on the artwork itself, which is the whole reason the user is looking at the screen.
 *
 * The one saturated colour is the accent, used only for selection and the active tool, so that
 * nothing on screen competes with the work.
 */
object Ink {
    /** Behind the canvas. Mid grey rather than black, so white and black artwork both read. */
    val Surround = Color(0xFF2A2A2A)

    val Chrome = Color(0xFF121212)
    val ChromeRaised = Color(0xFF1E1E1E)
    val ChromeSunken = Color(0xFF0B0B0B)

    val Accent = Color(0xFF4C8DFF)
    val OnAccent = Color(0xFF06121F)

    val Text = Color(0xFFECECEC)
    val TextMuted = Color(0xFF9A9A9A)
    val Divider = Color(0xFF303030)
    val Danger = Color(0xFFFF5C5C)

    /** Snap guides; a colour nothing in a design is likely to be. */
    val Guide = Color(0xFFFF3FB4)
}

private val scheme = darkColorScheme(
    primary = Ink.Accent,
    onPrimary = Ink.OnAccent,
    background = Ink.Chrome,
    onBackground = Ink.Text,
    surface = Ink.ChromeRaised,
    onSurface = Ink.Text,
    surfaceVariant = Ink.ChromeSunken,
    onSurfaceVariant = Ink.TextMuted,
    outline = Ink.Divider,
    error = Ink.Danger,
)

@Composable
fun PixelLabTheme(content: @Composable () -> Unit) {
    // The system setting is read and ignored on purpose; a light editor is not offered.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
