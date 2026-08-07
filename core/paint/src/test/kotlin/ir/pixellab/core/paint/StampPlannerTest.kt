package ir.pixellab.core.paint

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Vec2
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/**
 * The brush.
 *
 * Almost everything that distinguishes a good brush from a bad one is decided before a single pixel
 * is touched: where the dabs go, how big each one is, how much ink it carries. Keeping that in a
 * pure planner is what makes it checkable at all — the same behaviour tested through a rasteriser
 * can only be inspected by looking at pictures.
 */
class StampPlannerTest {

    private fun line(from: Vec2, to: Vec2, samples: Int = 20, pressure: Float = 1f) =
        (0..samples).map { i ->
            val t = i.toFloat() / samples
            StrokePoint(
                position = Vec2(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t),
                pressure = pressure,
                timeMillis = (i * 8).toLong(),
            )
        }

    /** No smoothing, so the planned path is the path the test drew and distances are readable. */
    private fun preset(base: BrushPreset = BrushPreset()) = base.copy(smoothing = 0f)

    // ---- spacing -----------------------------------------------------------------------------

    @Test
    fun `dabs land at even distances, not at even sample counts`() {
        val planner = StampPlanner(preset(BrushPreset(size = 20f, spacing = 0.5f)))
        val stamps = planner.plan(line(Vec2(0f, 0f), Vec2(200f, 0f)))

        val gaps = stamps.zipWithNext { a, b -> hypot(b.position.x - a.position.x, b.position.y - a.position.y) }
        // A device samples at a fixed rate, so a fast stroke reports sparsely and a slow one piles
        // samples in one place. Stamping the raw samples is why a fast stroke in a naive brush comes
        // out as a dotted line.
        gaps.forEach { it shouldBe 10f.plusOrMinus(1.5f) }
    }

    @Test
    fun `spacing scales with the brush, so a preset behaves the same at any size`() {
        val small = StampPlanner(preset(BrushPreset(size = 10f, spacing = 0.25f)))
            .plan(line(Vec2(0f, 0f), Vec2(100f, 0f)))
        val large = StampPlanner(preset(BrushPreset(size = 40f, spacing = 0.25f)))
            .plan(line(Vec2(0f, 0f), Vec2(100f, 0f)))
        (small.size > large.size * 3) shouldBe true
    }

    @Test
    fun `a stroke drawn in two batches is spaced the same as one drawn in a single batch`() {
        val whole = StampPlanner(preset()).plan(line(Vec2(0f, 0f), Vec2(200f, 0f), samples = 40))

        val split = StampPlanner(preset()).let { planner ->
            val points = line(Vec2(0f, 0f), Vec2(200f, 0f), samples = 40)
            planner.plan(points.take(21)) + planner.plan(points.drop(21))
        }
        // Touch events arrive in batches of whatever size the system feels like. Without carrying
        // the remaining distance across batches, every batch boundary gets a double dab — visible
        // as a bead on any low-flow stroke.
        (abs(whole.size - split.size) <= 1) shouldBe true
    }

    @Test
    fun `a tap leaves a mark`() {
        val planner = StampPlanner(preset())
        val stamps = planner.finish(StrokePoint(Vec2(50f, 50f)))
        // A dot is a legitimate thing to draw, and a brush that needs movement to make one reads as
        // broken.
        stamps.isNotEmpty() shouldBe true
        stamps.first().position shouldBe Vec2(50f, 50f)
    }

    // ---- dynamics ----------------------------------------------------------------------------

    @Test
    fun `pressure drives the size`() {
        val brush = preset(BrushPreset(size = 40f, sizeDynamic = Dynamic(ControlSource.PRESSURE)))
        val light = StampPlanner(brush).plan(line(Vec2(0f, 0f), Vec2(100f, 0f), pressure = 0.25f))
        val heavy = StampPlanner(brush).plan(line(Vec2(0f, 0f), Vec2(100f, 0f), pressure = 1f))

        light.first().size shouldBe 10f.plusOrMinus(0.5f)
        heavy.first().size shouldBe 40f.plusOrMinus(0.5f)
    }

    @Test
    fun `a minimum keeps a light touch from vanishing`() {
        val brush = preset(
            BrushPreset(size = 40f, sizeDynamic = Dynamic(ControlSource.PRESSURE, minimum = 0.5f)),
        )
        val stamps = StampPlanner(brush).plan(line(Vec2(0f, 0f), Vec2(100f, 0f), pressure = 0f))
        // The minimum is a fraction of the base, so a preset behaves the same at every size. An
        // absolute floor would make one preset behave differently on a 10px and a 300px brush.
        stamps.first().size shouldBe 20f.plusOrMinus(0.5f)
    }

    @Test
    fun `pressure with no stylus still paints at full size`() {
        // Most Android phones report no pressure at all. A brush that fades to nothing under a
        // finger is a brush most of this app's users cannot use.
        val stamps = StampPlanner(preset(BrushPreset(size = 30f))).plan(line(Vec2(0f, 0f), Vec2(60f, 0f)))
        stamps.first().size shouldBe 30f.plusOrMinus(0.5f)
    }

    @Test
    fun `an inverted control makes pressure do the opposite`() {
        val brush = preset(
            BrushPreset(size = 40f, sizeDynamic = Dynamic(ControlSource.PRESSURE, inverted = true)),
        )
        val stamps = StampPlanner(brush).plan(line(Vec2(0f, 0f), Vec2(100f, 0f), pressure = 1f))
        (stamps.first().size < 5f) shouldBe true
    }

    @Test
    fun `a fade control thins the stroke as it goes`() {
        val brush = preset(
            BrushPreset(
                size = 40f,
                spacing = 0.25f,
                sizeDynamic = Dynamic(ControlSource.FADE, fadeSteps = 10),
            ),
        )
        val stamps = StampPlanner(brush).plan(line(Vec2(0f, 0f), Vec2(400f, 0f), samples = 80))
        (stamps.last().size < stamps.first().size) shouldBe true
    }

    // ---- determinism -------------------------------------------------------------------------

    @Test
    fun `the same stroke plans identically every time`() {
        val brush = preset(BrushPreset.SPRAY)
        val points = line(Vec2(0f, 0f), Vec2(200f, 100f))
        val first = StampPlanner(brush, seed = 7).plan(points)
        val second = StampPlanner(brush, seed = 7).plan(points)

        // Not a nicety: a stroke has to look the same when it is undone and redone, when the
        // document is re-rendered, and when it is exported at four times the size. A brush seeded
        // from the clock is a brush whose artwork changes every time it is opened.
        first.map { it.position } shouldBe second.map { it.position }
        first.map { it.size } shouldBe second.map { it.size }
        first.map { it.flow } shouldBe second.map { it.flow }
    }

    @Test
    fun `two strokes with different seeds scatter differently`() {
        val points = line(Vec2(0f, 0f), Vec2(200f, 0f))
        val a = StampPlanner(preset(BrushPreset.SPRAY), seed = 1).plan(points)
        val b = StampPlanner(preset(BrushPreset.SPRAY), seed = 2).plan(points)
        (a.map { it.position } != b.map { it.position }) shouldBe true
    }

    // ---- scattering --------------------------------------------------------------------------

    @Test
    fun `scattering spreads dabs off the path`() {
        val brush = preset(BrushPreset(size = 40f, scatter = 1f, count = 3))
        val stamps = StampPlanner(brush, seed = 3).plan(line(Vec2(0f, 100f), Vec2(200f, 100f)))
        val offPath = stamps.count { abs(it.position.y - 100f) > 1f }
        (offPath > stamps.size / 2) shouldBe true
    }

    @Test
    fun `scattering is across the stroke, not across the canvas`() {
        val brush = preset(BrushPreset(size = 40f, scatter = 1f, count = 1))
        // A vertical stroke: if scatter were fixed to the x axis this would spread the same way as a
        // horizontal one, and a spray would visibly comb itself whenever the stroke turned.
        val stamps = StampPlanner(brush, seed = 4).plan(line(Vec2(100f, 0f), Vec2(100f, 200f)))
        val spread = stamps.count { abs(it.position.x - 100f) > 1f }
        (spread > stamps.size / 2) shouldBe true
    }

    @Test
    fun `count lays down several dabs per step`() {
        val one = StampPlanner(preset(BrushPreset(count = 1))).plan(line(Vec2(0f, 0f), Vec2(100f, 0f)))
        val five = StampPlanner(preset(BrushPreset(count = 5))).plan(line(Vec2(0f, 0f), Vec2(100f, 0f)))
        five.size shouldBe one.size * 5
    }

    // ---- flow and colour ---------------------------------------------------------------------

    @Test
    fun `flow is per dab, not per stroke`() {
        val stamps = StampPlanner(preset(BrushPreset(flow = 0.1f))).plan(line(Vec2(0f, 0f), Vec2(100f, 0f)))
        // The distinction most implementations miss: flow is how much ink each dab carries, and
        // dabs accumulate. Opacity is a ceiling on the finished stroke.
        stamps.forEach { it.flow shouldBe 0.1f.plusOrMinus(0.001f) }
    }

    @Test
    fun `colour jitter picks between the two colours`() {
        val brush = preset(
            BrushPreset(
                color = Color.BLACK,
                secondaryColor = Color.WHITE,
                colorJitter = 0.5f,
                spacing = 0.1f,
            ),
        )
        val stamps = StampPlanner(brush, seed = 11).plan(line(Vec2(0f, 0f), Vec2(400f, 0f), samples = 40))
        val distinct = stamps.map { it.color }.distinct()
        (distinct.size >= 2) shouldBe true
    }

    @Test
    fun `hue jitter stays inside the colour range`() {
        val brush = preset(BrushPreset(color = Color(0.5f, 0.2f, 0.8f), hueJitter = 1f, spacing = 0.1f))
        val stamps = StampPlanner(brush, seed = 5).plan(line(Vec2(0f, 0f), Vec2(200f, 0f)))
        stamps.forEach {
            (it.color.r in 0f..1f && it.color.g in 0f..1f && it.color.b in 0f..1f) shouldBe true
        }
    }

    // ---- taper -------------------------------------------------------------------------------

    @Test
    fun `taper thins both ends and leaves the middle alone`() {
        val brush = preset(BrushPreset(size = 40f, taperStart = 0.2f, taperEnd = 0.2f, spacing = 0.1f))
        val stamps = StampPlanner(brush).plan(line(Vec2(0f, 0f), Vec2(400f, 0f), samples = 80), totalLength = 400f)

        (stamps.first().size < stamps[stamps.size / 2].size) shouldBe true
        (stamps.last().size < stamps[stamps.size / 2].size) shouldBe true
        stamps[stamps.size / 2].size shouldBe 40f.plusOrMinus(1f)
    }

    @Test
    fun `taper does nothing while the stroke is still being drawn`() {
        val brush = preset(BrushPreset(size = 40f, taperStart = 0.3f))
        // The total length is not knowable until the finger lifts, and guessing it makes a stroke
        // visibly change thickness at the moment it ends.
        val stamps = StampPlanner(brush).plan(line(Vec2(0f, 0f), Vec2(200f, 0f)))
        stamps.first().size shouldBe 40f.plusOrMinus(0.5f)
    }

    // ---- stabilisation -----------------------------------------------------------------------

    @Test
    fun `smoothing pulls the path behind the finger`() {
        val shaky = listOf(
            StrokePoint(Vec2(0f, 0f)),
            StrokePoint(Vec2(20f, 12f)),
            StrokePoint(Vec2(40f, -10f)),
            StrokePoint(Vec2(60f, 14f)),
            StrokePoint(Vec2(80f, -8f)),
            StrokePoint(Vec2(100f, 10f)),
        )
        val raw = StampPlanner(BrushPreset(smoothing = 0f)).plan(shaky)
        val smooth = StampPlanner(BrushPreset(smoothing = 0.8f)).plan(shaky)

        // A finger on glass is far shakier than a pen on paper. Measured as total vertical travel,
        // because that is the wobble the user is complaining about.
        val rawWobble = raw.zipWithNext { a, b -> abs(b.position.y - a.position.y) }.sum()
        val smoothWobble = smooth.zipWithNext { a, b -> abs(b.position.y - a.position.y) }.sum()
        (smoothWobble < rawWobble) shouldBe true
    }

    @Test
    fun `a stroke still ends where the finger lifted`() {
        val planner = StampPlanner(BrushPreset(smoothing = 0.9f))
        planner.plan(line(Vec2(0f, 0f), Vec2(200f, 0f)))
        val last = planner.finish(StrokePoint(Vec2(200f, 0f)))
        // Smoothing implemented purely as a filter stops every stroke short by the smoothing
        // distance, which is the first thing anyone notices about it.
        (last.isEmpty() || abs(last.last().position.x - 200f) < 20f) shouldBe true
    }

    @Test
    fun `resetting starts a new stroke cleanly`() {
        val planner = StampPlanner(preset())
        val first = planner.plan(line(Vec2(0f, 0f), Vec2(100f, 0f)))
        planner.reset()
        val second = planner.plan(line(Vec2(0f, 0f), Vec2(100f, 0f)))
        // Without the reset the second stroke would begin wherever the first ended, and its dabs
        // would be spaced from the old stroke's leftover distance.
        second.first().position shouldBe first.first().position
    }

    // ---- presets -----------------------------------------------------------------------------

    @Test
    fun `every shipped preset produces dabs`() {
        for (brush in BrushPreset.ALL) {
            val stamps = StampPlanner(brush, seed = 1).plan(line(Vec2(0f, 0f), Vec2(300f, 120f), samples = 40))
            withClue(brush.name) { stamps.isNotEmpty() shouldBe true }
        }
    }

    @Test
    fun `the airbrush carries much less ink per dab than the hard brush`() {
        // Compared at the same diameter, because spacing is a fraction of it: what makes an
        // airbrush an airbrush is many light dabs close together that build up where they overlap,
        // not that it happens to ship at a larger default size.
        val stroke = line(Vec2(0f, 0f), Vec2(200f, 0f))
        val air = StampPlanner(BrushPreset.AIRBRUSH.copy(size = 40f)).plan(stroke)
        val hard = StampPlanner(BrushPreset.HARD.copy(size = 40f)).plan(stroke)

        (air.first().flow < hard.first().flow / 4f) shouldBe true
        (air.size > hard.size) shouldBe true
    }

    private inline fun withClue(clue: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("$clue: ${e.message}", e)
        }
    }
}
