package ir.pixellab.engine.android

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Contour
import ir.pixellab.core.model.PathNode
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.vector.PathMath
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Illustrator's Pathfinder, against real Skia.
 *
 * The operations are delegated rather than implemented, so what is worth testing is the delegation:
 * that the shapes reach Skia intact, that the results come back as usable paths, and that the
 * conventions match Illustrator's — particularly Minus Front, where getting the order backwards
 * gives a plausible-looking but wrong shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PathfinderTest {

    private fun square(x: Float, y: Float, size: Float) = ShapeGeometry.Path(
        listOf(
            Contour(
                listOf(
                    PathNode(Vec2(x, y)),
                    PathNode(Vec2(x + size, y)),
                    PathNode(Vec2(x + size, y + size)),
                    PathNode(Vec2(x, y + size)),
                ),
                closed = true,
            ),
        ),
    )

    /**
     * Filled area by the even-odd rule, which is how these paths are actually filled.
     *
     * A plain signed shoelace sum is not enough: Skia emits a hole with the *same* winding as the
     * contour containing it and relies on the fill rule to make it a hole, so summing the signed
     * areas would count the overlap twice rather than removing it. Each contour is therefore
     * counted according to how many others enclose it.
     */
    private fun areaOf(path: ShapeGeometry.Path): Float {
        val outlines = PathMath.flatten(path).filter { it.size >= 3 }
        var total = 0f
        for ((index, points) in outlines.withIndex()) {
            var signed = 0f
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                signed += a.x * b.y - b.x * a.y
            }
            val depth = outlines.withIndex().count { (other, _) ->
                other != index && encloses(outlines[other], points.first())
            }
            total += if (depth % 2 == 0) kotlin.math.abs(signed) else -kotlin.math.abs(signed)
        }
        return total / 2f
    }

    private fun encloses(outline: List<Vec2>, point: Vec2): Boolean {
        var inside = false
        var j = outline.size - 1
        for (i in outline.indices) {
            val a = outline[i]
            val b = outline[j]
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    @Test
    fun `uniting two overlapping squares gives their combined area`() {
        val united = Pathfinder.apply(
            listOf(square(0f, 0f, 100f), square(50f, 50f, 100f)),
            PathOperation.UNITE,
        )
        // Two 100-squares overlapping by 50 each way: 10000 + 10000 - 2500.
        areaOf(united) shouldBe 17500f.plusOrMinus(200f)
    }

    @Test
    fun `uniting shapes that do not touch keeps both`() {
        val united = Pathfinder.apply(
            listOf(square(0f, 0f, 50f), square(200f, 200f, 50f)),
            PathOperation.UNITE,
        )
        united.contours.size shouldBe 2
    }

    @Test
    fun `minus front cuts the upper shape out of the lower`() {
        val cut = Pathfinder.apply(
            listOf(square(0f, 0f, 100f), square(50f, 50f, 100f)),
            PathOperation.MINUS_FRONT,
        )
        // Illustrator subtracts everything above from the bottom shape, so the stacking order
        // decides the result — reversing it gives a plausible but wrong shape.
        areaOf(cut) shouldBe 7500f.plusOrMinus(200f)
    }

    @Test
    fun `intersecting keeps only the overlap`() {
        val overlap = Pathfinder.apply(
            listOf(square(0f, 0f, 100f), square(50f, 50f, 100f)),
            PathOperation.INTERSECT,
        )
        areaOf(overlap) shouldBe 2500f.plusOrMinus(100f)
    }

    @Test
    fun `intersecting shapes that miss each other gives nothing`() {
        val overlap = Pathfinder.apply(
            listOf(square(0f, 0f, 50f), square(200f, 200f, 50f)),
            PathOperation.INTERSECT,
        )
        overlap.contours.size shouldBe 0
    }

    @Test
    fun `excluding removes the overlap and keeps the rest`() {
        val excluded = Pathfinder.apply(
            listOf(square(0f, 0f, 100f), square(50f, 50f, 100f)),
            PathOperation.EXCLUDE,
        )
        // 17500 covered, of which 2500 is covered twice and drops out.
        areaOf(excluded) shouldBe 15000f.plusOrMinus(400f)
    }

    @Test
    fun `a single shape passes through untouched`() {
        val one = square(0f, 0f, 100f)
        Pathfinder.apply(listOf(one), PathOperation.UNITE) shouldBe one
    }

    @Test
    fun `no shapes gives an empty path rather than throwing`() {
        Pathfinder.apply(emptyList(), PathOperation.UNITE).contours.size shouldBe 0
    }

    @Test
    fun `a shape combined with itself is unchanged in area`() {
        val doubled = Pathfinder.apply(
            listOf(square(0f, 0f, 100f), square(0f, 0f, 100f)),
            PathOperation.UNITE,
        )
        // The same shape appearing twice in a selection is normal — it happens the moment someone
        // duplicates a layer and forgets — and an operation that mutated its receiver would
        // produce nothing at all here.
        areaOf(doubled) shouldBe 10000f.plusOrMinus(200f)
    }

    @Test
    fun `curves survive the round trip through the platform`() {
        val k = 100f * 0.5523f
        val circleish = ShapeGeometry.Path(
            listOf(
                Contour(
                    listOf(
                        PathNode(Vec2(0f, 100f), controlOut = Vec2(k, 100f)),
                        PathNode(Vec2(100f, 0f), controlIn = Vec2(100f, k), controlOut = Vec2(100f, -k)),
                        PathNode(Vec2(0f, -100f), controlIn = Vec2(k, -100f)),
                    ),
                    closed = true,
                ),
            ),
        )
        val united = Pathfinder.apply(listOf(circleish, square(-200f, -200f, 10f)), PathOperation.UNITE)
        val box = PathMath.bounds(united)!!
        // The curve's own extent, not its handles': a conversion that dropped the control points
        // would give a triangle and a visibly smaller box.
        box.right shouldBe 100f.plusOrMinus(1f)
    }

    @Test
    fun `dividing cuts overlapping shapes into their regions`() {
        val regions = Pathfinder.divide(listOf(square(0f, 0f, 100f), square(50f, 50f, 100f)))
        // Three: the part only in the first, the shared part, and the part only in the second.
        regions.size shouldBe 3
        areaOf(regions.maxByOrNull(::areaOf)!!) shouldBe 7500f.plusOrMinus(300f)
    }

    @Test
    fun `dividing one shape leaves it alone`() {
        Pathfinder.divide(listOf(square(0f, 0f, 100f))).size shouldBe 1
    }

    @Test
    fun `outlining discards the interiors`() {
        val outlined = Pathfinder.outline(listOf(square(0f, 0f, 100f)))
        outlined.contours.all { !it.closed } shouldBe true
    }

    @Test
    fun `offsetting outwards grows the shape`() {
        val grown = Pathfinder.offset(square(0f, 0f, 100f), 10f)
        // Stroking and uniting rather than an analytic offset, which self-intersects at every
        // concave corner.
        (areaOf(grown) > 10000f) shouldBe true
        val box = PathMath.bounds(grown)!!
        box.left shouldBe (-10f).plusOrMinus(1.5f)
    }

    @Test
    fun `offsetting inwards shrinks it`() {
        val shrunk = Pathfinder.offset(square(0f, 0f, 100f), -10f)
        (areaOf(shrunk) < 10000f) shouldBe true
    }

    @Test
    fun `offsetting by nothing is not an operation`() {
        val original = square(0f, 0f, 100f)
        Pathfinder.offset(original, 0f) shouldBe original
    }

    @Test
    fun `a closed contour comes back without a duplicated first node`() {
        val united = Pathfinder.apply(
            listOf(square(0f, 0f, 100f), square(200f, 0f, 100f)),
            PathOperation.UNITE,
        )
        for (contour in united.contours) {
            if (!contour.closed) continue
            val first = contour.nodes.first().point
            val last = contour.nodes.last().point
            // A repeated point gives a zero-length segment, which shows as a blob at the join on
            // any stroked path.
            (kotlin.math.hypot(first.x - last.x, first.y - last.y) > 0.01f) shouldBe true
        }
    }
}
