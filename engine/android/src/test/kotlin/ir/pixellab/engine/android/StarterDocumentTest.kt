package ir.pixellab.engine.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import ir.pixellab.core.editor.Library
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.model.Layer
import ir.pixellab.core.render.EffectRaster
import ir.pixellab.core.render.Raster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * What is actually on screen when somebody opens the application.
 *
 * ### Why this did not exist, and what it cost
 *
 * Every screenshot test in this repository renders *chrome* — a toolbar, a sheet, a picker. **Not
 * one of them rendered a document.** So the first thing a user sees had never been looked at by
 * anything, and what they saw was their word sitting on a mass of black they had not asked for and
 * could not name. Three waves of features were built on top of that, all green.
 *
 * The starter document is one text layer. It should be one text layer's worth of ink. That is the
 * whole assertion, and it is the one nobody was making.
 *
 * ### What this cannot check, stated plainly
 *
 * This runs the **CPU** effect path, because the GPU path needs a real driver and there is none in
 * this environment — which is precisely why the defect that drew a filled rectangle instead of an
 * outline survived every test here and appeared on the first phone it met. The encoding that fixes
 * that is checked in `core:render`'s `PackedFieldTest`, and the only real proof is an installed
 * build. A test that pretended otherwise would be worse than this one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class StarterDocumentTest {

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

    /** The starter document's one text layer, at a size a test can look at. */
    private fun title(): Layer.Text {
        val template = Library.templates.first { it.name == "پست اینستاگرام" }
        return Library.documentFor(template).layers.filterIsInstance<Layer.Text>().single()
    }

    /** The layer's coverage, as the effect stack receives it: white pixels with the glyph alpha. */
    private fun coverage(layer: Layer.Text, size: Int): Raster {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shaped = TextRasterizer().rasterize(layer.spec.copy(size = size / 6f), vazirmatn())
        canvas.translate(MARGIN, size / 2f)
        canvas.drawPath(shaped.outline, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })

        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        return Raster(size, size, pixels)
    }

    // ---- the assertion nobody was making --------------------------------------------------------

    @Test
    fun `the starter document is one word, not a word on a slab`() {
        val layer = title()
        val word = coverage(layer, SIZE)
        val rendered = EffectRaster.apply(word, layer.style)

        // The four corners of the canvas. A headline occupies the middle of a square; if the corners
        // are painted, whatever painted them is not the letters.
        val corners = listOf(
            0 to 0,
            SIZE - 1 to 0,
            0 to SIZE - 1,
            SIZE - 1 to SIZE - 1,
        )
        for ((x, y) in corners) {
            val alpha = rendered.at(x, y)
            assertEquals(
                "the starter document paints its corner at ($x, $y). A new document is one text " +
                    "layer and should be one text layer's worth of ink — anything reaching the " +
                    "corner is decoration nobody asked for, or an effect that has stopped " +
                    "following the letters.",
                0f,
                alpha,
                0.02f,
            )
        }
    }

    @Test
    fun `the starter layer carries no effects to go wrong`() {
        // The stronger form of the same point, and the one that fails fastest. The layer used to
        // arrive wearing a preset with a ten-pixel black stroke and a black drop shadow, so the
        // first thing on screen was decoration — and every rendering fault in the effect stack was
        // in the picture before the user had done anything at all.
        val layer = title()
        assertTrue(
            "the starter document ships ${layer.style.effects.size} layer effects; it should ship " +
                "none, so that what a user sees first is the thing they recognise",
            layer.style.effects.isEmpty(),
        )
    }

    @Test
    fun `the starter layer is painted in a colour that shows on a white canvas`() {
        // The other half of the same mistake: the preset's fill was white, which on the white
        // canvas the template also specifies is invisible on its own. It only read at all *because*
        // of the black decoration around it — so removing the decoration and leaving the fill would
        // have produced an apparently empty document.
        val fill = title().style.fill
        assertTrue("the starter title is filled with $fill, which is not visible on white", fill.isDark())
    }

    private fun ir.pixellab.core.model.Fill.isDark(): Boolean {
        val solid = this as? ir.pixellab.core.model.Fill.Solid ?: return true
        return solid.color.r + solid.color.g + solid.color.b < 1.5f
    }

    private fun Raster.at(x: Int, y: Int): Float = (pixels[y * width + x] ushr 24) / 255f

    private companion object {
        const val SIZE = 512
        const val MARGIN = 40f
    }
}
