package ir.pixellab.engine.android

import android.graphics.Bitmap
import io.kotest.matchers.shouldBe
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.paint.BrushPreset
import ir.pixellab.core.paint.BrushTip
import ir.pixellab.core.paint.Stamp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The clone stamp.
 *
 * It shares the whole of the brush machinery, so what is left to check is only the part that is its
 * own: that the copied pixels come from where the offset says, that they arrive with the brush's own
 * falloff rather than as a hard disc, and that the brush's colour has nothing to do with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloneStampTest {

    private val rasterizer = BrushRasterizer()
    private val size = 64

    private val red = 0xFFDD2222.toInt()
    private val blue = 0xFF2244DD.toInt()

    /** Red on the left half, blue on the right, so where a copy came from is unambiguous. */
    private fun halves(): RasterImage = RasterImage(
        size, size,
        IntArray(size * size) { if (it % size < size / 2) red else blue },
    )

    private fun asBitmap(image: RasterImage): Bitmap =
        Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)

    private fun stamp(at: Vec2, diameter: Float = 16f, flow: Float = 1f) = Stamp(
        position = at,
        size = diameter,
        angle = 0f,
        roundness = 1f,
        flow = flow,
        // Deliberately a colour that appears nowhere in the source: if any of it reaches the canvas
        // the shader is tinting the copy instead of only masking it.
        color = Color(0f, 1f, 0f),
    )

    private fun pixelAt(image: RasterImage, x: Int, y: Int) = image.pixels[y * size + x]

    private fun channels(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    @Test
    fun `a dab copies from the offset the caller gave`() {
        val source = halves()
        val stroke = rasterizer.begin(size, size, BrushPreset.CLONE)
        // Painting at x=48 (blue side) while copying from 32 pixels to the left (red side).
        rasterizer.addClone(stroke, listOf(stamp(Vec2(48f, 32f))), asBitmap(source), Vec2(32f, 0f))
        val painted = rasterizer.commit(source, stroke)

        val (r, g, b) = channels(pixelAt(painted, 48, 32))
        (r > 150 && b < 100) shouldBe true
        // And the brush's own green never appears — the falloff carries coverage, not colour.
        (g < 100) shouldBe true
    }

    @Test
    fun `the copy has the brush's falloff, not a hard edge`() {
        val source = halves()
        val stroke = rasterizer.begin(size, size, BrushPreset.CLONE.copy(tip = BrushTip.Round(hardness = 0f)))
        rasterizer.addClone(stroke, listOf(stamp(Vec2(48f, 32f), diameter = 24f)), asBitmap(source), Vec2(32f, 0f))
        val painted = rasterizer.commit(source, stroke)

        // Near the rim the copied red is only partly there, so the result is between the two
        // colours. A hard-edged stamp is the version people recognise instantly as a clone.
        val (rimR, _, rimB) = channels(pixelAt(painted, 58, 32))
        val (coreR, _, coreB) = channels(pixelAt(painted, 48, 32))
        (coreR > rimR) shouldBe true
        (rimB > coreB) shouldBe true
    }

    @Test
    fun `nothing outside the dab is touched`() {
        val source = halves()
        val stroke = rasterizer.begin(size, size, BrushPreset.CLONE)
        rasterizer.addClone(stroke, listOf(stamp(Vec2(48f, 32f))), asBitmap(source), Vec2(32f, 0f))
        val painted = rasterizer.commit(source, stroke)

        pixelAt(painted, 4, 4) shouldBe red
        pixelAt(painted, 60, 60) shouldBe blue
    }

    @Test
    fun `a zero flow dab lays down nothing`() {
        val source = halves()
        val stroke = rasterizer.begin(size, size, BrushPreset.CLONE)
        rasterizer.addClone(stroke, listOf(stamp(Vec2(48f, 32f), flow = 0f)), asBitmap(source), Vec2(32f, 0f))
        // Not merely invisible: the stroke must not even count as touched, or committing it would
        // rewrite the layer's pixels for nothing and put a no-op on the undo stack.
        (rasterizer.commit(source, stroke) === source) shouldBe true
    }

    @Test
    fun `the clone preset copies rather than paints`() {
        BrushPreset.CLONE.clone shouldBe true
        // Soft by default; a hard clone brush reads as a pasted disc however good the source is.
        (BrushPreset.CLONE.tip as BrushTip.Round).hardness shouldBe 0.5f
        BrushPreset.ALL.count { it.clone } shouldBe 1
    }
}
