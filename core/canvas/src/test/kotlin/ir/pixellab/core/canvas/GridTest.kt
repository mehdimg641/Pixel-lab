package ir.pixellab.core.canvas

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Guide
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

/**
 * The grid, the rulers, the guides and the safe zones.
 *
 * About twenty-five rows of the 1500-feature audit, none of which existed. The interesting cases
 * are all about *bounds*: a grid fine enough to be useful is dense enough to draw slower than the
 * artwork, and a ruler with a fixed step is unreadable at one zoom and useless at another.
 */
class GridTest {

    // ---- grid ----------------------------------------------------------------------------------

    @Test
    fun `a grid marks every subdivision and every main line`() {
        val grid = GridSpec(spacing = 100f, subdivisions = 4)
        val lines = grid.lines(400f)

        // 0, 25, 50, ... 400 — seventeen lines, of which five are major.
        lines.size shouldBe 17
        lines.count { it.second } shouldBe 5
        lines.first() shouldBe (0f to true)
        lines[1] shouldBe (25f to false)
        lines[4] shouldBe (100f to true)
    }

    @Test
    fun `no subdivisions means every line is a main one`() {
        GridSpec(spacing = 50f, subdivisions = 1).lines(200f).all { it.second } shouldBe true
    }

    @Test
    fun `a grid too fine to draw falls back to its main lines`() {
        // Four-unit steps on a six-thousand-pixel canvas is fifteen hundred lines: slower to draw
        // than the artwork and a solid grey wash on screen.
        val dense = GridSpec(spacing = 16f, subdivisions = 4).lines(6000f)
        dense.all { it.second } shouldBe true
        (dense.size < 400) shouldBe true
    }

    @Test
    fun `a grid too fine even for its main lines draws nothing`() {
        GridSpec(spacing = 1f, subdivisions = 1).lines(100_000f).isEmpty() shouldBe true
    }

    @Test
    fun `snapping goes to the nearest line, including subdivisions`() {
        val grid = GridSpec(spacing = 100f, subdivisions = 4)
        grid.nearest(0f) shouldBe 0f
        grid.nearest(12f) shouldBe 0f
        grid.nearest(14f) shouldBe 25f
        grid.nearest(-30f) shouldBe -25f
    }

    @Test
    fun `a grid needs a positive spacing and at least one division`() {
        runCatching { GridSpec(spacing = 0f) }.isFailure shouldBe true
        runCatching { GridSpec(subdivisions = 0) }.isFailure shouldBe true
    }

    // ---- rulers --------------------------------------------------------------------------------

    @Test
    fun `ruler steps follow the one-two-five sequence`() {
        // The step has to come from the zoom, or the ruler is unreadably dense at one and shows
        // two numbers at the other.
        val steps = listOf(0.05f, 0.2f, 1f, 4f, 20f).map { Ruler.stepFor(it) }
        steps.all { step ->
            val mantissa = generateSequence(step) { if (it >= 10f) it / 10f else null }.last()
            mantissa in listOf(1f, 2f, 5f) || kotlin.math.abs(mantissa - 1f) < 1e-3f
        } shouldBe true
    }

    @Test
    fun `zooming in gives a finer ruler`() {
        // Not merely different: monotonic. A ruler whose step jumped around as the user pinched
        // would be worse than none.
        val zoomedOut = Ruler.stepFor(0.25f)
        val oneToOne = Ruler.stepFor(1f)
        val zoomedIn = Ruler.stepFor(4f)
        (zoomedOut > oneToOne) shouldBe true
        (oneToOne > zoomedIn) shouldBe true
    }

    @Test
    fun `ticks start at the origin and stay inside the canvas`() {
        val ticks = Ruler.ticks(extent = 1000f, step = 250f)
        ticks shouldBe listOf(0f, 250f, 500f, 750f, 1000f)
    }

    @Test
    fun `an impossible ruler produces nothing rather than a million ticks`() {
        Ruler.ticks(extent = 100_000f, step = 1f).isEmpty() shouldBe true
        Ruler.ticks(extent = 0f, step = 10f).isEmpty() shouldBe true
        Ruler.ticks(extent = 100f, step = 0f).isEmpty() shouldBe true
    }

    // ---- safe zones ----------------------------------------------------------------------------

    @Test
    fun `a safe zone scales with the canvas`() {
        val small = SafeZone.STORY.rectFor(Vec2(1080f, 1920f))
        val large = SafeZone.STORY.rectFor(Vec2(2160f, 3840f))
        // Fractions rather than pixels, so one preset covers every resolution the platform accepts.
        (large.left / small.left) shouldBe 2f.plusOrMinus(0.001f)
        (large.width / small.width) shouldBe 2f.plusOrMinus(0.001f)
    }

    @Test
    fun `a story reserves more at the bottom than at the top`() {
        val zone = SafeZone.STORY.rectFor(Vec2(1000f, 1000f))
        val topInset = zone.top
        val bottomInset = 1000f - zone.bottom
        // The reply bar covers more than the profile row does, and a title placed in it is a
        // finished design wasted.
        (bottomInset > topInset) shouldBe true
    }

    @Test
    fun `every safe zone has a distinct name and leaves something usable`() {
        SafeZone.ALL.map { it.name }.distinct().size shouldBe SafeZone.ALL.size
        SafeZone.ALL.all { zone ->
            val rect = zone.rectFor(Vec2(1000f, 1000f))
            rect.width > 500f && rect.height > 500f
        } shouldBe true
    }
}

/**
 * Snapping to what the user placed, and to the grid.
 */
class GuideSnapTest {

    private val viewport = Viewport(offset = Vec2.ZERO, zoom = 1f, screenSize = Vec2(1000f, 1000f))
    private val canvas = Vec2(1000f, 1000f)

    private fun box(left: Float, top: Float) = Rect(left, top, left + 100f, top + 100f)

    @Test
    fun `a layer snaps to a guide the user placed`() {
        val result = SnapEngine.snap(
            moving = box(303f, 500f),
            canvas = canvas,
            others = emptyList(),
            viewport = viewport,
            guides = listOf(Guide(vertical = true, position = 300f)),
        )
        result.offset.x shouldBe -3f
        result.guides.first().kind shouldBe SnapGuide.Kind.GUIDE
    }

    @Test
    fun `a guide beats a layer edge at the same distance`() {
        // Deliberate beats incidental: the guide is where someone put it on purpose, the layer edge
        // is wherever that layer happens to sit.
        val result = SnapEngine.snap(
            moving = box(304f, 500f),
            canvas = canvas,
            others = listOf(Rect(296f, 0f, 396f, 100f)),
            viewport = viewport,
            guides = listOf(Guide(vertical = true, position = 300f)),
        )
        result.guides.first { it.axis == Axis.VERTICAL }.kind shouldBe SnapGuide.Kind.GUIDE
    }

    @Test
    fun `the grid loses every tie`() {
        // A grid line is never more than half a step away, so ranking it higher would let it win
        // against the thing the user was actually aiming for.
        val result = SnapEngine.snap(
            moving = box(302f, 500f),
            canvas = canvas,
            others = emptyList(),
            viewport = viewport,
            guides = listOf(Guide(vertical = true, position = 300f)),
            grid = GridSpec(spacing = 100f, subdivisions = 1),
        )
        result.guides.first { it.axis == Axis.VERTICAL }.kind shouldBe SnapGuide.Kind.GUIDE
    }

    @Test
    fun `the grid still catches when nothing else is near`() {
        // Deliberately clear of the canvas centre line: at 497 the box's right edge would sit three
        // units from it, and the canvas centre outranks the grid — correctly, but it would be
        // measuring the ranking rather than whether the grid catches at all.
        val result = SnapEngine.snap(
            moving = box(347f, 203f),
            canvas = canvas,
            others = emptyList(),
            viewport = viewport,
            grid = GridSpec(spacing = 100f, subdivisions = 1),
        )
        result.offset.x shouldBe 3f
        result.offset.y shouldBe -3f
        result.guides.all { it.kind == SnapGuide.Kind.GRID } shouldBe true
    }

    @Test
    fun `a grid with snapping switched off is only drawn`() {
        val result = SnapEngine.snap(
            moving = box(497f, 500f),
            canvas = canvas,
            others = emptyList(),
            viewport = viewport,
            config = SnapConfig(snapToCanvas = false),
            grid = GridSpec(spacing = 100f, subdivisions = 1, snap = false),
        )
        result.snapped shouldBe false
    }

    @Test
    fun `a horizontal guide never moves a layer sideways`() {
        val result = SnapEngine.snap(
            moving = box(500f, 402f),
            canvas = canvas,
            others = emptyList(),
            viewport = viewport,
            config = SnapConfig(snapToCanvas = false),
            guides = listOf(Guide(vertical = false, position = 400f)),
        )
        result.offset.x shouldBe 0f
        result.offset.y shouldBe -2f
    }

    @Test
    fun `guides can be switched off without switching off everything else`() {
        val result = SnapEngine.snap(
            moving = box(303f, 500f),
            canvas = canvas,
            others = emptyList(),
            viewport = viewport,
            config = SnapConfig(snapToCanvas = false, snapToGuides = false),
            guides = listOf(Guide(vertical = true, position = 300f)),
        )
        result.snapped shouldBe false
    }
}
