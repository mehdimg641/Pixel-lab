package ir.pixellab.core.canvas

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

class HandleTest {

    private val bounds = Rect(0f, 0f, 200f, 100f)
    private val viewport = Viewport(zoom = 1f, screenSize = Vec2(1080f, 2400f))

    private infix fun Vec2.shouldBeNear(other: Vec2) {
        x shouldBe (other.x plusOrMinus 0.05f)
        y shouldBe (other.y plusOrMinus 0.05f)
    }

    /** The fixed corner, which must not move while the opposite one is dragged. */
    private fun corner(handle: Handle, transform: Transform): Vec2 {
        val unit = handle.unitPosition
        return Handles.localToCanvas(
            Vec2(bounds.left + unit.x * bounds.width, bounds.top + unit.y * bounds.height),
            bounds,
            transform,
        )
    }

    @Test
    fun `local and canvas mappings are inverses under scale and rotation`() {
        val transform = Transform(translation = Vec2(30f, -12f), scale = Vec2(1.7f, 0.6f), rotation = 26f)
        val point = Vec2(140f, 80f)
        Handles.canvasToLocal(Handles.localToCanvas(point, bounds, transform), bounds, transform) shouldBeNear point
    }

    @Test
    fun `dragging the right edge holds the left edge still`() {
        val start = Transform()
        val before = corner(Handle.LEFT, start)
        val resized = Handles.resize(Handle.RIGHT, bounds, start, Vec2(50f, 0f))
        resized.scale.x shouldBe (1.25f plusOrMinus 0.001f)
        resized.scale.y shouldBe (1f plusOrMinus 0.001f)
        // Letting the translation stay put makes the layer grow from its centre, so dragging the
        // right edge visibly moves the left one.
        corner(Handle.LEFT, resized) shouldBeNear before
    }

    @Test
    fun `dragging a corner holds the opposite corner still`() {
        val start = Transform(translation = Vec2(40f, 60f))
        val before = corner(Handle.TOP_LEFT, start)
        val resized = Handles.resize(Handle.BOTTOM_RIGHT, bounds, start, Vec2(100f, 50f))
        corner(Handle.TOP_LEFT, resized) shouldBeNear before
        resized.scale shouldBe Vec2(1.5f, 1.5f)
    }

    @Test
    fun `a rotated layer resizes along its own axes`() {
        // At 90 degrees the layer's local x axis points down the screen, so a downward drag has to
        // widen it. Applying the drag in screen space instead would stretch the wrong side.
        val start = Transform(rotation = 90f)
        val resized = Handles.resize(Handle.RIGHT, bounds, start, Vec2(0f, 50f))
        resized.scale.x shouldBe (1.25f plusOrMinus 0.001f)
        resized.scale.y shouldBe (1f plusOrMinus 0.001f)
    }

    @Test
    fun `a rotated corner drag still pins the opposite corner`() {
        val start = Transform(translation = Vec2(15f, 25f), rotation = 33f, scale = Vec2(1.2f, 0.9f))
        val before = corner(Handle.TOP_LEFT, start)
        val resized = Handles.resize(Handle.BOTTOM_RIGHT, bounds, start, Vec2(70f, -20f))
        corner(Handle.TOP_LEFT, resized) shouldBeNear before
    }

    @Test
    fun `locking the aspect keeps the proportions on a corner`() {
        val resized = Handles.resize(Handle.BOTTOM_RIGHT, bounds, Transform(), Vec2(100f, 0f), lockAspect = true)
        resized.scale.x shouldBe (resized.scale.y plusOrMinus 0.001f)
        resized.scale.x shouldBe (1.5f plusOrMinus 0.001f)
    }

    @Test
    fun `locking the aspect on an edge is ignored because one axis has no drag`() {
        // An edge handle only carries one axis of intent; forcing the other to match would make the
        // box jump the moment the handle is touched.
        val resized = Handles.resize(Handle.RIGHT, bounds, Transform(), Vec2(50f, 0f), lockAspect = true)
        resized.scale.y shouldBe (1f plusOrMinus 0.001f)
    }

    @Test
    fun `resizing from the centre grows both sides and holds the anchor`() {
        val start = Transform(translation = Vec2(10f, 10f))
        val before = corner(Handle.BODY, start)
        val resized = Handles.resize(Handle.RIGHT, bounds, start, Vec2(50f, 0f), fromCentre = true)
        resized.scale.x shouldBe (1.5f plusOrMinus 0.001f)
        corner(Handle.BODY, resized) shouldBeNear before
    }

    @Test
    fun `a layer cannot be collapsed to nothing`() {
        val squashed = Handles.resize(Handle.RIGHT, bounds, Transform(), Vec2(-200f, 0f))
        // Below this it has no handle left to grab and the layer is unrecoverable.
        (kotlin.math.abs(squashed.scale.x) * bounds.width >= HandleConfig().minimumSize) shouldBe true
    }

    @Test
    fun `dragging a handle past the far side flips the layer`() {
        val flipped = Handles.resize(Handle.RIGHT, bounds, Transform(), Vec2(-400f, 0f))
        (flipped.scale.x < 0f) shouldBe true
    }

    @Test
    fun `the body handle moves the layer and nothing else`() {
        val moved = Handles.resize(Handle.BODY, bounds, Transform(rotation = 20f), Vec2(30f, -15f))
        moved.translation shouldBe Vec2(30f, -15f)
        moved.scale shouldBe Vec2.ONE
        moved.rotation shouldBe 20f
    }

    @Test
    fun `rotation follows the finger around the anchor`() {
        val start = Transform()
        val pivot = Vec2(100f, 50f)
        val rotated = Handles.rotate(bounds, start, pivot + Vec2(0f, -80f), pivot + Vec2(80f, 0f), snap = false)
        rotated.rotation shouldBe (90f plusOrMinus 0.001f)
    }

    @Test
    fun `rotation snaps near the common angles but not between them`() {
        Handles.snapAngle(43f) shouldBe (45f plusOrMinus 0.001f)
        Handles.snapAngle(0.5f) shouldBe (0f plusOrMinus 0.001f)
        // 37 is a deliberate angle; pulling it to 30 would make fine rotation impossible.
        Handles.snapAngle(37f) shouldBe (37f plusOrMinus 0.001f)
    }

    @Test
    fun `a rotation grab at the anchor is ignored rather than spinning on noise`() {
        val start = Transform(rotation = 12f)
        Handles.rotate(bounds, start, Vec2(100f, 50f), Vec2(100.2f, 50f)) shouldBe start
    }

    @Test
    fun `handles stay the same size on screen as the canvas zooms`() {
        val close = Handles.layout(bounds, Transform(), viewport.copy(zoom = 8f))
        val far = Handles.layout(bounds, Transform(), viewport.copy(zoom = 0.1f))
        val gap = { l: HandleLayout ->
            (l.positions.getValue(Handle.ROTATE) - l.positions.getValue(Handle.TOP)).length
        }
        // The rotation handle's reach is a screen distance; in canvas units it would vanish when
        // zoomed out and float far off the layer when zoomed in.
        gap(close) shouldBe (gap(far) plusOrMinus 0.01f)
        gap(close) shouldBe (HandleConfig().rotateDistance plusOrMinus 0.01f)
    }

    @Test
    fun `the rotation handle follows the layer round rather than staying above the screen`() {
        val upright = Handles.layout(bounds, Transform(), viewport)
        val inverted = Handles.layout(bounds, Transform(rotation = 180f), viewport)
        val above = upright.positions.getValue(Handle.ROTATE).y < upright.positions.getValue(Handle.BODY).y
        val below = inverted.positions.getValue(Handle.ROTATE).y > inverted.positions.getValue(Handle.BODY).y
        above shouldBe true
        below shouldBe true
    }

    @Test
    fun `a corner wins over the edge it overlaps`() {
        val layout = Handles.layout(Rect(0f, 0f, 30f, 30f), Transform(), viewport)
        // On a small layer every handle is inside every other's touch radius. A corner is the more
        // useful grab, so it has to be tested first.
        layout.hitTest(layout.positions.getValue(Handle.TOP_LEFT)) shouldBe Handle.TOP_LEFT
    }

    @Test
    fun `the interior selects the layer and the outside selects nothing`() {
        val layout = Handles.layout(bounds, Transform(), viewport)
        layout.hitTest(viewport.toScreen(Vec2(100f, 50f))) shouldBe Handle.BODY
        layout.hitTest(viewport.toScreen(Vec2(1000f, 1000f))) shouldBe null
    }

    @Test
    fun `the interior test follows the rotation instead of an upright box`() {
        val layout = Handles.layout(bounds, Transform(rotation = 45f), viewport)
        // A corner of the upright bounding box that the rotated layer does not actually cover.
        val outsideButInBoundingBox = viewport.toScreen(Vec2(-40f, -40f))
        layout.containsPoint(outsideButInBoundingBox) shouldBe false
        layout.containsPoint(viewport.toScreen(Vec2(100f, 50f))) shouldBe true
    }

    @Test
    fun `the touch target is larger than the dot that is drawn`() {
        val layout = Handles.layout(bounds, Transform(), viewport)
        val handle = layout.positions.getValue(Handle.TOP_RIGHT)
        // Twenty screen pixels off and it still catches; hit-testing at the drawn size is why
        // resize on a phone feels like it misses.
        layout.hitTest(handle + Vec2(20f, 0f)) shouldBe Handle.TOP_RIGHT
        layout.hitTest(handle + Vec2(200f, -200f)) shouldBe null
    }

    @Test
    fun `canvas bounds cover a rotated layer`() {
        val square = Rect(0f, 0f, 100f, 100f)
        val turned = Handles.canvasBounds(square, Transform(rotation = 45f))
        // A square turned 45 degrees spans its own diagonal.
        turned.width shouldBe (141.42f plusOrMinus 0.05f)
        turned.height shouldBe (141.42f plusOrMinus 0.05f)
    }
}
