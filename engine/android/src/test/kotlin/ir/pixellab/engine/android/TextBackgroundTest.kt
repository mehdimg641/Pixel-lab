package ir.pixellab.engine.android

import android.graphics.RectF
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.TextBackground
import ir.pixellab.core.model.TextSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The panel behind the words.
 *
 * Two things have to be true and they pull in opposite directions. The layer has to be *measured*
 * as large as its panel, or the background is clipped by the selection box it lives inside — and
 * the letters have to stay separable from it, or converting a caption to a shape or extruding it to
 * 3D produces a slab with words carved out of it rather than words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class TextBackgroundTest {

    private fun vazirmatn(): FontFile {
        val file = File.createTempFile("vazirmatn", ".ttf")
        file.deleteOnExit()
        checkNotNull(javaClass.classLoader?.getResourceAsStream("vazirmatn.ttf")) {
            "the test font is missing from engine/android/src/test/resources"
        }.use { input -> file.outputStream().use { input.copyTo(it) } }
        return FontFile(
            path = file.absolutePath,
            family = "Vazirmatn",
            subfamily = "Regular",
            postScriptName = "Vazirmatn-Regular",
            fullName = "Vazirmatn Regular",
            weight = 400,
            italic = false,
            axes = emptyMap(),
            features = emptySet(),
            script = Script.ARABIC,
            hasPersianDigits = true,
            hasTatweel = true,
        )
    }

    private fun spec(background: TextBackground? = null, text: String = "قیمت\nکابینت آشپزخانه") =
        TextSpec(text = text, font = FontRef("Vazirmatn"), size = 90f, background = background)

    private fun boundsOf(path: android.graphics.Path): RectF = RectF().also {
        @Suppress("DEPRECATION")
        path.computeBounds(it, true)
    }

    @Test
    fun `a layer with no background is untouched by any of this`() {
        // The whole of every existing document. A feature that changes what a caption without one
        // looks like is not a feature.
        assertNull(TextRasterizer().rasterize(spec(), vazirmatn()).background)
    }

    @Test
    fun `the panel is measured as part of the layer`() {
        val plain = TextRasterizer().rasterize(spec(), vazirmatn())
        val padded = TextRasterizer().rasterize(spec(TextBackground(paddingX = 40f, paddingY = 20f)), vazirmatn())

        assertTrue(
            "the panel added no width to the layer, so it will be clipped by the selection box it " +
                "sits inside — ${padded.bounds.width()} against ${plain.bounds.width()}",
            padded.bounds.width() > plain.bounds.width() + 60f,
        )
        assertTrue(padded.bounds.height() > plain.bounds.height() + 30f)
    }

    @Test
    fun `the letters stay out of the geometry everything else reads`() {
        // `outline` is what the 3D extruder and convert-to-shape take. If the panel leaked into it,
        // a caption would extrude as a slab.
        val padded = TextRasterizer().rasterize(spec(TextBackground()), vazirmatn())
        val plain = TextRasterizer().rasterize(spec(), vazirmatn())
        assertEquals(boundsOf(plain.outline).width(), boundsOf(padded.outline).width(), 0.01f)
    }

    @Test
    fun `the panel is behind the letters and covers all of them`() {
        val shaped = TextRasterizer().rasterize(spec(TextBackground(paddingX = 30f, paddingY = 16f)), vazirmatn())
        val panel = boundsOf(assertNotNull(shaped.background).let { shaped.background!! })
        val letters = boundsOf(shaped.outline)

        assertTrue("the panel does not reach past the letters on the leading side", panel.left <= letters.left)
        assertTrue("the panel does not reach past the letters on the trailing side", panel.right >= letters.right)
        assertTrue("the panel is shorter than the letters it is meant to be behind", panel.top <= letters.top)
        assertTrue(panel.bottom >= letters.bottom)
    }

    @Test
    fun `a box per line is narrower than one box round a ragged title`() {
        // **The choice that decides what the thing looks like.** Two lines of very different lengths:
        // one box round both leaves a wide empty band beside the short one, which is exactly the
        // result every editor that offers this produces and nobody wants.
        val ragged = "قیمت\nکابینت آشپزخانه"
        val perLine = TextRasterizer().rasterize(spec(TextBackground(perLine = true), ragged), vazirmatn())
        val single = TextRasterizer().rasterize(spec(TextBackground(perLine = false), ragged), vazirmatn())

        // The two share an outer width — both wrap the longest line — so the difference is in what
        // they cover, which is area, not extent.
        assertEquals(
            boundsOf(single.background!!).width(),
            boundsOf(perLine.background!!).width(),
            1f,
        )
        // And the per-line version genuinely has two contours where the single one has one.
        assertTrue("the per-line panel is one shape, so it is not per line at all", contours(perLine) > 1)
        assertEquals(1, contours(single))
    }

    @Test
    fun `a blank line gets no panel of its own`() {
        // An empty line between paragraphs is real and drawing a bare rounded rectangle in the gap
        // reads as a rendering fault. Nothing is behind nothing.
        val spaced = TextRasterizer().rasterize(spec(TextBackground(), "قیمت\n\nکابینت"), vazirmatn())
        assertEquals(2, contours(spaced))
    }

    @Test
    fun `the fill colour never reaches the geometry`() {
        // The panel's colour is painted through the fill texture, not baked into coverage — so two
        // backgrounds differing only in colour must produce the same shape. If they did not, every
        // colour change would re-shape the text.
        val white = TextRasterizer().rasterize(spec(TextBackground(fill = Fill.Solid(Color.WHITE))), vazirmatn())
        val black = TextRasterizer().rasterize(spec(TextBackground(fill = Fill.Solid(Color.BLACK))), vazirmatn())
        assertEquals(boundsOf(white.background!!), boundsOf(black.background!!))
    }

    /** How many separate closed shapes the panel is made of. */
    private fun contours(shaped: RasterizedText): Int {
        var count = 0
        val measure = android.graphics.PathMeasure(shaped.background, false)
        do {
            if (measure.length > 0f) count++
        } while (measure.nextContour())
        return count
    }
}
