package ir.pixellab.core.canvas

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

class SnappingTest {

    private val canvas = Vec2(1000f, 1000f)
    private val viewport = Viewport(zoom = 1f, screenSize = Vec2(1080f, 2400f))

    private fun snap(
        moving: Rect,
        others: List<Rect> = emptyList(),
        v: Viewport = viewport,
        config: SnapConfig = SnapConfig(),
    ) = SnapEngine.snap(moving, canvas, others, v, config)

    @Test
    fun `whichever of a layer's three anchors is nearest is the one that snaps`() {
        // Left edge at 495, centre at 600, right edge at 705. Only the left edge is near the canvas
        // centre line, so that is what catches — snapping the centre regardless would jump the
        // layer 100 units the user never asked for.
        val result = snap(Rect(495f, 100f, 705f, 300f))
        result.offset.x shouldBe (5f plusOrMinus 0.001f)
        result.guides.single { it.axis == Axis.VERTICAL }.kind shouldBe SnapGuide.Kind.CANVAS_CENTRE
    }

    @Test
    fun `the canvas centre catches a layer whose centre is close to it`() {
        // Centre at 497, edges at 392 and 602: this time the centre is the nearest anchor.
        val result = snap(Rect(392f, 100f, 602f, 300f))
        result.offset.x shouldBe (3f plusOrMinus 0.001f)
        result.guides.single { it.axis == Axis.VERTICAL }.kind shouldBe SnapGuide.Kind.CANVAS_CENTRE
        result.snapped shouldBe true
    }

    @Test
    fun `a layer far from everything is left alone`() {
        val result = snap(Rect(313f, 217f, 400f, 300f))
        result shouldBe SnapResult.NONE
        result.snapped shouldBe false
    }

    @Test
    fun `the canvas edge catches a layer near it`() {
        val result = snap(Rect(4f, 400f, 100f, 500f))
        result.offset.x shouldBe (-4f plusOrMinus 0.001f)
        result.guides.single { it.axis == Axis.VERTICAL }.kind shouldBe SnapGuide.Kind.CANVAS_EDGE
    }

    @Test
    fun `a layer aligns to another layer's edge`() {
        val other = Rect(300f, 600f, 460f, 700f)
        val result = snap(Rect(303f, 100f, 400f, 200f), others = listOf(other))
        result.offset.x shouldBe (-3f plusOrMinus 0.001f)
        val guide = result.guides.single { it.axis == Axis.VERTICAL }
        guide.kind shouldBe SnapGuide.Kind.LAYER_EDGE
        // The guide is drawn long enough to span both layers, which is what shows the connection.
        guide.from shouldBe (100f plusOrMinus 0.001f)
        guide.to shouldBe (700f plusOrMinus 0.001f)
    }

    @Test
    fun `both axes snap independently in one move`() {
        val other = Rect(300f, 600f, 460f, 700f)
        val result = snap(Rect(303f, 603f, 400f, 660f), others = listOf(other))
        result.offset.x shouldBe (-3f plusOrMinus 0.001f)
        result.offset.y shouldBe (-3f plusOrMinus 0.001f)
        result.guides.size shouldBe 2
    }

    @Test
    fun `only the nearest guide on an axis wins`() {
        // Two neighbours' left edges are both within tolerance of the moving layer's left edge.
        // Applying both would pull it to -3, which is neither of them.
        val others = listOf(Rect(295f, 0f, 335f, 50f), Rect(302f, 0f, 342f, 50f))
        val result = snap(Rect(300f, 500f, 400f, 600f), others = others)
        result.guides.count { it.axis == Axis.VERTICAL } shouldBe 1
        result.offset.x shouldBe (2f plusOrMinus 0.001f)
    }

    @Test
    fun `the tolerance is a screen distance so it feels the same at every zoom`() {
        // Six canvas units from the left edge of the canvas, and clear of every other guide.
        val moving = Rect(6f, 401f, 106f, 480f)
        // Zoomed in 4x those six units are 24 screen pixels away — out of reach.
        snap(moving, v = viewport.copy(zoom = 4f)).snapped shouldBe false
        // Zoomed out they are well inside the same 8 screen pixels.
        snap(moving, v = viewport.copy(zoom = 0.5f)).snapped shouldBe true
    }

    @Test
    fun `equal spacing continues a run`() {
        // Two layers 50 apart; the third wants to keep the rhythm. Laying a row out by eye is one
        // of the things that makes a mobile editor feel amateur.
        val others = listOf(Rect(100f, 0f, 200f, 100f), Rect(250f, 0f, 350f, 100f))
        val result = snap(Rect(403f, 0f, 460f, 100f), others = others)
        result.offset.x shouldBe (-3f plusOrMinus 0.001f)
        result.guides.single { it.axis == Axis.VERTICAL }.kind shouldBe SnapGuide.Kind.SPACING
    }

    @Test
    fun `spacing also continues the run backwards`() {
        val others = listOf(Rect(300f, 0f, 400f, 100f), Rect(450f, 0f, 550f, 100f))
        // The slot before the first layer is at 150..250.
        val result = snap(Rect(152f, 0f, 252f, 100f), others = others)
        result.offset.x shouldBe (-2f plusOrMinus 0.001f)
    }

    @Test
    fun `each source of guides can be switched off`() {
        val moving = Rect(4f, 400f, 100f, 500f)
        snap(moving, config = SnapConfig(snapToCanvas = false)).snapped shouldBe false

        val other = Rect(300f, 600f, 500f, 700f)
        snap(Rect(303f, 100f, 400f, 200f), others = listOf(other), config = SnapConfig(snapToLayers = false))
            .snapped shouldBe false
    }

    @Test
    fun `a tie between two guides resolves the same way every time`() {
        // The run continues at 400, which is also where the moving layer's right edge would meet the
        // canvas centre. Without an explicit ranking the winner would depend on the order candidates
        // happen to be generated in, and would change when unrelated code moved.
        val others = listOf(Rect(100f, 0f, 200f, 100f), Rect(250f, 0f, 350f, 100f))
        val result = snap(Rect(403f, 0f, 503f, 100f), others = others)
        result.offset.x shouldBe (-3f plusOrMinus 0.001f)
        result.guides.single { it.axis == Axis.VERTICAL }.kind shouldBe SnapGuide.Kind.CANVAS_CENTRE
    }

    @Test
    fun `spacing needs at least two neighbours to infer a rhythm`() {
        val result = snap(Rect(403f, 0f, 503f, 100f), others = listOf(Rect(100f, 0f, 200f, 100f)))
        result.guides.none { it.kind == SnapGuide.Kind.SPACING } shouldBe true
    }
}
