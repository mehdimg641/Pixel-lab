package ir.pixellab.engine.android

import android.graphics.RectF
import io.kotest.matchers.shouldBe
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.TextSpec
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Do the faces the APK ships actually draw Persian?
 *
 * A `cmap` covering the Arabic block says the glyphs are *present*. It says nothing about whether
 * the shaper can assemble them: joining, mark positioning and — for a Nastaliq face — a cascade of
 * contextual substitutions deep enough that a shaper without the right feature support returns a
 * row of disconnected isolated forms rather than a word.
 *
 * That distinction decides whether Gulzar is worth the 944 KB it adds to every install, which is
 * two-thirds of the font payload on its own. So this renders a real word with each face and
 * measures the ink.
 *
 * The assertions are deliberately coarse — ink exists, and the word is wider than it is tall.
 * Anything finer would be asserting the design of somebody else's typeface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class BundledFaceRenderTest {

    /** Persian with three joining groups and a descender: a word that exercises the shaper. */
    private val word = "گنجشک"

    private val bundled = File("../../app/android/src/main/assets/fonts")

    private fun face(fileName: String, family: String): FontFile {
        val file = File(bundled, fileName)
        check(file.exists()) { "${file.absolutePath} is missing — the bundled faces moved" }
        return FontFile(
            path = file.absolutePath,
            family = family,
            subfamily = "Regular",
            postScriptName = "$family-Regular",
            fullName = "$family Regular",
            weight = 400,
            italic = false,
            axes = emptyMap(),
            features = emptySet(),
            script = Script.ARABIC,
            hasPersianDigits = true,
            hasTatweel = true,
        )
    }

    /**
     * Shapes the word and returns the outline's bounds.
     *
     * The outline rather than a bitmap, because that is what the rasteriser actually produces and
     * what every downstream path uses — measuring, extruding to 3D, converting to a shape. An empty
     * outline is the failure this is looking for: it is what a face returns when the shaper cannot
     * reach its glyphs.
     */
    private fun bounds(font: FontFile): RectF {
        val spec = TextSpec(text = word, font = FontRef(family = font.family), size = 96f)
        val shaped = TextRasterizer().rasterize(spec, font)
        // The compiled SDK still only offers the two-argument form, which is deprecated because
        // its second argument never did anything. Suppressed rather than worked around.
        @Suppress("DEPRECATION")
        return RectF().also { shaped.outline.computeBounds(it, true) }
    }

    private fun assertDrawsPersian(fileName: String, family: String) {
        val box = bounds(face(fileName, family))

        // An outline at all. A face whose glyphs the shaper cannot reach returns an empty path.
        (box.width() > 1f && box.height() > 1f) shouldBe true
        // Persian runs right to left and this word has five letters, so it is decidedly wider than
        // one glyph is tall. A shaper that failed and stacked everything at the origin fails here.
        (box.width() > box.height()) shouldBe true
    }

    @Test
    fun `Vazirmatn draws the word`() = assertDrawsPersian("Vazirmatn-Regular.ttf", "Vazirmatn")

    @Test
    fun `Lalezar draws the word`() = assertDrawsPersian("Lalezar-Regular.ttf", "Lalezar")

    @Test
    fun `Gulzar draws the word`() {
        // The one that had to be checked rather than assumed. Nastaliq is the deepest contextual
        // shaping in common use — letters cascade down a steep diagonal and the joining forms are
        // chosen several glyphs ahead — so a shaper that handles Naskh perfectly can still return
        // a disconnected row here. At 944 KB it is two-thirds of the font payload, and a face that
        // does not shape is not worth a byte of it.
        assertDrawsPersian("Gulzar-Regular.ttf", "Gulzar")
    }

    @Test
    fun `a Nastaliq word is taller than a Naskh one at the same size`() {
        // The property that says the cascade actually happened. Nastaliq stacks its letters down a
        // diagonal, so the same word occupies far more vertical space relative to its width than in
        // a horizontal face. If Gulzar came back the same shape as Vazirmatn, the shaper flattened
        // it to isolated forms and the file is doing nothing the smaller face does not already do.
        val gulzar = bounds(face("Gulzar-Regular.ttf", "Gulzar"))
        val vazir = bounds(face("Vazirmatn-Regular.ttf", "Vazirmatn"))

        (gulzar.height() / gulzar.width() > vazir.height() / vazir.width()) shouldBe true
    }
}
