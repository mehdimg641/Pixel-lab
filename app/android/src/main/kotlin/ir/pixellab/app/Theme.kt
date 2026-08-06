package ir.pixellab.app

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design system — four skins, two modes each.
 *
 * ### What changed and why
 *
 * This file used to hold one palette and its light counterpart. It now holds **four complete
 * directions**, because a theme is not a colour: *Ember Slate*, *Iris Ink*, *Console* and
 * *Ember فارسی* differ in accent, in surface ladder, in corner radius, in typeface and in how the
 * work panel meets the canvas. Picking one has to change all of those together or it is a recolour
 * pretending to be a direction.
 *
 * Every screen in the application paints through the [Ink] accessors, so none of them had to be
 * touched: swapping [ThemeSkin] swaps the table underneath and the whole interface follows.
 *
 * ### The one place the source direction was overruled
 *
 * The supplied palettes were assembled for a browser mock-up, and several values fail WCAG AA once
 * they sit on the *deeper* surfaces rather than on the panel they were picked against — the
 * secondary text in five of the six modes, and the accent in all three light modes. Those were
 * corrected by shifting **lightness only**, keeping hue and saturation, until each clears 4.5:1 on
 * all four surfaces. The shifts are between two and eight percent; the directions still read exactly
 * as drawn. `ThemeContrastTest` measures every one of the eight palettes, so this cannot quietly
 * come undone.
 */
object Ink {

    // ---- surfaces ----------------------------------------------------------------------------

    /** `bg` — behind everything. */
    val Ground get() = tokens.ground

    /** `panel` — the work panel and any card. */
    val Chrome get() = tokens.surface1

    /** `elev` — a raised plane inside the panel: a chip, a tool tray, a field. */
    val ChromeRaised get() = tokens.surface2

    /** `hover` composited onto the panel — a selected row, a pressed chip. */
    val ChromeSunken get() = tokens.surface3

    /** `canvas` — around the artwork. Darker than the ground so the document reads as the subject. */
    val Surround get() = tokens.surround

    /**
     * `overlay` — the fill behind a control that floats *on* the canvas.
     *
     * Its own token rather than a surface with alpha, because it has to stay legible over an
     * arbitrary photograph: a zoom read-out at 85% opacity over black and the same read-out over a
     * white sky are different problems, and only a fixed, near-opaque plate solves both.
     */
    val Overlay get() = tokens.overlay

    /** `track` — the unfilled part of a slider or a toggle. Not a border; not a surface. */
    val Track get() = tokens.track

    // ---- accent ------------------------------------------------------------------------------

    /** `accent` — selection, the active tool, a primary action. One per skin, and only one. */
    val Accent get() = tokens.accent

    val AccentPressed get() = tokens.accentPressed

    /** A chosen chip's fill. */
    val AccentSoft get() = tokens.accentSubtle

    /** `ink` — text on a filled accent. */
    val OnAccent get() = tokens.onAccent

    /**
     * Selection and mask, and nothing else.
     *
     * Kept identical across the four skins on purpose. A second colour that moved with the theme
     * would leave the interface with two accents and therefore none; used strictly for *marching
     * ants, mask overlays and chosen pixels* it says something the accent cannot, because the accent
     * already means "the tool you are holding".
     */
    val Selection get() = tokens.selection

    // ---- text --------------------------------------------------------------------------------

    /** `text`. */
    val Text get() = tokens.textPrimary

    /** `dim`. */
    val TextMuted get() = tokens.textSecondary

    /** Deliberately below the contrast floor, because that *is* what it means. */
    val TextDisabled get() = tokens.textDisabled

    // ---- lines and semantics -----------------------------------------------------------------

    /** `line`. */
    val Divider get() = tokens.borderSubtle

    val Outline get() = tokens.borderStrong

    val Danger get() = tokens.error

    val Warning get() = tokens.warning

    val Success get() = tokens.success

    val Scrim get() = tokens.scrim

    /**
     * Snap guides.
     *
     * Magenta rather than the accent, and fixed across all four skins: a guide sits *on top of the
     * artwork* while the accent sits on the chrome, and a guide the same colour as the active tool
     * is a guide that disappears the moment it crosses a warm-toned photograph. Console's acid
     * yellow would vanish over a sunset; Iris's violet over a dusk shot.
     */
    val Guide = Color(0xFFFF3FB4)

    /**
     * The palette currently in force. Swapped wholesale by [PixelLabTheme].
     *
     * **Compose state, and that is the whole point.** This was a plain `var`, and every screen in
     * the application paints from the accessors above — so every one of those reads was invisible
     * to the snapshot system. Choosing a theme swapped the palette and then repainted only whatever
     * happened to recompose for some *other* reason: the setting appeared to be ignored.
     */
    internal var tokens: Palette by mutableStateOf(Palette.Dark)
        private set

    internal fun use(palette: Palette) {
        tokens = palette
    }
}

/**
 * One complete set of colour values.
 *
 * A table rather than a `when` inside each accessor, because a second direction is a *second table*,
 * not a set of exceptions: almost nothing in Iris is derivable from Ember.
 */
internal data class Palette(
    val ground: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surround: Color,
    val overlay: Color,
    val track: Color,
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

        /**
         * The values every skin shares.
         *
         * Semantics (success / warning / error / selection), the disabled step and the border are
         * not part of a *direction* — they are part of being readable — so they are stated once and
         * measured once. Only the three that carry the direction are per-skin: the surface ladder,
         * the accent, and the secondary text that has to survive on it.
         */
        private fun dark(
            ground: Long,
            surface1: Long,
            surface2: Long,
            surface3: Long,
            surround: Long,
            overlay: Long,
            textPrimary: Long,
            textSecondary: Long,
            accent: Long,
            onAccent: Long,
        ) = Palette(
            ground = Color(ground),
            surface1 = Color(surface1),
            surface2 = Color(surface2),
            surface3 = Color(surface3),
            surround = Color(surround),
            overlay = Color(overlay),
            track = Color(0x29FFFFFF),
            borderSubtle = Color(0x14FFFFFF),
            borderStrong = Color(0xFF616871),
            textPrimary = Color(textPrimary),
            textSecondary = Color(textSecondary),
            textDisabled = Color(0xFF5C646D),
            accent = Color(accent),
            accentPressed = Color(accent).copy(alpha = 0.39f),
            accentSubtle = Color(accent).copy(alpha = 0.10f),
            onAccent = Color(onAccent),
            selection = Color(0xFF3DCCC0),
            success = Color(0xFF4ADE80),
            warning = Color(0xFFFBBF24),
            error = Color(0xFFF87171),
            scrim = Color(0xB3000000),
        )

        private fun light(
            ground: Long,
            surface1: Long,
            surface2: Long,
            surface3: Long,
            surround: Long,
            overlay: Long,
            textPrimary: Long,
            textSecondary: Long,
            accent: Long,
            onAccent: Long,
        ) = Palette(
            ground = Color(ground),
            surface1 = Color(surface1),
            surface2 = Color(surface2),
            surface3 = Color(surface3),
            surround = Color(surround),
            overlay = Color(overlay),
            track = Color(0x29000000),
            borderSubtle = Color(0x1A000000),
            borderStrong = Color(0xFF8B939D),
            textPrimary = Color(textPrimary),
            textSecondary = Color(textSecondary),
            textDisabled = Color(0xFF99A1AB),
            accent = Color(accent),
            accentPressed = Color(accent).copy(alpha = 0.39f),
            accentSubtle = Color(accent).copy(alpha = 0.10f),
            onAccent = Color(onAccent),
            selection = Color(0xFF0B6A63),
            success = Color(0xFF126B33),
            warning = Color(0xFF8A5105),
            error = Color(0xFFA81F1A),
            scrim = Color(0x59000000),
        )

        // ---- Ember Slate -----------------------------------------------------------------------
        //
        // The default. Warm graphite with a vermilion accent — the colour of hot metal rather than
        // the cold blue every competitor reaches for.

        val EmberDark = dark(
            ground = 0xFF0F1114,
            surface1 = 0xFF171A1E,
            surface2 = 0xFF1F2329,
            surface3 = 0xFF272A2E,
            surround = 0xFF0A0B0D,
            overlay = 0xD9101215,
            textPrimary = 0xFFE7E9EC,
            // Given as #7E858E, which is 3.87:1 on the raised surface. Lightened ~5% — same hue,
            // same chroma, 4.54:1 on the worst of the four.
            textSecondary = 0xFF8B9199,
            accent = 0xFFFF5C35,
            onAccent = 0xFF150A06,
        )

        val EmberLight = light(
            ground = 0xFFF6F4F1,
            surface1 = 0xFFFFFFFF,
            surface2 = 0xFFEFEBE5,
            surface3 = 0xFFF1F1F1,
            surround = 0xFFE4E0DA,
            overlay = 0xE6FFFFFF,
            textPrimary = 0xFF15171A,
            // #6E747C → 3.97:1 on the raised surface. Darkened to 4.52:1.
            textSecondary = 0xFF666B73,
            // #E14A22 → 3.40:1, which is a *primary action* nobody can read. Darkened to 4.51:1;
            // it is still unmistakably the same vermilion.
            accent = 0xFFC03D1A,
            onAccent = 0xFFFFF6F3,
        )

        // ---- Iris Ink --------------------------------------------------------------------------
        //
        // Canvas-first. Everything is violet-black and the chrome is meant to disappear: the panel
        // floats as a blurred card with an inset, so the artwork runs behind it rather than stopping
        // at it.

        val IrisDark = dark(
            ground = 0xFF131019,
            surface1 = 0xFF1D1826,
            surface2 = 0xFF262031,
            surface3 = 0xFF2D2835,
            surround = 0xFF0B0910,
            overlay = 0xD91D1826,
            textPrimary = 0xFFEDE9F5,
            // The one secondary that already passed — 5.12:1 — so it is left exactly as drawn.
            textSecondary = 0xFF9E97B0,
            accent = 0xFF9B7CFF,
            onAccent = 0xFF17092F,
        )

        val IrisLight = light(
            ground = 0xFFF6F3FC,
            surface1 = 0xFFFFFFFF,
            surface2 = 0xFFEFE9F9,
            surface3 = 0xFFF1F1F1,
            surround = 0xFFE7E1F3,
            overlay = 0xE0FFFFFF,
            textPrimary = 0xFF1B1626,
            textSecondary = 0xFF6D677F,
            // #7A5AF8 → 3.81:1. Darkened to 4.53:1.
            accent = 0xFF6C48F7,
            onAccent = 0xFFFFFFFF,
        )

        // ---- Console ---------------------------------------------------------------------------
        //
        // Maximum density, monospace throughout, numeric read-outs everywhere. Acid yellow because
        // it is the one accent that reads at 8sp against a near-black terminal ground.

        val ConsoleDark = dark(
            ground = 0xFF16151A,
            surface1 = 0xFF1B1A20,
            surface2 = 0xFF26252C,
            surface3 = 0xFF2B2A30,
            surround = 0xFF0F0E12,
            overlay = 0xE61B1A20,
            textPrimary = 0xFFE4E2EA,
            // #8B8894 → 4.10:1. Lightened to 4.53:1.
            textSecondary = 0xFF92909B,
            accent = 0xFFE3F25C,
            onAccent = 0xFF16151A,
        )

        val ConsoleLight = light(
            ground = 0xFFF2F2EC,
            surface1 = 0xFFFFFFFF,
            surface2 = 0xFFE9E9E1,
            surface3 = 0xFFF1F1F1,
            surround = 0xFFDFDFD7,
            overlay = 0xE6FFFFFF,
            textPrimary = 0xFF16151A,
            textSecondary = 0xFF696871,
            // #66770A → 4.09:1. Darkened to 4.51:1.
            accent = 0xFF607009,
            onAccent = 0xFFFBFFE8,
        )

        /**
         * Kept as names so the tests and any caller that predates the four skins still compile.
         *
         * They are Ember's, because Ember is the default direction.
         */
        val Dark = EmberDark
        val Light = EmberLight
    }
}

/**
 * How the work panel meets the canvas.
 *
 * Not decoration: this is the single decision that makes the three directions feel like different
 * applications rather than three colour schemes of one.
 */
enum class PanelLayout {

    /** Flat, full-bleed, one hairline along the top. Ember. */
    DOCKED,

    /** A blurred card floating over the artwork with an inset on three sides. Iris. */
    FLOATING,

    /** Flat and compact, with the panel given less height so the canvas keeps more. Console. */
    DENSE,
}

/**
 * A complete direction: the two palettes, the shape language, the typeface and the writing system.
 *
 * The fourth entry is not a fourth colour scheme — it is Ember mirrored, in Vazirmatn, with Persian
 * digits. Kept as a separate skin rather than as a flag on Ember so that "which direction am I in"
 * has exactly one answer everywhere, including in the picker.
 */
enum class ThemeSkin(
    val persianLabel: String,
    val note: String,
    val layout: PanelLayout,
    val mono: Boolean,
    internal val dark: Palette,
    internal val light: Palette,
    private val buttonRadius: Int,
    private val cardRadius: Int,
    private val sheetRadius: Int,
    private val chipRadius: Int,
    /** How tall the docked work panel is allowed to grow, as a fraction of the screen. */
    val panelFraction: Float,
    /** Whether technical read-outs are set in Persian digits. See [Digits.technical]. */
    val persianNumerals: Boolean = false,
) {
    EMBER(
        persianLabel = "امبر اسلیت",
        note = "پیش‌فرض",
        layout = PanelLayout.DOCKED,
        mono = false,
        dark = Palette.EmberDark,
        light = Palette.EmberLight,
        buttonRadius = 9,
        cardRadius = 12,
        sheetRadius = 20,
        chipRadius = 999,
        panelFraction = 0.42f,
    ),
    IRIS(
        persianLabel = "آیریس اینک",
        note = "بوم‌محور",
        layout = PanelLayout.FLOATING,
        mono = false,
        dark = Palette.IrisDark,
        light = Palette.IrisLight,
        buttonRadius = 11,
        cardRadius = 18,
        sheetRadius = 24,
        chipRadius = 999,
        panelFraction = 0.40f,
    ),
    CONSOLE(
        persianLabel = "کنسول",
        note = "متراکم / حرفه‌ای",
        layout = PanelLayout.DENSE,
        mono = true,
        dark = Palette.ConsoleDark,
        light = Palette.ConsoleLight,
        buttonRadius = 4,
        cardRadius = 6,
        sheetRadius = 12,
        chipRadius = 6,
        panelFraction = 0.32f,
    ),
    /**
     * Ember, with the numbers in Persian too.
     *
     * The direction as drawn is "Ember, fully mirrored RTL" — which this application already is,
     * everywhere, and has been since the first screen. Shipping that as a fourth entry would put two
     * identical rows in the picker, and a choice that changes nothing is worse than three choices.
     *
     * So the fourth direction is the one thing Ember deliberately does *not* do: it sets the
     * technical read-outs — sizes, percentages, coordinates — in Persian digits as well. The
     * specification argues for Latin there, and the argument is good: those are values the user
     * types back in. But it is an argument, not a fact, and somebody laying out a Persian poster may
     * reasonably want an interface with no Latin numerals anywhere in it. That is a direction.
     */
    EMBER_FA(
        persianLabel = "امبر — فارسی",
        note = "ارقام فارسی",
        layout = PanelLayout.DOCKED,
        mono = false,
        dark = Palette.EmberDark,
        light = Palette.EmberLight,
        buttonRadius = 9,
        cardRadius = 12,
        sheetRadius = 20,
        chipRadius = 999,
        panelFraction = 0.42f,
        persianNumerals = true,
    ),
    ;

    internal fun palette(dark: Boolean): Palette = if (dark) this.dark else this.light

    /**
     * Built once per direction, not once per read.
     *
     * A `get()` here allocated a new `CornerSet` on every call, which made "the shapes did not
     * change" impossible to establish by equality — see the note on [Metrics.use] for what that
     * cost.
     */
    internal val corners: CornerSet by lazy {
        CornerSet(
            button = RoundedCornerShape(buttonRadius.dp),
            card = RoundedCornerShape(cardRadius.dp),
            chip = RoundedCornerShape(chipRadius.dp),
            sheet = RoundedCornerShape(topStart = sheetRadius.dp, topEnd = sheetRadius.dp),
            panel = RoundedCornerShape(cardRadius.dp),
        )
    }

    /** The inset a floating panel keeps from the screen edge. Zero for the flat layouts. */
    val panelInset: Dp get() = if (layout == PanelLayout.FLOATING) 10.dp else 0.dp
}

internal data class CornerSet(
    val button: RoundedCornerShape,
    val card: RoundedCornerShape,
    val chip: RoundedCornerShape,
    val sheet: RoundedCornerShape,
    val panel: RoundedCornerShape,
)

/**
 * Spacing. The specification's scale, and nothing between its steps.
 *
 * `4 · 8 · 12 · 16 · 24 · 32 · 48`
 *
 * Deliberately **not** varied per skin. Console reads as dense through its radii, its typeface and
 * its shorter panel; making the spacing scale move as well would reflow every screen in the
 * application on a theme change, and a layout that only holds together in one theme is one nobody
 * can maintain.
 */
object Space {
    val tight = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val wide = 24.dp
    val huge = 32.dp

    /** The screen's gutter. */
    val gutter = 16.dp

    /** The platform minimum, and the specification's: a mis-tap on a canvas costs an undo. */
    val touch = 48.dp
}

/**
 * Corner radii — now a property of the skin.
 *
 * Written as accessors so that the hundred-odd call sites that say `Corners.card` did not have to
 * change, and so that a radius read is an observable state read like a colour read. The bug that
 * `ThemeSwitchTest` exists to catch applies here identically: a shape read through a plain `val`
 * would freeze at whatever the first skin was.
 */
object Corners {
    val button get() = Metrics.corners.button
    val small get() = Metrics.corners.button
    val card get() = Metrics.corners.card
    val large get() = Metrics.corners.card
    val chip get() = Metrics.corners.chip
    val sheet get() = Metrics.corners.sheet
    val panel get() = Metrics.corners.panel
}

/**
 * The fixed heights of the screen's furniture.
 *
 * Named rather than written where they are used, because the whole point of the layout is that
 * these bars are *the same height on every screen* — the moment one is set locally, the canvas
 * starts moving as the user switches tools.
 */
object Frame {
    /** History strip, along the top. */
    val history = 56.dp

    /**
     * Contextual ribbon, directly under the canvas.
     *
     * Fixed at eighty-eight in every direction, and that is a correction rather than an omission.
     * Console shortened both this and the dock to read as denser, and the first picture of it showed
     * why that was wrong: the labels are Persian words under 24dp icons, and «سه‌بعدی» and «خروجی»
     * were cut off at the baseline. A direction may not make text unreadable to look tighter.
     * Console's density comes from its radii, its accent and its read-outs instead.
     */
    val ribbon = 88.dp

    /** The main dock. Five entries, always. */
    val dock = 64.dp

    /** Icon stroke. Monochrome, 1.5dp. */
    val stroke = 1.5.dp

    /** Icon box. Sized so a 1.5dp stroke reads as a line rather than as a smudge. */
    val icon = 24.dp
}

/**
 * Which direction is in force, for the parts of the interface that must know more than a colour.
 *
 * Same reasoning as [Ink.tokens], and the same trap: shapes and metrics are read all over the tree,
 * so they have to be observable or a theme change repaints the colours and leaves the corners.
 */
object Metrics {

    internal var skin: ThemeSkin by mutableStateOf(ThemeSkin.EMBER)
        private set

    internal var corners: CornerSet by mutableStateOf(ThemeSkin.EMBER.corners)
        private set

    internal var numeric: TextStyle by mutableStateOf(numericStyle(mono = false))
        private set

    /**
     * Adopts a direction — **and does nothing at all if it is already the one in force.**
     *
     * ### The hang this guard exists to prevent
     *
     * `PixelLabTheme` calls this from inside composition, the same way it hands [Ink] its palette.
     * Without the early return, every composition of the root wrote three snapshot states; two of
     * them are rebuilt values (`chosen.corners` allocates a `CornerSet`, `numericStyle` allocates a
     * `TextStyle` holding a freshly-loaded `FontFamily`) and a rebuilt value is not reliably equal
     * to its predecessor. An unequal write invalidates every reader — and `Corners.card` and
     * [NumericStyle] are read all over the tree — so the tree recomposed, which re-ran this, which
     * wrote again. **Write-during-composition of a value derived from that composition is a loop**,
     * and it presented as `TouchTargetTest` pinning a core at 100% inside
     * `ComposeIdlingResource.isIdleNow` for half an hour with no failure and no output.
     *
     * The guard is the fix, and the cached [ThemeSkin.corners] behind it is the belt: a stable
     * instance per direction means even a future caller that skips the guard writes an equal value.
     */
    internal fun use(chosen: ThemeSkin) {
        if (skin == chosen) return
        skin = chosen
        corners = chosen.corners
        numeric = numericStyle(chosen.mono)
    }

    /** The panel treatment the current direction asks for. */
    val layout: PanelLayout get() = skin.layout
}

/**
 * Motion.
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
 * Console's monospace is the platform's, deliberately: bundling a second full face for one skin
 * costs about 300 KB of APK for a difference the skin already carries in its colour and its
 * read-outs, and every Android device since Lollipop ships a competent mono. It is used **only for
 * numbers**, where its fixed pitch is the point and its lack of Arabic coverage cannot bite — see
 * the note on [typographyFor] for what happened when it was used for words.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun vazirmatn(weight: Int): FontFamily = Font(
    resId = R.font.vazirmatn,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
).toFontFamily()

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
 * The type scale.
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
 * than there are roles, so several map to the same style — which is the point: an interface with
 * five sizes is one somebody designed, and one with thirteen is one that accumulated.
 *
 * ### Console does *not* set its interface in monospace, and the first render is why
 *
 * The direction as drawn says "JetBrains Mono throughout", which is the right call for the Latin
 * mock-up it was drawn in and the wrong one here. No monospace face on Android carries the Arabic
 * script, so every Persian label fell through to a system fallback: a different design, different
 * metrics, and «سه‌بعدی» sitting a pixel outside its own box. An interface that cannot render its
 * own language is not a denser interface, it is a broken one.
 *
 * So Console keeps monospace exactly where monospace earns its place — the numeric read-outs, via
 * [NumericStyle] — and sets its words in the same face as everything else. Its identity is carried
 * by the acid accent, the near-square corners and the read-outs, which is where it was coming from
 * anyway. It still drops one step at the small end, because that part was about density and density
 * is not the thing that broke.
 */
private fun typographyFor(mono: Boolean): Typography {
    val display = vazirmatn(600)
    val title = vazirmatn(500)
    val body = vazirmatn(400)
    val label = vazirmatn(500)
    val caption = vazirmatn(400)
    val labelSize = if (mono) 12 else 13
    val captionSize = if (mono) 10 else 11
    return Typography(
        displayLarge = style(display, 22, 1.4, 600),
        displayMedium = style(display, 22, 1.4, 600),
        displaySmall = style(display, 22, 1.4, 600),
        headlineLarge = style(display, 22, 1.4, 600),
        headlineMedium = style(display, 22, 1.4, 600),
        headlineSmall = style(title, 17, 1.45, 500),
        titleLarge = style(title, 17, 1.45, 500),
        titleMedium = style(title, 17, 1.45, 500),
        titleSmall = style(label, labelSize, 1.4, 500),
        bodyLarge = style(body, 15, 1.6, 400),
        bodyMedium = style(body, 15, 1.6, 400),
        bodySmall = style(caption, captionSize, 1.4, 400),
        labelLarge = style(label, labelSize, 1.4, 500),
        labelMedium = style(label, labelSize, 1.4, 500),
        labelSmall = style(caption, captionSize, 1.4, 400),
    )
}

/**
 * The numeric style: 14sp, weight 500, **tabular figures**.
 *
 * Its own style rather than a variant of `label`, because `tnum` is the whole reason it exists. A
 * proportional `1` is narrower than a `0`, so a readout that counts while a slider moves jitters
 * sideways under the finger, and a column of values in a panel refuses to line up. Monospace is
 * already tabular, so Console gets it for free and keeps the feature tag anyway — harmless, and it
 * survives a future swap of the mono face for one that is not fixed-pitch in its figures.
 */
private val numericStyles = HashMap<Boolean, TextStyle>()

/** Cached per variant, for the same reason [ThemeSkin.corners] is: a rebuilt value is not equal. */
private fun numericStyle(mono: Boolean): TextStyle = numericStyles.getOrPut(mono) { buildNumericStyle(mono) }

private fun buildNumericStyle(mono: Boolean): TextStyle =
    style(if (mono) FontFamily.Monospace else vazirmatn(500), 14, 1.4, 500, features = "tnum")
        // **Left to right, inside a right-to-left interface.** A number is written left to right in
        // Persian exactly as it is in English — ۱۰۸۰ is one thousand and eighty in both — but a
        // *compound* read-out is a run of tokens, and the bidi algorithm lays those out in the
        // paragraph's direction. So `1080 × 1920` in an RTL row renders as `1920 × 1080`, and the
        // first render of the template grid showed a story template advertising itself as landscape.
        //
        // Set on the style rather than at each call site, because that is the property every
        // read-out in the application shares and the reason this style exists at all: these are
        // values the user could type back in, and a value that reads backwards is worse than none.
        .copy(textDirection = TextDirection.Ltr)

/** The numeric style of the direction in force. */
val NumericStyle: TextStyle get() = Metrics.numeric

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

    /**
     * For a measurement, a coordinate, a percentage — anything the user could type back in.
     *
     * Latin in three of the four directions, Persian in «امبر — فارسی». That is the one thing the
     * fourth direction changes, and it is a real preference rather than a translation setting: the
     * argument for Latin here is that these are values the user types back, and it is a good
     * argument that some people will simply not accept for their own work.
     */
    fun technical(value: Int): String =
        if (Metrics.skin.persianNumerals) prose(value) else value.toString()

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

/** Which direction is in force, for the few controls that lay themselves out differently. */
val LocalThemeSkin: ProvidableCompositionLocal<ThemeSkin> = compositionLocalOf { ThemeSkin.EMBER }

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
 * @param skin which of the four directions to paint in.
 * @param dark whether to use that direction's night palette. Defaults to the system setting, so a
 *   user whose phone is light gets the light one — and everyone else the dark.
 */
@Composable
fun PixelLabTheme(
    skin: ThemeSkin = ThemeSkin.EMBER,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = skin.palette(dark)
    Ink.use(palette)
    Metrics.use(skin)
    CompositionLocalProvider(
        LocalDarkTheme provides dark,
        LocalThemeSkin provides skin,
    ) {
        MaterialTheme(
            colorScheme = schemeFor(palette, dark),
            typography = typographyFor(skin.mono),
            content = content,
        )
    }
}
