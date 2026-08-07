package ir.pixellab.core.vector

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Contour
import ir.pixellab.core.model.PathNode
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/**
 * Path geometry and editing.
 *
 * The pen tool is the one place where being *almost* right is worse than being absent: a curve that
 * shifts when a point is added, or a handle that snaps when it is touched, damages work the user has
 * already done. Every rule below exists because its absence is immediately noticeable.
 */
class VectorTest {

    private fun node(x: Float, y: Float, inX: Float = x, inY: Float = y, outX: Float = x, outY: Float = y) =
        PathNode(Vec2(x, y), Vec2(inX, inY), Vec2(outX, outY))

    /** A quarter circle of radius 100, with the handle length every drawing tool uses. */
    private fun quarter(): Contour {
        val k = 100f * 0.5523f
        return Contour(
            listOf(
                node(0f, 100f, outX = k, outY = 100f),
                node(100f, 0f, inX = 100f, inY = k),
            ),
            closed = false,
        )
    }

    private fun square(x: Float, y: Float, size: Float) = ShapeGeometry.Path(
        listOf(
            Contour(
                listOf(node(x, y), node(x + size, y), node(x + size, y + size), node(x, y + size)),
                closed = true,
            ),
        ),
    )

    // ---- node types ---------------------------------------------------------------------------

    @Test
    fun `a node with no handles is a corner`() {
        PathMath.typeOf(node(10f, 10f)) shouldBe NodeType.CORNER
    }

    @Test
    fun `collinear handles of equal length are symmetric`() {
        PathMath.typeOf(node(10f, 10f, inX = 0f, inY = 10f, outX = 20f, outY = 10f)) shouldBe NodeType.SYMMETRIC
    }

    @Test
    fun `collinear handles of different lengths are smooth but not symmetric`() {
        // The distinction matters: dragging one handle of a smooth node must keep the other's
        // length, and treating the two the same makes every smooth node snap to symmetric the first
        // time it is touched.
        PathMath.typeOf(node(10f, 10f, inX = 0f, inY = 10f, outX = 40f, outY = 10f)) shouldBe NodeType.SMOOTH
    }

    @Test
    fun `handles at an angle make a corner`() {
        PathMath.typeOf(node(10f, 10f, inX = 0f, inY = 10f, outX = 10f, outY = 40f)) shouldBe NodeType.CORNER
    }

    @Test
    fun `the type is derived rather than stored, so it cannot disagree with the geometry`() {
        val smoothed = PathMath.retype(node(10f, 10f, inX = 0f, inY = 4f, outX = 20f, outY = 10f), NodeType.SYMMETRIC)
        PathMath.typeOf(smoothed) shouldBe NodeType.SYMMETRIC
    }

    // ---- evaluation ---------------------------------------------------------------------------

    @Test
    fun `a curve passes through its endpoints`() {
        val (from, to) = quarter().nodes
        PathMath.evaluate(from, to, 0f) shouldBe Vec2(0f, 100f)
        PathMath.evaluate(from, to, 1f) shouldBe Vec2(100f, 0f)
    }

    @Test
    fun `the standard handle length really does draw a circle`() {
        val (from, to) = quarter().nodes
        val middle = PathMath.evaluate(from, to, 0.5f)
        // 0.5523 is the constant every drawing tool uses because it puts the midpoint on the arc to
        // within a thousandth of the radius. If the evaluation were wrong this would be visibly off.
        hypot(middle.x, middle.y) shouldBe 100f.plusOrMinus(0.2f)
    }

    @Test
    fun `the tangent points along the curve`() {
        val (from, to) = quarter().nodes
        val start = PathMath.tangent(from, to, 0f)
        // The quarter starts heading straight to the right.
        start.x shouldBe 1f.plusOrMinus(0.01f)
        start.y shouldBe 0f.plusOrMinus(0.01f)
    }

    @Test
    fun `a cusp yields a finite tangent rather than a collapse`() {
        val flat = node(0f, 0f)
        // A stroke outline divides by this; a zero tangent would give a stroke of no width at all.
        val tangent = PathMath.tangent(flat, flat, 0.5f)
        hypot(tangent.x, tangent.y) shouldBe 1f.plusOrMinus(0.01f)
    }

    // ---- flattening ---------------------------------------------------------------------------

    @Test
    fun `flattening adapts to the curve rather than using a fixed count`() {
        val straight = Contour(listOf(node(0f, 0f), node(200f, 0f)), closed = false)
        val curly = quarter()
        // A fixed subdivision either facets the curls or wastes hundreds of points on the
        // straights, and a real path has both in the same contour.
        (PathMath.flatten(curly).size > PathMath.flatten(straight).size) shouldBe true
    }

    @Test
    fun `bounds follow the curve, not the handles`() {
        val box = PathMath.bounds(ShapeGeometry.Path(listOf(quarter())))!!
        // A control point sits well outside the curve it shapes; taking the hull of the handles
        // makes every selection box on a curved shape too big.
        (box.right <= 100.5f) shouldBe true
        (box.top >= -0.5f) shouldBe true
    }

    @Test
    fun `length measures the arc`() {
        // A quarter of a circle of radius 100 is about 157.
        PathMath.length(quarter()) shouldBe 157f.plusOrMinus(1f)
    }

    @Test
    fun `a closed path knows what is inside it`() {
        val shape = square(0f, 0f, 100f)
        PathMath.contains(shape, Vec2(50f, 50f)) shouldBe true
        PathMath.contains(shape, Vec2(150f, 50f)) shouldBe false
    }

    @Test
    fun `a hole reads as outside`() {
        val ring = ShapeGeometry.Path(square(0f, 0f, 100f).contours + square(30f, 30f, 40f).contours)
        // Even-odd, so a counter inside a letter is a hole rather than a second fill.
        PathMath.contains(ring, Vec2(50f, 50f)) shouldBe false
        PathMath.contains(ring, Vec2(10f, 50f)) shouldBe true
    }

    // ---- splitting ----------------------------------------------------------------------------

    @Test
    fun `splitting a curve does not change it`() {
        val (from, to) = quarter().nodes
        val (start, middle, end) = PathMath.split(from, to, 0.5f)

        // De Casteljau gives the *same* curve as two, which is the whole point of splitting rather
        // than resampling — any approximation alters the shape the moment a point is added to it.
        for (i in 0..10) {
            val t = i / 10f
            val original = PathMath.evaluate(from, to, t * 0.5f)
            val half = PathMath.evaluate(start, middle, t)
            hypot(original.x - half.x, original.y - half.y) shouldBe 0f.plusOrMinus(0.05f)
        }
        end.point shouldBe to.point
    }

    @Test
    fun `inserting a node keeps the shape`() {
        val path = ShapeGeometry.Path(listOf(quarter()))
        val before = PathMath.length(path.contours[0])
        val after = PathEditor.insert(path, 0, 0, 0.5f)

        after.contours[0].nodes.size shouldBe 3
        PathMath.length(after.contours[0]) shouldBe before.plusOrMinus(0.5f)
    }

    // ---- editing ------------------------------------------------------------------------------

    @Test
    fun `moving a point takes its handles with it`() {
        val path = ShapeGeometry.Path(listOf(quarter()))
        val moved = PathEditor.move(path, NodeRef(0, 0), Handle.POINT, Vec2(10f, 110f))
        val node = moved.contours[0].nodes[0]

        // Leaving the handles behind is the single most jarring thing a node editor can do: the
        // curve springs away from the finger.
        node.point shouldBe Vec2(10f, 110f)
        node.controlOut.x shouldBe (100f * 0.5523f + 10f).plusOrMinus(0.01f)
        node.controlOut.y shouldBe 110f.plusOrMinus(0.01f)
    }

    @Test
    fun `dragging one handle of a smooth node moves the other`() {
        val path = ShapeGeometry.Path(
            listOf(Contour(listOf(node(50f, 50f, inX = 30f, inY = 50f, outX = 70f, outY = 50f)), closed = false)),
        )
        val moved = PathEditor.move(path, NodeRef(0, 0), Handle.CONTROL_OUT, Vec2(50f, 30f))
        val node = moved.contours[0].nodes[0]
        // Opposite side, so the curve stays smooth — which is what the word means.
        node.controlIn.x shouldBe 50f.plusOrMinus(0.5f)
        node.controlIn.y shouldBe 70f.plusOrMinus(0.5f)
    }

    @Test
    fun `dragging a handle of a corner node leaves the other alone`() {
        val corner = node(50f, 50f, inX = 30f, inY = 50f, outX = 50f, outY = 80f)
        val path = ShapeGeometry.Path(listOf(Contour(listOf(corner), closed = false)))
        val moved = PathEditor.move(path, NodeRef(0, 0), Handle.CONTROL_OUT, Vec2(80f, 80f))
        moved.contours[0].nodes[0].controlIn shouldBe Vec2(30f, 50f)
    }

    @Test
    fun `a smooth node keeps the other handle's length`() {
        val path = ShapeGeometry.Path(
            listOf(Contour(listOf(node(50f, 50f, inX = 10f, inY = 50f, outX = 60f, outY = 50f)), closed = false)),
        )
        val moved = PathEditor.move(path, NodeRef(0, 0), Handle.CONTROL_OUT, Vec2(50f, 40f))
        val node = moved.contours[0].nodes[0]
        // Forty units, as it was. Snapping it to the dragged handle's length is what silently turns
        // every smooth node into a symmetric one.
        hypot(node.controlIn.x - 50f, node.controlIn.y - 50f) shouldBe 40f.plusOrMinus(0.5f)
    }

    @Test
    fun `closing welds an end that landed on the start`() {
        val path = ShapeGeometry.Path(
            listOf(Contour(listOf(node(0f, 0f), node(50f, 0f), node(1f, 1f)), closed = false)),
        )
        val closed = PathEditor.close(path, 0)
        // Two nodes in the same place give a zero-length segment, which every stroker turns into a
        // visible blob at the join.
        closed.contours[0].nodes.size shouldBe 2
        closed.contours[0].closed shouldBe true
    }

    @Test
    fun `closing a path whose end is far away just closes it`() {
        val path = ShapeGeometry.Path(
            listOf(Contour(listOf(node(0f, 0f), node(50f, 0f), node(50f, 50f)), closed = false)),
        )
        PathEditor.close(path, 0).contours[0].nodes.size shouldBe 3
    }

    @Test
    fun `removing a node keeps the neighbours' handles`() {
        val path = ShapeGeometry.Path(
            listOf(
                Contour(
                    listOf(
                        node(0f, 0f, outX = 20f, outY = 0f),
                        node(50f, 50f),
                        node(100f, 0f, inX = 80f, inY = 0f),
                    ),
                    closed = false,
                ),
            ),
        )
        val removed = PathEditor.remove(path, NodeRef(0, 1))
        // Straightening the neighbours is what makes deleting one point flatten a whole section,
        // which is never what removing a point was meant to do.
        removed.contours[0].nodes[0].controlOut shouldBe Vec2(20f, 0f)
        removed.contours[0].nodes[1].controlIn shouldBe Vec2(80f, 0f)
    }

    @Test
    fun `removing the last node removes the contour`() {
        PathEditor.remove(square(0f, 0f, 10f), NodeRef(0, 0)).contours.size shouldBe 1
        val line = ShapeGeometry.Path(listOf(Contour(listOf(node(0f, 0f), node(10f, 0f)), closed = false)))
        PathEditor.remove(line, NodeRef(0, 0)).contours.size shouldBe 0
    }

    @Test
    fun `splitting a closed contour opens it without losing a segment`() {
        val opened = PathEditor.split(square(0f, 0f, 100f), NodeRef(0, 2))
        opened.contours[0].closed shouldBe false
        // Five nodes for four corners: the cut point appears at both ends, or the ring would come
        // back a segment short.
        opened.contours[0].nodes.size shouldBe 5
    }

    @Test
    fun `splitting an open contour gives two`() {
        val line = ShapeGeometry.Path(
            listOf(Contour(listOf(node(0f, 0f), node(50f, 0f), node(100f, 0f)), closed = false)),
        )
        PathEditor.split(line, NodeRef(0, 1)).contours.size shouldBe 2
    }

    @Test
    fun `joining welds two ends that meet`() {
        val a = Contour(listOf(node(0f, 0f), node(50f, 0f)), closed = false)
        val b = Contour(listOf(node(51f, 0f), node(100f, 0f)), closed = false)
        val joined = PathEditor.join(ShapeGeometry.Path(listOf(a, b)), 0, 1)
        joined.contours.size shouldBe 1
        joined.contours[0].nodes.size shouldBe 3
    }

    // ---- hit testing --------------------------------------------------------------------------

    @Test
    fun `a touch on a node finds it`() {
        val hit = PathEditor.hitTest(square(0f, 0f, 100f), Vec2(2f, 2f), radius = 10f)
        hit shouldBe Hit.Node(NodeRef(0, 0), Handle.POINT)
    }

    @Test
    fun `a touch on the curve offers a place to insert`() {
        val hit = PathEditor.hitTest(square(0f, 0f, 100f), Vec2(50f, 1f), radius = 6f)
        (hit is Hit.Segment) shouldBe true
    }

    @Test
    fun `a handle wins over the curve it sits on`() {
        val path = ShapeGeometry.Path(
            listOf(
                Contour(
                    listOf(node(0f, 0f, outX = 40f, outY = 0f), node(100f, 0f, inX = 60f, inY = 0f)),
                    closed = false,
                ),
            ),
        )
        // The handle lies exactly on the straight curve it shapes. Testing the segment first would
        // make it unreachable.
        val hit = PathEditor.hitTest(path, Vec2(40f, 0f), radius = 8f, showHandlesFor = NodeRef(0, 0))
        hit shouldBe Hit.Node(NodeRef(0, 0), Handle.CONTROL_OUT)
    }

    @Test
    fun `a touch on nothing finds nothing`() {
        PathEditor.hitTest(square(0f, 0f, 100f), Vec2(500f, 500f), radius = 10f) shouldBe null
    }

    // ---- variable width -----------------------------------------------------------------------

    @Test
    fun `a uniform profile is the same width all the way along`() {
        WidthProfile.UNIFORM.widthAt(0f) shouldBe (1f to 1f)
        WidthProfile.UNIFORM.widthAt(0.5f) shouldBe (1f to 1f)
        WidthProfile.UNIFORM.widthAt(1f) shouldBe (1f to 1f)
    }

    @Test
    fun `a tapered profile is thin at both ends and full in the middle`() {
        val (start, _) = WidthProfile.TAPERED.widthAt(0f)
        val (middle, _) = WidthProfile.TAPERED.widthAt(0.5f)
        val (end, _) = WidthProfile.TAPERED.widthAt(1f)
        (start < 0.1f) shouldBe true
        middle shouldBe 1f.plusOrMinus(0.01f)
        (end < 0.1f) shouldBe true
    }

    @Test
    fun `a calligraphic profile is asymmetric`() {
        val (left, right) = WidthProfile.CALLIGRAPHIC.widthAt(0.5f)
        // What makes a stroke read as having been drawn with a nib rather than merely thickened.
        (left > right * 3f) shouldBe true
    }

    @Test
    fun `an outlined stroke is a closed shape`() {
        val outlined = StrokeOutliner.outline(quarter(), width = 10f)
        outlined.contours.size shouldBe 1
        outlined.contours[0].closed shouldBe true
    }

    @Test
    fun `an outlined stroke is about as wide as it was told`() {
        val line = Contour(listOf(node(0f, 50f), node(100f, 50f)), closed = false)
        val outlined = StrokeOutliner.outline(line, width = 20f)
        val box = PathMath.bounds(outlined)!!
        box.height shouldBe 20f.plusOrMinus(0.5f)
    }

    @Test
    fun `a tapered outline is narrower at its ends than in its middle`() {
        val line = Contour(listOf(node(0f, 50f), node(200f, 50f)), closed = false)
        val outlined = StrokeOutliner.outline(line, width = 40f, profile = WidthProfile.TAPERED)
        val points = outlined.contours[0].nodes.map { it.point }

        val spreadAt = { x: Float ->
            val near = points.filter { kotlin.math.abs(it.x - x) < 8f }
            if (near.isEmpty()) 0f else near.maxOf { it.y } - near.minOf { it.y }
        }
        (spreadAt(5f) < spreadAt(100f)) shouldBe true
    }

    @Test
    fun `a closed stroke outlines to two rings, not one`() {
        val outlined = StrokeOutliner.outline(square(0f, 0f, 100f).contours[0], width = 10f)
        // Joining the outside and the hole into one loop would fill the middle in.
        outlined.contours.size shouldBe 2
    }

    // ---- svg ----------------------------------------------------------------------------------

    @Test
    fun `a path survives a round trip through svg`() {
        val original = ShapeGeometry.Path(listOf(quarter()))
        val back = SvgPath.read(SvgPath.write(original))

        back.contours.size shouldBe 1
        back.contours[0].nodes.size shouldBe 2
        back.contours[0].nodes[1].point.x shouldBe 100f.plusOrMinus(0.01f)
    }

    @Test
    fun `straight segments are written as lines rather than curves`() {
        val data = SvgPath.write(square(0f, 0f, 100f))
        // Writing every segment as a curve would be correct and would double the file, and a
        // straight edge written as a curve loses its straightness the first time it is rounded.
        ('L' in data) shouldBe true
        ('C' in data) shouldBe false
    }

    @Test
    fun `a closed path writes its close command`() {
        ('Z' in SvgPath.write(square(0f, 0f, 10f))) shouldBe true
        ('Z' in SvgPath.write(ShapeGeometry.Path(listOf(quarter())))) shouldBe false
    }

    @Test
    fun `relative commands are read`() {
        val absolute = SvgPath.read("M 10 10 L 60 10 L 60 60 Z")
        val relative = SvgPath.read("m 10 10 l 50 0 l 0 50 z")
        relative.contours[0].nodes.map { it.point } shouldBe absolute.contours[0].nodes.map { it.point }
    }

    @Test
    fun `horizontal and vertical shorthands are read`() {
        val shape = SvgPath.read("M0 0 H100 V100 H0 Z")
        // Almost every optimised icon uses these; a reader without them silently mangles the shape.
        shape.contours[0].nodes.map { it.point } shouldBe
            listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 100f), Vec2(0f, 100f))
    }

    @Test
    fun `the smooth curve shorthand reflects the previous control point`() {
        val explicit = SvgPath.read("M0 0 C20 0 40 20 40 40 C40 60 60 80 80 80")
        val shorthand = SvgPath.read("M0 0 C20 0 40 20 40 40 S60 80 80 80")
        // The reflection is the whole meaning of `S`; getting it wrong gives a kink at every join.
        shorthand.contours[0].nodes.last().point shouldBe explicit.contours[0].nodes.last().point
        shorthand.contours[0].nodes[1].controlOut.x shouldBe explicit.contours[0].nodes[1].controlOut.x.plusOrMinus(0.01f)
    }

    @Test
    fun `a quadratic is converted to a cubic that draws the same curve`() {
        val quadratic = SvgPath.read("M0 0 Q50 100 100 0")
        val (from, to) = quadratic.contours[0].nodes
        // The controls sit two thirds of the way to the single quadratic control, which is the
        // exact conversion rather than an approximation.
        from.controlOut.y shouldBe 66.67f.plusOrMinus(0.1f)
        PathMath.evaluate(from, to, 0.5f).y shouldBe 50f.plusOrMinus(0.5f)
    }

    @Test
    fun `an elliptical arc becomes curves`() {
        val arc = SvgPath.read("M0 0 A50 50 0 0 1 100 0")
        val points = PathMath.flatten(arc.contours[0])
        // A half circle of radius 50 rises to 50 at its middle; a converter that dropped the arc
        // would leave a straight line and no rise at all.
        (points.maxOf { kotlin.math.abs(it.y) } > 40f) shouldBe true
    }

    @Test
    fun `an arc with degenerate radii is a straight line`() {
        val line = SvgPath.read("M0 0 A0 0 0 0 1 100 0")
        // The specification says so explicitly, and real files use it to draw a line with an arc.
        line.contours[0].nodes.last().point shouldBe Vec2(100f, 0f)
    }

    @Test
    fun `an arc whose radii cannot reach is scaled up rather than producing nothing`() {
        val arc = SvgPath.read("M0 0 A10 10 0 0 1 100 0")
        // Without the scaling required by the specification this is a square root of a negative
        // number, and the shape vanishes — which is what happens to any file with rounded
        // coordinates.
        arc.contours[0].nodes.last().point.x shouldBe 100f.plusOrMinus(0.5f)
        arc.contours[0].nodes.none { it.point.x.isNaN() } shouldBe true
    }

    @Test
    fun `run-together numbers are separated`() {
        // "1.5.5" is two values, which optimised path data relies on.
        val shape = SvgPath.read("M0 0L1.5.5")
        shape.contours[0].nodes[1].point shouldBe Vec2(1.5f, 0.5f)
    }

    @Test
    fun `extra pairs after a moveto are implicit linetos`() {
        val shape = SvgPath.read("M0 0 10 0 10 10")
        // How a polygon is usually written, and a reader that stops after the first pair loses
        // everything but the first point.
        shape.contours[0].nodes.size shouldBe 3
    }

    @Test
    fun `several subpaths are read as several contours`() {
        SvgPath.read("M0 0 H10 V10 Z M20 20 H30 V30 Z").contours.size shouldBe 2
    }

    @Test
    fun `nonsense yields an empty path rather than an exception`() {
        SvgPath.read("this is not path data").contours.size shouldBe 0
        SvgPath.read("").contours.size shouldBe 0
    }
}
