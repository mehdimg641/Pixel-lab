package ir.pixellab.app

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Whether the interface can be read.
 *
 * Nobody had ever measured this. Both palettes were assembled by eye, and a palette assembled by
 * eye fails in a specific, predictable way: it is checked against the *card* — white, or near-black
 * — and never against the sunken field a text box sits in. The colour then looks right in a swatch
 * and is unreadable on the one panel that matters. The previous light amber was 4.0:1 on the sunken
 * surface, which is a warning colour that cannot be read as a warning.
 *
 * So every text-bearing colour is checked against **all four surfaces**, not the one it was chosen
 * against. WCAG 2.2 AA asks 4.5:1 for body text; that is the bar here, and it is the first
 * accessibility rule the UI skills put at priority one.
 */
class ThemeContrastTest {

    /** Relative luminance, WCAG 2.x — the sRGB transfer curve, then Rec. 709 weights. */
    private fun luminance(color: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun surfacesOf(palette: Palette) = mapOf(
        "ground" to palette.ground,
        "surface1" to palette.surface1,
        "surface2" to palette.surface2,
        "surface3" to palette.surface3,
    )

    /** Everything that ever carries a letter. Not the decorative colours — see the disabled test. */
    private fun textColoursOf(palette: Palette) = mapOf(
        "textPrimary" to palette.textPrimary,
        "textSecondary" to palette.textSecondary,
        "accent" to palette.accent,
        "selection" to palette.selection,
        "success" to palette.success,
        "warning" to palette.warning,
        "error" to palette.error,
    )

    private fun assertReadable(name: String, palette: Palette) {
        val failures = buildList {
            for ((textName, text) in textColoursOf(palette)) {
                for ((surfaceName, surface) in surfacesOf(palette)) {
                    val ratio = contrast(text, surface)
                    if (ratio < AA_BODY) {
                        add("$name.$textName on $surfaceName is %.2f:1".format(ratio))
                    }
                }
            }
        }
        // Every failure at once rather than the first: fixing a palette one assertion at a time is
        // six rebuilds, and the fix for one hue usually is the fix for its neighbour.
        assertTrue(
            "these pairs fall below WCAG AA ($AA_BODY:1):\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /**
     * Every palette the application can be in: four directions, day and night.
     *
     * This used to be two entries. The four directions arrived from a browser mock-up, and a
     * mock-up is checked by eye against the one panel the designer had open — which is exactly the
     * failure described above, now with eight chances to happen instead of two. Five of the six
     * supplied `dim` values and all three light accents were below the bar when measured; they were
     * corrected in `Theme.kt` by shifting lightness only. This list is what stops the next hue
     * adjustment from quietly undoing that.
     */
    private fun everyPalette(): List<Pair<String, Palette>> = buildList {
        for (skin in ThemeSkin.entries) {
            add("${skin.name}.dark" to skin.palette(dark = true))
            add("${skin.name}.light" to skin.palette(dark = false))
        }
    }

    @Test
    fun `every text colour is readable on every surface, in every direction`() {
        for ((name, palette) in everyPalette()) assertReadable(name, palette)
    }

    @Test
    fun `a filled accent can be read by its own label`() {
        // The one pair that is not text-on-surface: a chosen chip is the accent with onAccent
        // written across it, and getting this wrong makes the *selected* item the unreadable one.
        for ((name, palette) in everyPalette()) {
            val ratio = contrast(palette.onAccent, palette.accent)
            assertTrue("$name onAccent on accent is %.2f:1".format(ratio), ratio >= AA_BODY)
        }
    }

    @Test
    fun `a disabled control reads as disabled`() {
        // Deliberately *below* the text bar, and asserted from that side. A disabled colour that
        // passed 4.5:1 would look as definite as an enabled one, and the state would stop being
        // legible as a state — which is the failure the last three panels actually had.
        for ((name, palette) in everyPalette()) {
            val ratio = contrast(palette.textDisabled, palette.ground)
            assertTrue("$name textDisabled is %.2f:1 — too strong to read as disabled".format(ratio), ratio < AA_BODY)
            // ...but still visible. Invisible is not disabled either; it is missing.
            assertTrue("$name textDisabled is %.2f:1 — invisible, not disabled".format(ratio), ratio >= VISIBLE)
        }
    }

    @Test
    fun `a border separates the surfaces it sits between`() {
        // A hairline that cannot be seen is a hairline that is not doing its job, and it is why the
        // panels read as one undifferentiated slab in the screenshots that started this.
        for ((name, palette) in everyPalette()) {
            val ratio = contrast(palette.borderStrong, palette.surface1)
            assertTrue("$name borderStrong on surface1 is %.2f:1".format(ratio), ratio >= NON_TEXT)
        }
    }

    @Test
    fun `a read-out plate is opaque enough to read over any artwork`() {
        // `Ink.Overlay` is the fill behind the zoom and size read-outs that float on the canvas,
        // and it is the one surface whose *backdrop* is arbitrary — a photograph. A translucent
        // panel colour over a white sky is white, and the read-out disappears exactly when somebody
        // has zoomed into something bright. The guard is on the alpha rather than on a contrast
        // ratio, because there is no second colour to measure against: the requirement is that the
        // plate hides what is behind it.
        for ((name, palette) in everyPalette()) {
            val alpha = palette.overlay.alpha
            assertTrue(
                "$name overlay is %.2f opaque — a read-out on it would take the colour of whatever "
                    .format(alpha) + "photograph is behind it",
                alpha >= 0.85f,
            )
            // And the text has to survive on the *composite*, not on the token. Over black artwork
            // the plate is at its darkest, so that is the case to measure.
            val overBlack = composite(palette.overlay, Color.Black)
            assertTrue(
                "$name text on an overlay plate over black artwork is %.2f:1"
                    .format(contrast(palette.textPrimary, overBlack)),
                contrast(palette.textPrimary, overBlack) >= AA_BODY,
            )
            val overWhite = composite(palette.overlay, Color.White)
            assertTrue(
                "$name text on an overlay plate over white artwork is %.2f:1"
                    .format(contrast(palette.textPrimary, overWhite)),
                contrast(palette.textPrimary, overWhite) >= AA_BODY,
            )
        }
    }

    /** Source-over: what the eye actually sees when a translucent plate sits on a backdrop. */
    private fun composite(top: Color, under: Color): Color = Color(
        red = top.red * top.alpha + under.red * (1f - top.alpha),
        green = top.green * top.alpha + under.green * (1f - top.alpha),
        blue = top.blue * top.alpha + under.blue * (1f - top.alpha),
    )

    private companion object {
        /** WCAG 2.2 AA, body text. */
        const val AA_BODY = 4.5

        /** WCAG 2.2 AA, user-interface components and graphical objects. */
        const val NON_TEXT = 3.0

        /** Below this a colour is not dimmed, it is gone. */
        const val VISIBLE = 1.8
    }
}
