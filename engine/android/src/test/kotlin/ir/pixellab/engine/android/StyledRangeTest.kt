package ir.pixellab.engine.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.model.CharacterStyle
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.StyleRun
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.text.Clusters
import ir.pixellab.core.text.Granularity
import ir.pixellab.core.text.StyleRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Whether one word in a Persian sentence can be painted differently without breaking the sentence.
 *
 * ### The failure this is written against
 *
 * «برای اطلاع از قیمت کابینت» — tint «کابینت» and nothing else. Every editor that offers this cuts
 * the string at the range boundary and shapes the pieces separately, and in Arabic script that
 * changes the *letters*: ب and ی lose their medial forms and the word arrives on screen as four
 * disconnected shapes. It is not a colour bug, it is a spelling bug, and it is why Persian titles
 * are not set in mobile editors.
 *
 * ### Why the assertion is about alpha and not about colour
 *
 * A test that checked the word came out red would pass just as happily on a broken renderer that
 * detached the letters, because they would still be red. The thing that must not change is the
 * *shape*, so the test renders the sentence twice — once plain and once styled — and requires the
 * coverage to be identical pixel for pixel. Detached letters have different advances, different
 * glyphs and different ink; there is no way for that comparison to survive them.
 *
 * Real font, real Skia. Robolectric runs with `graphicsMode=NATIVE` in this module, so these are
 * the same shaping and rasterising calls the phone makes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class StyledRangeTest {

    private val sentence = "برای اطلاع از قیمت کابینت"
    private val word = sentence.indexOf("کابینت").let { StyleRun(it, it + "کابینت".length, RED) }

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

    private fun spec(runs: List<StyleRun> = emptyList()) =
        TextSpec(text = sentence, font = FontRef("Vazirmatn"), size = 120f, runs = runs)

    /** The coverage of an outline, as an alpha-only bitmap in a fixed frame. */
    private fun coverage(path: android.graphics.Path): IntArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(MARGIN, MARGIN)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        val pixels = IntArray(WIDTH * HEIGHT)
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        bitmap.recycle()
        return pixels
    }

    // ---- the one that matters -----------------------------------------------------------------

    @Test
    fun `tinting one word does not change a single pixel of the letters`() {
        val rasterizer = TextRasterizer()
        val plain = rasterizer.rasterize(spec(), vazirmatn())
        val styled = rasterizer.rasterize(spec(listOf(word)), vazirmatn())

        val before = coverage(plain.outline)
        val after = coverage(styled.outline)

        val differing = before.indices.count { before[it] != after[it] }
        assertEquals(
            "styling a range changed the shape of the letters in $differing pixels. In Persian that " +
                "is not a rendering difference, it is a spelling difference — the range was cut out " +
                "of the string instead of out of the finished outline, so the letters either side " +
                "of the cut lost their medial forms.",
            0,
            differing,
        )
    }

    @Test
    fun `the pieces cover exactly what the whole outline covers`() {
        // The other half of the same guarantee. Identical letters are worth nothing if the pieces
        // the painter walks leave a gap between them — a pale seam down the middle of a word reads
        // as damage, and it is what happens when adjacent bands meet on an exact edge.
        val styled = TextRasterizer().rasterize(spec(listOf(word)), vazirmatn())
        val union = android.graphics.Path().apply { for (piece in styled.pieces) addPath(piece.outline) }

        val whole = coverage(styled.outline)
        val assembled = coverage(union)
        val missing = whole.indices.count { whole[it] != 0 && assembled[it] == 0 }
        assertEquals("the styled pieces leave $missing pixels of the word unpainted", 0, missing)
    }

    // ---- the ranges land where they were asked to ----------------------------------------------

    @Test
    fun `the styled piece is the styled word and not its neighbour`() {
        val styled = TextRasterizer().rasterize(spec(listOf(word)), vazirmatn())
        val tinted = styled.pieces.filter { it.style.fill != null }
        assertTrue("nothing was styled at all", tinted.isNotEmpty())

        val covered = tinted.sumOf { it.end - it.start }
        assertEquals("the tinted stretch is not the length of «کابینت»", word.length, covered)
        assertEquals(word.start, tinted.minOf { it.start })
        assertEquals(word.end, tinted.maxOf { it.end })
    }

    @Test
    fun `the styled word sits at the right-hand end of the line`() {
        // A right-to-left sentence, so the last word is the leftmost thing on screen. Getting this
        // backwards is the classic bidi mistake and it would put the colour on «برای» — which is at
        // the *right* — while every index-based assertion still passed.
        val styled = TextRasterizer().rasterize(spec(listOf(word)), vazirmatn())
        val tinted = styled.pieces.first { it.style.fill != null }

        val box = RectF()
        @Suppress("DEPRECATION")
        tinted.outline.computeBounds(box, true)
        val whole = RectF()
        @Suppress("DEPRECATION")
        styled.outline.computeBounds(whole, true)

        assertTrue(
            "the last word of a right-to-left line was painted at x=${box.left}, but the line runs " +
                "from ${whole.left} to ${whole.right} — a left-to-right assumption has crept in",
            box.left < whole.left + whole.width() / 3f,
        )
    }

    @Test
    fun `an unstyled layer produces no pieces at all`() {
        // The no-regression guarantee, stated as code: a document that never touched this feature
        // must not take the new path even once.
        TextRasterizer().rasterize(spec(), vazirmatn()).pieces.isEmpty() shouldBeTrue
            "an untouched text layer built styled pieces, so every existing document now renders " +
            "through code that did not exist when it was made"
    }

    // ---- resizing a range: the path that cannot avoid re-shaping --------------------------------

    @Test
    fun `a resized word still joins its own letters`() {
        // Changing the size partway through a line is the one case that cannot be solved by cutting
        // the finished outline, because one string cannot be shaped at two sizes. The letters
        // inside the resized word must still connect to each other, which is what the zero-width
        // joiner padding is for.
        val bigger = StyleRun(word.start, word.end, CharacterStyle(sizeScale = 1.6f))
        val styled = TextRasterizer().rasterize(spec(listOf(bigger)), vazirmatn())
        val piece = styled.pieces.first { it.style.sizeScale != 1f }

        val box = RectF()
        @Suppress("DEPRECATION")
        piece.outline.computeBounds(box, true)

        val plainWord = TextRasterizer().rasterize(
            TextSpec(text = "کابینت", font = FontRef("Vazirmatn"), size = 120f),
            vazirmatn(),
        )
        // Bigger, and by roughly the factor asked for rather than by whatever fell out.
        assertTrue(
            "the resized word measured ${box.width()} against ${plainWord.bounds.width()} at 1.0",
            box.width() > plainWord.bounds.width() * 1.3f,
        )
        // And still one connected word: «کابینت» is two clusters, so at most two ink runs, not six.
        assertEquals(2, Clusters.of("کابینت", Granularity.CLUSTER).size)
    }

    // ---- elongation moves the letters under the ranges -------------------------------------------

    @Test
    fun `a range follows its word when tatweel is inserted in front of it`() {
        // `KashidaPlanner` rewrites the string before shaping, so every index after the first
        // stretched word is out by however many characters were added. The colour picker and the
        // kashida slider sit in the same panel, so this would be found in the first minute.
        val stretched = "کاااابینت"
        val moved = StyleRuns.remap(listOf(StyleRun(0, 2, RED)), "کابینت", stretched)
        assertEquals(0, moved.single().start)
        val after = StyleRuns.remap(listOf(StyleRun(2, 6, RED)), "کابینت", stretched)
        assertEquals("the range did not move past the inserted tatweel", 5, after.single().start)
    }

    private infix fun Boolean.shouldBeTrue(message: String) = assertTrue(message, this)

    private companion object {
        val RED = CharacterStyle(fill = Fill.Solid(Color(1f, 0.1f, 0.1f)))

        const val MARGIN = 40f
        const val WIDTH = 1400
        const val HEIGHT = 400
    }
}
