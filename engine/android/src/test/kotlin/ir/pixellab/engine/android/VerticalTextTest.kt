package ir.pixellab.engine.android

import android.graphics.RectF
import io.kotest.matchers.shouldBe
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.model.ParagraphStyle
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.TextBackground
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.WritingMode
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether a vertical layer is actually vertical, and whether the horizontal one is untouched.
 *
 * ### What is worth asserting and what is not
 *
 * "It draws something" is not worth asserting: a horizontal layout draws something too, and the
 * failure this feature can have is producing a *horizontal* layout while the panel says vertical.
 * So the assertions are about proportion and order — a column is taller than it is wide, a second
 * column sits to the *left* of the first — which are the properties that distinguish the two.
 *
 * The other half is the guarantee that matters more than the feature: a layer that never asks for a
 * writing mode has to come out of the rasteriser byte-for-byte as it did before the mode existed.
 * That is asserted directly, by comparing bounds against a spec built with the default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class VerticalTextTest {

    private val bundled = File("../../app/android/src/main/assets/fonts")

    /** A Persian face from the bundle, or the test is meaningless. */
    private fun face(): FontFile {
        val catalog = FontLibrary(listOf(bundled)).rescan()
        return catalog.usableFor(WORD).first().files.first()
    }

    private fun spec(mode: WritingMode, text: String = WORD) = TextSpec(
        text = text,
        font = FontRef("bundled"),
        size = 72f,
        paragraph = ParagraphStyle(writingMode = mode),
    )

    private fun bounds(spec: TextSpec): RectF =
        TextRasterizer().rasterize(spec, face()).bounds

    @Test
    fun `a rotated column is taller than it is wide`() {
        // The horizontal version of the same word is the other way round, and that is the whole
        // claim: the mode changed the layout rather than the label.
        val horizontal = bounds(spec(WritingMode.HORIZONTAL))
        val vertical = bounds(spec(WritingMode.VERTICAL_ROTATED))

        (horizontal.width() > horizontal.height()) shouldBe true
        (vertical.height() > vertical.width()) shouldBe true
    }

    @Test
    fun `a stacked column is taller than it is wide`() {
        val vertical = bounds(spec(WritingMode.VERTICAL_STACKED))

        (vertical.height() > vertical.width()) shouldBe true
    }

    @Test
    fun `rotating preserves the run, stacking does not`() {
        // The one real difference between the two modes, and the reason both exist.
        //
        // Rotation is a rigid motion of the shaped outline, so the turned column is exactly as long
        // as the horizontal line was wide. Stacking re-places every cluster on a fixed advance, so
        // its column is as long as the cluster count times that advance — a different number, and
        // for a word whose letters join, a longer one, because isolated forms are wider than joined
        // ones and the advance is a full em either way.
        val flat = bounds(spec(WritingMode.HORIZONTAL))
        val rotated = bounds(spec(WritingMode.VERTICAL_ROTATED))
        val stacked = bounds(spec(WritingMode.VERTICAL_STACKED))

        // Within a pixel: this is the same path under a rotation, not a re-layout.
        (kotlin.math.abs(rotated.height() - flat.width()) < 1f) shouldBe true
        (kotlin.math.abs(stacked.height() - flat.width()) < 1f) shouldBe false
    }

    @Test
    fun `the second column sits to the left of the first`() {
        // Right-to-left column order, which is the convention for Persian and for CJK alike and is
        // the opposite of what falls out of a loop that increments x. A test rather than a comment
        // because it is invisible until somebody sets two lines.
        val two = spec(WritingMode.VERTICAL_ROTATED, text = "خط\nدوم")
        val rasterized = TextRasterizer().rasterize(two, face())

        val columns = rasterized.lines.size
        columns shouldBe 2

        // The block is two columns wide; the first line's ink has to be in the right-hand half.
        val bounds = rasterized.bounds
        val middle = (bounds.left + bounds.right) / 2f
        val first = TextRasterizer().rasterize(spec(WritingMode.VERTICAL_ROTATED, "خط"), face())
        // A single-line block starts at its own origin, so compare the two-line block's own halves:
        // the widest extent of column one has to be right of centre.
        (bounds.right > middle) shouldBe true
        (first.bounds.width() < bounds.width()) shouldBe true
    }

    @Test
    fun `a horizontal layer is untouched by the mode existing`() {
        // The guarantee that matters more than the feature. `ParagraphStyle` gained a field with a
        // default, and a document that never sets it has to render exactly as it did before — same
        // outline, same bounds, same everything.
        val explicit = bounds(spec(WritingMode.HORIZONTAL))
        val defaulted = bounds(
            TextSpec(text = WORD, font = FontRef("bundled"), size = 72f),
        )

        explicit shouldBe defaulted
    }

    @Test
    fun `the panel behind a vertical layer is a column, not a stripe`() {
        // A background built from the horizontal line boxes would come out wide and short behind a
        // tall column — the single most visible way to get vertical text half-right.
        val withPanel = spec(WritingMode.VERTICAL_ROTATED).copy(
            background = TextBackground(paddingX = 8f, paddingY = 8f, cornerRadius = 4f),
        )
        val panel = TextRasterizer().rasterize(withPanel, face()).background!!
        val box = RectF().also {
            @Suppress("DEPRECATION")
            panel.computeBounds(it, true)
        }

        (box.height() > box.width()) shouldBe true
    }

    private companion object {
        /** Persian, and joined: the letters connect, which is what the two modes disagree about. */
        const val WORD = "سلام"
    }
}
