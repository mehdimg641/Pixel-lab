package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.paint.BrushPreset
import ir.pixellab.core.paint.BrushTip
import ir.pixellab.core.paint.Marquee
import ir.pixellab.core.paint.Stamp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Painting, against real Skia.
 *
 * The planner decides everything interesting about a brush and is tested without a device; what is
 * left here is whether a dab lands where it was told, whether hardness means what Photoshop means by
 * it, and — the one that is easy to get wrong and invisible until someone complains — whether flow
 * and opacity are actually different things.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrushRasterizerTest {

    private val rasterizer = BrushRasterizer()
    private val size = 64

    private fun blank() = RasterImage(size, size, IntArray(size * size))

    private fun stamp(at: Vec2, diameter: Float = 20f, flow: Float = 1f, color: Color = Color.BLACK) =
        Stamp(position = at, size = diameter, angle = 0f, roundness = 1f, flow = flow, color = color)

    private fun alphaAt(image: RasterImage, x: Int, y: Int) = (image.pixels[y * size + x] ushr 24) and 0xFF

    @Test
    fun `a dab lands where it was told`() {
        val stroke = rasterizer.begin(size, size, BrushPreset.HARD)
        rasterizer.add(stroke, listOf(stamp(Vec2(32f, 32f))))
        val painted = rasterizer.commit(blank(), stroke)

        (alphaAt(painted, 32, 32) > 200) shouldBe true
        alphaAt(painted, 2, 2) shouldBe 0
    }

    @Test
    fun `a hard tip has a sharp edge and a soft one does not`() {
        val hard = paintOne(BrushPreset(tip = BrushTip.Round(hardness = 1f)))
        val soft = paintOne(BrushPreset(tip = BrushTip.Round(hardness = 0f)))

        // Measured a quarter of the way out from the centre: a hard tip is still solid there and a
        // soft one has already faded. Hardness as a blur radius would leave both solid.
        (alphaAt(hard, 37, 32) > 200) shouldBe true
        (alphaAt(soft, 37, 32) < 180) shouldBe true
    }

    @Test
    fun `hardness is a fraction of the radius, so a soft brush stays soft when resized`() {
        val small = paintOne(BrushPreset(tip = BrushTip.Round(hardness = 0.5f)), diameter = 20f)
        val large = paintOne(BrushPreset(tip = BrushTip.Round(hardness = 0.5f)), diameter = 40f)
        // Sampled at the same fraction of each radius. If hardness were an absolute falloff width,
        // the large brush would read as much harder than the small one.
        val smallEdge = alphaAt(small, 32 + 7, 32)
        val largeEdge = alphaAt(large, 32 + 14, 32)
        (kotlin.math.abs(smallEdge - largeEdge) < 60) shouldBe true
    }

    @Test
    fun `low flow builds up where a stroke crosses itself`() {
        val preset = BrushPreset(tip = BrushTip.Round(hardness = 1f), flow = 0.2f, opacity = 1f)
        val once = rasterizer.begin(size, size, preset).also {
            rasterizer.add(it, listOf(stamp(Vec2(32f, 32f), flow = 0.2f)))
        }
        val twice = rasterizer.begin(size, size, preset).also {
            rasterizer.add(it, List(4) { stamp(Vec2(32f, 32f), flow = 0.2f) })
        }
        // The defining behaviour of flow, and the reason an airbrush works at all.
        (alphaAt(rasterizer.commit(blank(), twice), 32, 32) >
            alphaAt(rasterizer.commit(blank(), once), 32, 32)) shouldBe true
    }

    @Test
    fun `opacity is a ceiling on the whole stroke, not on each dab`() {
        val preset = BrushPreset(tip = BrushTip.Round(hardness = 1f), flow = 1f, opacity = 0.5f)
        val stroke = rasterizer.begin(size, size, preset)
        // Eight fully opaque dabs on the same spot. If opacity were applied per dab they would
        // compound to nearly solid; as a ceiling on the stroke the answer stays half.
        rasterizer.add(stroke, List(8) { stamp(Vec2(32f, 32f)) })
        val alpha = alphaAt(rasterizer.commit(blank(), stroke), 32, 32)
        (alpha in 110..145) shouldBe true
    }

    @Test
    fun `an eraser removes rather than adds`() {
        val filled = RasterImage(size, size, IntArray(size * size) { 0xFF204080.toInt() })
        val stroke = rasterizer.begin(size, size, BrushPreset.ERASER)
        rasterizer.add(stroke, listOf(stamp(Vec2(32f, 32f), diameter = 24f)))
        val erased = rasterizer.commit(filled, stroke)

        (alphaAt(erased, 32, 32) < 40) shouldBe true
        alphaAt(erased, 2, 2) shouldBe 255
    }

    @Test
    fun `a stroke keeps the pixels it did not touch`() {
        val filled = RasterImage(size, size, IntArray(size * size) { 0xFF00FF00.toInt() })
        val stroke = rasterizer.begin(size, size, BrushPreset.HARD)
        rasterizer.add(stroke, listOf(stamp(Vec2(10f, 10f), diameter = 8f)))
        val painted = rasterizer.commit(filled, stroke)
        painted.pixels[50 * size + 50] shouldBe 0xFF00FF00.toInt()
    }

    @Test
    fun `an untouched stroke leaves the layer exactly as it was`() {
        val filled = RasterImage(size, size, IntArray(size * size) { 0xFF123456.toInt() })
        val stroke = rasterizer.begin(size, size, BrushPreset.HARD)
        // Not an optimisation: copying the layer on every touch event that produced no dab would
        // allocate a full buffer per frame of a resting finger.
        rasterizer.commit(filled, stroke) shouldBe filled
    }

    @Test
    fun `a selection confines the stroke`() {
        val selection = Marquee.rectangle(size, size, Rect(0f, 0f, 32f, 64f))
        val stroke = rasterizer.begin(size, size, BrushPreset.HARD)
        // A dab straddling the boundary: everything past it must be untouched, not painted and then
        // partly restored — restoring would lose whatever was underneath where the edge is soft.
        rasterizer.add(stroke, listOf(stamp(Vec2(32f, 32f), diameter = 30f)))
        val painted = rasterizer.commit(blank(), stroke, selection)

        (alphaAt(painted, 22, 32) > 200) shouldBe true
        alphaAt(painted, 42, 32) shouldBe 0
    }

    @Test
    fun `a feathered selection fades the stroke rather than cutting it`() {
        val selection = Marquee.rectangle(size, size, Rect(0f, 0f, 32f, 64f)).feathered(6f)
        val stroke = rasterizer.begin(size, size, BrushPreset.HARD)
        rasterizer.add(stroke, listOf(stamp(Vec2(32f, 32f), diameter = 40f)))
        val painted = rasterizer.commit(blank(), stroke, selection)
        (alphaAt(painted, 32, 32) in 1..254) shouldBe true
    }

    @Test
    fun `roundness squashes the dab along its own angle`() {
        val stroke = rasterizer.begin(size, size, BrushPreset.HARD)
        rasterizer.add(
            stroke,
            listOf(Stamp(Vec2(32f, 32f), size = 40f, angle = 0f, roundness = 0.25f, flow = 1f, color = Color.BLACK)),
        )
        val painted = rasterizer.commit(blank(), stroke)
        // Wide across, thin down: the shape a chisel nib makes.
        (alphaAt(painted, 48, 32) > 150) shouldBe true
        alphaAt(painted, 32, 48) shouldBe 0
    }

    @Test
    fun `the dab takes its colour from the stroke`() {
        val stroke = rasterizer.begin(size, size, BrushPreset.HARD)
        rasterizer.add(stroke, listOf(stamp(Vec2(32f, 32f), color = Color(1f, 0f, 0f))))
        val painted = rasterizer.commit(blank(), stroke)
        val pixel = painted.pixels[32 * size + 32]
        ((pixel shr 16) and 0xFF > 200) shouldBe true
        ((pixel shr 8) and 0xFF < 60) shouldBe true
    }

    private fun paintOne(preset: BrushPreset, diameter: Float = 20f): RasterImage {
        val stroke = rasterizer.begin(size, size, preset)
        rasterizer.add(stroke, listOf(stamp(Vec2(32f, 32f), diameter = diameter)))
        return rasterizer.commit(blank(), stroke)
    }
}
