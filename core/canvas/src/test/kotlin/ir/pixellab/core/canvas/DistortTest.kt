package ir.pixellab.core.canvas

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * Free four-corner distort.
 *
 * The distinction from a resize is the whole subject: a resize keeps the box a parallelogram, and
 * this does not. So every test below either checks that three corners stayed put while one moved,
 * or checks something a parallelogram cannot do.
 */
class DistortTest {

    private val bounds = Rect(0f, 0f, 100f, 50f)

    private fun corners(transform: Transform): List<Vec2> =
        listOf(Handle.TOP_LEFT, Handle.TOP_RIGHT, Handle.BOTTOM_RIGHT, Handle.BOTTOM_LEFT).map {
            val unit = it.unitPosition
            Handles.localToCanvas(
                Vec2(bounds.left + unit.x * bounds.width, bounds.top + unit.y * bounds.height),
                bounds,
                transform,
            )
        }

    private fun near(a: Vec2, b: Vec2, tolerance: Float = 0.01f) =
        abs(a.x - b.x) < tolerance && abs(a.y - b.y) < tolerance

    @Test
    fun `moves the dragged corner and leaves the other three`() {
        val start = Transform()
        val before = corners(start)

        val distorted = Handles.distort(Handle.TOP_LEFT, bounds, start, Vec2(10f, 20f))
        val after = corners(distorted)

        near(after[0], before[0] + Vec2(10f, 20f)) shouldBe true
        near(after[1], before[1]) shouldBe true
        near(after[2], before[2]) shouldBe true
        near(after[3], before[3]) shouldBe true
    }

    @Test
    fun `makes a shape a resize could not`() {
        // A trapezium: the top edge shorter than the bottom. Under any affine transform opposite
        // edges stay parallel and equal, so this is the assertion that says the map became
        // projective rather than merely stretched.
        var t = Transform()
        t = Handles.distort(Handle.TOP_LEFT, bounds, t, Vec2(20f, 0f))
        t = Handles.distort(Handle.TOP_RIGHT, bounds, t, Vec2(-20f, 0f))

        val c = corners(t)
        val topWidth = abs(c[1].x - c[0].x)
        val bottomWidth = abs(c[2].x - c[3].x)

        (topWidth < bottomWidth - 1f) shouldBe true
    }

    @Test
    fun `keeps a rotation the layer already had`() {
        // The failure this guards is silent and hard to attribute: read the corners off the raw
        // bounds instead of through the transform and a layer loses its rotation the instant
        // somebody drags a corner, with the distort control getting none of the blame.
        val rotated = Transform(rotation = 30f)
        val before = corners(rotated)

        val distorted = Handles.distort(Handle.BOTTOM_RIGHT, bounds, rotated, Vec2(5f, 5f))
        val after = corners(distorted)

        near(after[0], before[0]) shouldBe true
        near(after[1], before[1]) shouldBe true
        near(after[3], before[3]) shouldBe true
        near(after[2], before[2] + Vec2(5f, 5f)) shouldBe true
    }

    @Test
    fun `keeps a scale and a translation the layer already had`() {
        val moved = Transform(translation = Vec2(40f, -15f), scale = Vec2(2f, 0.5f))
        val before = corners(moved)

        val after = corners(Handles.distort(Handle.TOP_RIGHT, bounds, moved, Vec2(-8f, 3f)))

        near(after[1], before[1] + Vec2(-8f, 3f)) shouldBe true
        near(after[0], before[0]) shouldBe true
        near(after[2], before[2]) shouldBe true
    }

    @Test
    fun `a second drag builds on the first`() {
        // Two corners moved in sequence, which is what actually happens: nobody distorts a layer
        // with one drag. The second must read the corners as they now are, or it silently undoes
        // the first.
        val once = Handles.distort(Handle.TOP_LEFT, bounds, Transform(), Vec2(10f, 10f))
        val twice = Handles.distort(Handle.BOTTOM_RIGHT, bounds, once, Vec2(-10f, -10f))

        val c = corners(twice)
        near(c[0], Vec2(10f, 10f)) shouldBe true
        near(c[2], Vec2(90f, 40f)) shouldBe true
    }

    @Test
    fun `edges and the body are left alone rather than guessed at`() {
        // An edge handle has no meaning in a four-corner warp — moving one would have to invent
        // which of its two corners followed it, and inventing that is worse than declining.
        val start = Transform()
        for (handle in listOf(Handle.TOP, Handle.LEFT, Handle.BODY, Handle.ROTATE)) {
            Handles.distort(handle, bounds, start, Vec2(10f, 10f)) shouldBe start
        }
    }

    @Test
    fun `a degenerate box is left alone`() {
        val flat = Rect(0f, 0f, 0f, 0f)
        Handles.distort(Handle.TOP_LEFT, flat, Transform(), Vec2(10f, 10f)) shouldBe Transform()
    }

    @Test
    fun `undistort is the way back`() {
        val distorted = Handles.distort(Handle.TOP_LEFT, bounds, Transform(rotation = 20f), Vec2(9f, 9f))

        val reset = Handles.undistort(distorted)

        reset.perspective shouldBe null
        // The affine part survives, so resetting the warp is not a reset of the layer.
        reset.rotation shouldBe 20f
    }

    @Test
    fun `the handles land on the warped corners afterwards`() {
        // `localToCanvas` defers to the projective matrix once a warp is present. If it did not,
        // the drawn handles would sit on the un-warped box and every subsequent grab would miss.
        val distorted = Handles.distort(Handle.TOP_LEFT, bounds, Transform(), Vec2(25f, 15f))

        val topLeft = Handles.localToCanvas(Vec2(bounds.left, bounds.top), bounds, distorted)

        near(topLeft, Vec2(25f, 15f)) shouldBe true
    }
}
