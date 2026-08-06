package ir.pixellab.app

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.toFontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design system — «کارگاه» (Workshop).
 *
 * Every value here comes from the specification: the token table in the build constitution (§۶) and
 * the creative direction in the product document (§۱۳.۵). Nothing is invented, and nothing outside
 * this file may name a raw colour or a raw dimension.
 *
 * The governing metaphor is a workshop rather than a toolbox: the light matters, the work surface
 * matters, and the tools are laid out within reach instead of buried in nested menus. What that
 * means concretely is the rule the whole palette serves — **the canvas is the only bright thing on
 * the screen, and the interface steps back.**
 */
object Ink {

    // ---- surfaces ----------------------------------------------------------------------------
    //
    // Four steps, from the specification's dark theme. Near-black behind everything so a design
    // floats rather than glowing on grey, then three raised planes for card, panel and field.

    /** `canvas.backdrop` — behind everything. */
    val Ground get() = tokens.ground

    /** `surface.1` — card and panel. */
    val Chrome get() = tokens.surface1

    /** `surface.2` — raised panel, the tool tray. */
    val ChromeRaised get() = tokens.surface2

    /** `surface.3` — input and field. */
    val ChromeSunken get() = tokens.surface3

    /**
     * Around the artwork.
     *
     * Mid grey, and it is the one surface not taken from the four-step ladder: white and black
     * artwork must both read against it, which neither end of the ladder allows.
     */
    val Surround get() = tokens.surround

    // ---- accent ------------------------------------------------------------------------------

    /**
     * `accent.primary` — the brand colour. Selection, the active tool, a primary action.
     *
     * A warm amber, and the reasoning in the specification is cultural rather than decorative: it
     * shares a root with gold leaf, copper and wood — the right reference for a workshop in Yazd —
     * and it is the opposite of the cold blue every competitor reaches for.
     */
    val Accent get() = tokens.accent

    /** `accent.pressed`. */
    val AccentPressed get() = tokens.accentPressed

    /** `accent.subtle` — a chosen chip's fill. */
    val AccentSoft get() = tokens.accentSubtle

    /** Text on a filled accent. Very dark: amber at full chroma is a light colour. */
    val OnAccent get() = tokens.onAccent

    /**
     * `secondary.teal` — selection and mask, and nothing else.
     *
     * Kept strictly to that role. A second colour used decoratively would leave the interface with
     * two accents and therefore none; used for *marching ants, mask overlays and chosen pixels* it
     * says something the amber cannot, because the amber already means "the tool you are holding".
     */
    val Selection get() = tokens.selection

    // ---- text --------------------------------------------------------------------------------

    /** `text.primary`. */
    val Text get() = tokens.textPrimary

    /** `text.secondary`. */
    val TextMuted get() = tokens.textSecondary

    /** `text.disabled`. Deliberately below the contrast floor, because that *is* what it means. */
    val TextDisabled get() = tokens.textDisabled

    // ---- lines and semantics -----------------------------------------------------------------

    /** `border.subtle`. */
    val Divider get() = tokens.borderSubtle

    /** `border.strong`. */
    val Outline get() = tokens.borderStrong

    /** `state.error`. */
    val Danger get() = tokens.error

    /** `state.warning`. */
    val Warning get() = tokens.warning

    /** `state.success`. */
    val Success get() = tokens.success

    /** `overlay.scrim`. */
    val Scrim get() = tokens.scrim

    /**
     * Snap guides.
     *
     * Magenta rather than either accent: a guide sits *on top of the artwork* while the amber sits
     * on the chrome, and a guide the same colour as the active tool is a guide that disappears the
     * moment it crosses a warm-toned photograph.
     */
    val Guide = Color(0xFFFF3FB4)

    /** The palette currently in force. Swapped wholesale by [PixelLabTheme]. */
    internal var tokens: Palette = Palette.Dark
        private set

    internal fun use(palette: Palette) {
        tokens = palette
    }
}

/**
 * One complete set of colour values.
 *
 * A table rather than a `when` inside each accessor, because the light theme is a *second table*,
 * not a set of exceptions: the specification gives its own hex values and several of them are not
 * derivable from the dark ones.
 */
internal data class Palette(
    val ground: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surround: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val accent: Color,
    val accentPressed: Color,
    val accentSubtle: Color,
    val onAccent: Color,
    val selection: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val scrim: Color,
) {
    companion object {

        /** The default, and the one the whole product is designed in. Specification §۶. */
        val Dark = Palette(
            ground = Color(0xFF0B0C0E),
            surface1 = Color(0xFF141619),
            surface2 = Color(0xFF1C1F23),
            surface3 = Color(0xFF262A2F),
            // Not in the token table: the one surface the specification describes in prose instead
            // ("the canvas must be the brightest element"), so it is derived to sit above the field
            // colour without approaching the artwork.
            surround = Color(0xFF31363C),
            borderSubtle = Color(0xFF2E3338),
            // 3.2:1 against the card, not the 1.8:1 it was. WCAG 1.4.11 asks 3:1 of any boundary
            // that carries meaning, and this one draws every unselected chip and every panel edge —
            // below the bar the interface reads as one undifferentiated slab, which is precisely
            // what came back from the first device session.
            borderStrong = Color(0xFF616871),
            textPrimary = Color(0xFFF2F4F6),
            textSecondary = Color(0xFFA3ABB4),
            textDisabled = Color(0xFF5C646D),
            accent = Color(0xFFE8A33D),
            accentPressed = Color(0x63C98739),
            accentSubtle = Color(0x1AE8A33D),
            // Derived, because a token table that gives a fill has to be read together with the
            // contrast floor: white on #E8A33D is 2.1:1 and fails, so filled controls take ink.
            onAccent = Color(0xFF1B1206),
            selection = Color(0xFF3DCCC0),
            success = Color(0xFF4ADE80),
            warning = Color(0xFFFBBF24),
            error = Color(0xFFF87171),
            scrim = Color(0xB3000000),
        )

        /**
         * The light theme — and no longer "optional".
         *
         * A light ground is the only one in which the *colour* of a photograph can be judged: the
         * eye adapts to the surround, so shadow, saturation and warmth are read against a bright
         * field the way they will be read on paper. Photoshop keeps a light interface for exactly
         * this reason. For an application whose whole job is colour, having one is not a preference.
         *
         * **Every value here was measured, not chosen by eye.** Each text-bearing colour clears
         * WCAG 4.5:1 against *all four* surfaces — not only against white, which is the mistake that
         * makes a palette look fine in a swatch and fail on the one panel that happens to be sunken.
         * `ThemeContrastTest` asserts it, so the next person to nudge a hue finds out immediately.
         *
         * That measurement is what moved the semantic colours: the previous amber was 4.0:1 on the
         * sunken field, and a warning that cannot be read is worse than no warning.
         */
        val Light = Palette(
            // A warm-neutral ladder rather than a blue-grey one. Blue-grey chrome pushes a
            // photograph's whites toward yellow by simultaneous contrast, which is a colour
            // judgement the interface has no business making for the user.
            ground = Color(0xFFF4F5F7),
            surface1 = Color(0xFFFFFFFF),
            surface2 = Color(0xFFEAECEF),
            surface3 = Color(0xFFE1E4E8),
            // Mid grey around the artwork, darker than the ladder: white *and* black artwork have
            // to read against it, which neither end of the ladder allows.
            surround = Color(0xFFC4C9CF),
            borderSubtle = Color(0xFFDDE1E6),
            // 3.1:1 on the card. Same reason as the dark theme's: a boundary that cannot be seen
            // is not a boundary.
            borderStrong = Color(0xFF8B939D),
            textPrimary = Color(0xFF14161A),
            textSecondary = Color(0xFF545C66),
            // Decorative only — 2.4:1, deliberately below the text bar, because a disabled control
            // that reads as clearly as an active one is a control nobody can tell is disabled.
            textDisabled = Color(0xFF99A1AB),
            // Vermilion, darkened until it passes as *text* on the sunken field. A light theme
            // cannot borrow the dark theme's accent: amber at full chroma is a light colour, and on
            // white it disappears.
            accent = Color(0xFFAC3A23),
            accentPressed = Color(0x638F2E1B),
            accentSubtle = Color(0x1AAC3A23),
            onAccent = Color(0xFFFFFFFF),
            selection = Color(0xFF0B6A63),
            success = Color(0xFF126B33),
            warning = Color(0xFF8A5105),
            error = Color(0xFFA81F1A),
            scrim = Color(0x59000000),
        )
    }
}

/**
 * Spacing. The specification's scale, and nothing between its steps.
 *
 * `4 · 8 · 12 · 16 · 24 · 32 · 48`
 */
object Space {
    val tight = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val wide = 24.dp
    val huge = 32.dp

    /** The screen's gutter. On the scale, unlike the 20dp it replaces. */
    val gutter = 16.dp

    /** The platform minimum, and the specification's: a mis-tap on a canvas costs an undo. */
    val touch = 48.dp
}

/** Corner radii. Specification §۶: `button 8 · card 12 · sheet 20 · chip 999`. */
object Corners {
    val button = RoundedCornerShape(8.dp)
    val small = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(12.dp)
    val chip = RoundedCornerShape(999.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}

/**
 * The fixed heights of the screen's furniture. Specification §۶ and §۱۳.۵.
 *
 * Named rather than written where they are used, because the whole point of the layout is that
 * these three bars are *the same height on every screen* — the moment one is set locally, the canvas
 * starts moving as the user switches tools.
 */
object Frame {
    /** History strip, along the top. */
    val history = 56.dp

    /** Contextual ribbon, directly under the canvas. Changes with what is selected. */
    val ribbon = 88.dp

    /** The main dock. Five entries, always. */
    val dock = 64.dp

    /** Icon stroke. Specification: monochrome, 1.5dp. */
    val stroke = 1.5.dp

    /** Icon box. Sized so a 1.5dp stroke reads as a line rather than as a smudge. */
    val icon = 24.dp
}

/**
 * Motion. Specification §۶.
 *
 * Three durations and one curve. The two rules that matter more than the numbers: a slider changing
 * a value updates the canvas **immediately and without animation** — an eased canvas reads as a slow
 * app — and no animation may block the interaction after it.
 */
object Motion {
    const val MICRO = 150
    const val STANDARD = 200
    const val SHEET = 250

    /** `easeOutCubic`. */
    val ease = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
}

/**
 * The interface typeface.
 *
 * Vazirmatn Variable, bundled under the SIL Open Font License (see `docs/licenses/`). Bundled rather
 * than taken from the device, for the reason the specification gives about shaping in general: a
 * face that varies with the Android version means the interface measures differently on different
 * phones, and a Persian label that fits on one and wraps on another is a layout nobody can design
 * against.
 *
 * The weight axis is set explicitly per style rather than by synthesising bold, which on a variable
 * face produces a smeared outline instead of the drawn weight.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun vazirmatn(weight: Int): FontFamily = Font(
    resId = R.font.vazirmatn,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
).toFontFamily()

private val displayFace = vazirmatn(600)
private val titleFace = vazirmatn(500)
private val bodyFace = vazirmatn(400)
private val labelFace = vazirmatn(500)
private val captionFace = vazirmatn(400)

/**
 * Line height is a multiple in the specification, so it is written as one here.
 *
 * The multiples are generous — 1.6 for body — because Persian needs it: the ascenders and descenders
 * of the script overlap at the ratios Latin type gets away with, and a two-line label runs into
 * itself.
 */
private fun style(
    family: FontFamily,
    size: Int,
    multiple: Double,
    weight: Int,
    features: String? = null,
) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = (size * multiple).sp,
    fontWeight = FontWeight(weight),
    fontFeatureSettings = features,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/**
 * The type scale, specification §۶.
 *
 * ```
 * display  22sp / 600 / 1.4
 * title    17sp / 500 / 1.45
 * body     15sp / 400 / 1.6
 * label    13sp / 500 / 1.4
 * caption  11sp / 400 / 1.4
 * ```
 *
 * Five roles, mapped onto the Material slots the components already ask for. Material has more slots
 * than the specification has roles, so several map to the same style — which is the point: an
 * interface with five sizes is one somebody designed, and one with thirteen is one that accumulated.
 */
private val typography = Typography(
    displayLarge = style(displayFace, 22, 1.4, 600),
    displayMedium = style(displayFace, 22, 1.4, 600),
    displaySmall = style(displayFace, 22, 1.4, 600),
    headlineLarge = style(displayFace, 22, 1.4, 600),
    headlineMedium = style(displayFace, 22, 1.4, 600),
    headlineSmall = style(titleFace, 17, 1.45, 500),
    titleLarge = style(titleFace, 17, 1.45, 500),
    titleMedium = style(titleFace, 17, 1.45, 500),
    titleSmall = style(labelFace, 13, 1.4, 500),
    bodyLarge = style(bodyFace, 15, 1.6, 400),
    bodyMedium = style(bodyFace, 15, 1.6, 400),
    bodySmall = style(captionFace, 11, 1.4, 400),
    labelLarge = style(labelFace, 13, 1.4, 500),
    labelMedium = style(labelFace, 13, 1.4, 500),
    labelSmall = style(captionFace, 11, 1.4, 400),
)

/**
 * The numeric style: 14sp, weight 500, **tabular figures**.
 *
 * Its own style rather than a variant of `label`, because `tnum` is the whole reason it exists. A
 * proportional `1` is narrower than a `0`, so a readout that counts while a slider moves jitters
 * sideways under the finger, and a column of values in a panel refuses to line up.
 */
val NumericStyle: TextStyle = style(vazirmatn(500), 14, 1.4, 500, features = "tnum")

/**
 * Renders digits the way the specification asks for each context.
 *
 * §۱۳.۳ is explicit and easy to get backwards: **numeric panels use Latin digits**, because they are
 * technical input and the user types them back in; running Persian prose uses Persian digits,
 * because mixed numerals inside one sentence are what makes an interface read as translated rather
 * than written. So this is not a global setting — it is a decision per string, and the two helpers
 * are named for the decision rather than for the script.
 */
object Digits {

    /** For a measurement, a coordinate, a percentage — anything the user could type back in. */
    fun technical(value: Int): String = value.toString()

    /** For a count or a duration inside a Persian sentence. */
    fun prose(value: Long): String = buildString {
        for (character in value.toString()) {
            append(if (character in '0'..'9') PERSIAN[character - '0'] else character)
        }
    }

    fun prose(value: Int): String = prose(value.toLong())

    private const val PERSIAN = "۰۱۲۳۴۵۶۷۸۹"
}

/** Whether the interface is currently dark, for the few controls that must know. */
val LocalDarkTheme: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }

private fun schemeFor(palette: Palette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        secondary = palette.selection,
        background = palette.ground,
        onBackground = palette.textPrimary,
        surface = palette.surface1,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surface3,
        onSurfaceVariant = palette.textSecondary,
        outline = palette.borderStrong,
        outlineVariant = palette.borderSubtle,
        error = palette.error,
        scrim = palette.scrim,
    )
} else {
    lightColorScheme(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        secondary = palette.selection,
        background = palette.ground,
        onBackground = palette.textPrimary,
        surface = palette.surface1,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surface3,
        onSurfaceVariant = palette.textSecondary,
        outline = palette.borderStrong,
        outlineVariant = palette.borderSubtle,
        error = palette.error,
        scrim = palette.scrim,
    )
}

/**
 * @param dark which palette to use. Defaults to the system setting, so a user whose phone is light
 *   gets the light theme the specification calls optional — and everyone else gets the dark one the
 *   product is designed in.
 */
@Composable
fun PixelLabTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) Palette.Dark else Palette.Light
    Ink.use(palette)
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = schemeFor(palette, dark),
            typography = typography,
            content = content,
        )
    }
}
