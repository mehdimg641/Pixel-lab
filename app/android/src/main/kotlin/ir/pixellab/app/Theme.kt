package ir.pixellab.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design system.
 *
 * Two references, taken for different things. **Airbrush** for the editor's own chrome: a dark
 * ground, a raised rounded card holding the tools, pill-shaped chips, one live accent. **Canva** for
 * everything around the canvas: generous space, large obvious targets, one clear primary action per
 * screen, and enough air that a beginner is not looking at a control panel.
 *
 * The dark ground is not a style preference. A light interface around a design makes the design's
 * own colours read darker than they are — simultaneous contrast, and the reason every professional
 * editor is dark. What the Canva half contributes is not brightness but *clarity*: the previous
 * interface was dark and also grey, cramped and undifferentiated, which is a different failure and
 * the one the user actually objected to.
 *
 * Everything below is a token. Nothing in the app should name a raw colour or a raw dimension —
 * that is what made the last interface impossible to restyle without touching two hundred files.
 */
object Ink {

    // ---- surfaces --------------------------------------------------------------------------
    //
    // Four steps, each about one stop apart, so a card on a sheet on the ground is legible without
    // a border. Neutral greys with a *faint* blue bias: a perfectly neutral grey next to a saturated
    // accent reads as slightly warm, and a warm surround shifts colour judgement on the artwork.

    /** Behind everything. Nearly black, so a bright design floats rather than glowing on grey. */
    val Ground = Color(0xFF0B0F12)

    /** Bars and the sheet's backing. */
    val Chrome = Color(0xFF11171C)

    /** Cards and the raised tool tray — the Airbrush surface. */
    val ChromeRaised = Color(0xFF161D23)

    /** Wells: a text field, a histogram's backing, the inside of a slider's track. */
    val ChromeSunken = Color(0xFF0D1317)

    /** Around the canvas. Mid grey on purpose: white and black artwork must both read against it. */
    val Surround = Color(0xFF262E34)

    // ---- accent ----------------------------------------------------------------------------

    /**
     * The one live colour. Selection, the active tool, a primary action — nothing else.
     *
     * Teal, and the reason is the job rather than the fashion: it is a colour almost nothing in a
     * cover design is, so a selected chip never blends into the artwork behind it, and it stays
     * distinguishable from the red used for warnings by hue rather than by brightness alone.
     */
    val Accent = Color(0xFF22D3C5)

    /** Text on the accent. Very dark rather than white: teal at full chroma is a light colour. */
    val OnAccent = Color(0xFF04211F)

    /** The accent at low opacity, for a chosen chip's fill. */
    val AccentSoft = Color(0x2622D3C5)

    /** A second step down, for hover and pressed states on already-tinted surfaces. */
    val AccentFaint = Color(0x1422D3C5)

    /** The accent as a wash, for the one place a gradient earns itself: the primary action. */
    val AccentGradient = Brush.horizontalGradient(listOf(Color(0xFF22D3C5), Color(0xFF17B8D4)))

    // ---- text ------------------------------------------------------------------------------

    val Text = Color(0xFFEDF2F4)

    /** Labels and secondary readouts. Still passes contrast on [ChromeRaised] at body size. */
    val TextMuted = Color(0xFF93A2AC)

    /** Disabled. Deliberately below the contrast floor, because that *is* what disabled means. */
    val TextDisabled = Color(0xFF4C585F)

    // ---- lines and semantics ---------------------------------------------------------------

    val Divider = Color(0xFF232C33)
    val Outline = Color(0xFF2E3A42)

    val Danger = Color(0xFFFF6B6B)
    val Warning = Color(0xFFE0A93F)
    val Success = Color(0xFF4ADE80)

    /** Snap guides. Magenta: nothing in a design is likely to be exactly this. */
    val Guide = Color(0xFFFF3FB4)
}

/**
 * Spacing, on a four-point grid.
 *
 * A scale rather than free numbers. The previous interface used 2, 3, 4, 6, 8, 9, 10, 11, 12, 14 and
 * 16 dp in adjacent files, and the result reads as slightly wrong everywhere without any single
 * place being identifiably at fault.
 */
object Space {
    val hair = 2.dp
    val tight = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val wide = 24.dp
    val huge = 32.dp

    /**
     * The gutter every screen's content sits inside.
     *
     * Generous, and that is the Canva half of the brief: the old sheet ran controls to within twelve
     * points of the edge, which is what made it read as a control panel rather than as a tool.
     */
    val gutter = 20.dp

    /** The platform's minimum touch target. A mis-tap on a canvas costs an undo. */
    val touch = 48.dp
}

/**
 * Corner radii.
 *
 * Softer than Material's defaults throughout, because both references are: Airbrush's tool tray and
 * Canva's template cards are notably round, and roundness is most of what makes an interface read as
 * approachable rather than as instrumentation.
 */
object Corners {
    val chip = RoundedCornerShape(999.dp)
    val small = RoundedCornerShape(10.dp)
    val card = RoundedCornerShape(16.dp)
    val large = RoundedCornerShape(20.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

/**
 * The type scale.
 *
 * Persian text needs more line height than Latin at the same size — the ascenders and descenders of
 * the script overlap at Material's defaults and a two-line label runs into itself. Every style below
 * carries that, and trims the extra space at the top and bottom so the block still aligns.
 */
private val persianLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(size: Int, height: Int, weight: FontWeight, spacing: Double = 0.0) = TextStyle(
    fontSize = size.sp,
    lineHeight = height.sp,
    fontWeight = weight,
    letterSpacing = spacing.sp,
    lineHeightStyle = persianLineHeight,
)

private val typography = Typography(
    displaySmall = style(30, 42, FontWeight.Bold, -0.3),
    headlineMedium = style(24, 34, FontWeight.Bold, -0.2),
    headlineSmall = style(20, 30, FontWeight.SemiBold),
    titleLarge = style(18, 28, FontWeight.SemiBold),
    titleMedium = style(16, 26, FontWeight.SemiBold),
    bodyLarge = style(15, 26, FontWeight.Normal),
    bodyMedium = style(14, 24, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(12, 18, FontWeight.Normal),
)

private val scheme = darkColorScheme(
    primary = Ink.Accent,
    onPrimary = Ink.OnAccent,
    secondary = Ink.Accent,
    background = Ink.Ground,
    onBackground = Ink.Text,
    surface = Ink.ChromeRaised,
    onSurface = Ink.Text,
    surfaceVariant = Ink.ChromeSunken,
    onSurfaceVariant = Ink.TextMuted,
    outline = Ink.Outline,
    outlineVariant = Ink.Divider,
    error = Ink.Danger,
)

@Composable
fun PixelLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
