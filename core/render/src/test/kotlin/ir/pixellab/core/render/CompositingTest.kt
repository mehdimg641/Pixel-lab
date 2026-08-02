package ir.pixellab.core.render

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CompositingTest {

    private infix fun Vec2.shouldBeNear(other: Vec2) {
        x shouldBe (other.x plusOrMinus 0.001f)
        y shouldBe (other.y plusOrMinus 0.001f)
    }

    @Test
    fun `identity leaves a point alone`() {
        Affine.IDENTITY.map(Vec2(3f, 7f)) shouldBeNear Vec2(3f, 7f)
    }

    @Test
    fun `composition applies right to left`() {
        // Translate then scale is not scale then translate; getting the order backwards puts a
        // layer at a plausible but wrong place, which is the hardest kind of error to spot.
        val scaleThenTranslate = Affine.translate(10f, 0f) * Affine.scale(2f, 2f)
        scaleThenTranslate.map(Vec2(1f, 0f)) shouldBeNear Vec2(12f, 0f)

        val translateThenScale = Affine.scale(2f, 2f) * Affine.translate(10f, 0f)
        translateThenScale.map(Vec2(1f, 0f)) shouldBeNear Vec2(22f, 0f)
    }

    @Test
    fun `rotation is clockwise under a downward y axis`() {
        // The same convention as Vec2.rotated and Transform.rotation; a matrix that disagreed
        // would spin layers the opposite way from their handles.
        Affine.rotate(90f).map(Vec2(1f, 0f)) shouldBeNear Vec2(0f, 1f)
    }

    @Test
    fun `inverting undoes a map`() {
        val map = Affine.translate(30f, -12f) * Affine.rotate(37f) * Affine.scale(1.7f, 0.6f)
        val point = Vec2(140f, 80f)
        map.inverse().map(map.map(point)) shouldBeNear point
    }

    @Test
    fun `a collapsed map inverts to nothing rather than to identity`() {
        // A layer scaled to zero on an axis has no inverse. Identity would draw it at full size in
        // the wrong place; a degenerate map draws nothing, which is what zero width should look like.
        val collapsed = Affine.scale(0f, 1f).inverse()
        collapsed.values.all { it == 0f } shouldBe true
    }

    @Test
    fun `a matrix of the wrong size is rejected`() {
        assertThrows<IllegalArgumentException> { Affine(FloatArray(6)) }
    }

    @Test
    fun `an untransformed layer maps its texture straight onto the canvas`() {
        val map = Compositing.canvasUvToLayerUv(
            canvasSize = Vec2(1000f, 1000f),
            textureBounds = Rect(0f, 0f, 500f, 250f),
            transform = Transform(),
        )
        // The layer occupies the top-left corner, so the canvas's origin is the texture's origin
        // and the canvas point at (500, 250) is the texture's far corner.
        map.map(Vec2(0f, 0f)) shouldBeNear Vec2(0f, 0f)
        map.map(Vec2(0.5f, 0.25f)) shouldBeNear Vec2(1f, 1f)
    }

    @Test
    fun `moving a layer moves where the canvas samples it`() {
        val map = Compositing.canvasUvToLayerUv(
            canvasSize = Vec2(1000f, 1000f),
            textureBounds = Rect(0f, 0f, 500f, 250f),
            transform = Transform(translation = Vec2(200f, 100f)),
        )
        // Canvas (200,100) is now the layer's origin.
        map.map(Vec2(0.2f, 0.1f)) shouldBeNear Vec2(0f, 0f)
    }

    @Test
    fun `a scaled layer samples its texture over a larger area`() {
        val map = Compositing.canvasUvToLayerUv(
            canvasSize = Vec2(1000f, 1000f),
            textureBounds = Rect(0f, 0f, 500f, 250f),
            transform = Transform(scale = Vec2(2f, 2f)),
        )
        // Scaling is about the anchor, which defaults to the centre, so the layer grows outwards
        // from (250, 125): the canvas now covers only half the texture's extent per unit, and the
        // canvas origin lands a quarter of the way *into* the texture rather than at its corner.
        map.map(Vec2(0f, 0f)) shouldBeNear Vec2(0.25f, 0.25f)
        map.map(Vec2(0.25f, 0.125f)) shouldBeNear Vec2(0.5f, 0.5f)
        // And the texture's own origin has moved out to canvas (-250, -125).
        map.map(Vec2(-0.25f, -0.125f)) shouldBeNear Vec2(0f, 0f)
    }

    @Test
    fun `a rotated layer is sampled through its own frame`() {
        val map = Compositing.canvasUvToLayerUv(
            canvasSize = Vec2(1000f, 1000f),
            textureBounds = Rect(0f, 0f, 400f, 400f),
            transform = Transform(rotation = 90f),
        )
        // The centre is fixed under rotation whatever the angle.
        map.map(Vec2(0.2f, 0.2f)) shouldBeNear Vec2(0.5f, 0.5f)
    }

    @Test
    fun `the bleed is inside the texture, so an effect is not clipped at the shape's edge`() {
        // A shadow reaching 50 units past the shape means the texture starts at -50; the canvas
        // point at the shape's own origin must therefore land *inside* the texture, not at zero.
        val map = Compositing.canvasUvToLayerUv(
            canvasSize = Vec2(1000f, 1000f),
            textureBounds = Rect(-50f, -50f, 450f, 450f),
            transform = Transform(),
        )
        map.map(Vec2(0f, 0f)) shouldBeNear Vec2(0.1f, 0.1f)
    }

    @Test
    fun `the present map turns screen coordinates into canvas coordinates`() {
        val map = Compositing.screenUvToCanvasUv(
            screenSize = Vec2(1080f, 2400f),
            canvasSize = Vec2(1000f, 1000f),
            offset = Vec2(40f, 700f),
            zoom = 1f,
            rotation = 0f,
        )
        // Screen (40, 700) is where the canvas origin was placed.
        map.map(Vec2(40f / 1080f, 700f / 2400f)) shouldBeNear Vec2(0f, 0f)
        map.map(Vec2(1040f / 1080f, 1700f / 2400f)) shouldBeNear Vec2(1f, 1f)
    }

    @Test
    fun `zooming in samples a smaller part of the canvas`() {
        val far = Compositing.screenUvToCanvasUv(Vec2(1000f, 1000f), Vec2(1000f, 1000f), Vec2.ZERO, 1f, 0f)
        val near = Compositing.screenUvToCanvasUv(Vec2(1000f, 1000f), Vec2(1000f, 1000f), Vec2.ZERO, 4f, 0f)
        // At 4x the whole screen shows a quarter of the canvas on each axis.
        far.map(Vec2(1f, 1f)) shouldBeNear Vec2(1f, 1f)
        near.map(Vec2(1f, 1f)) shouldBeNear Vec2(0.25f, 0.25f)
    }

    @Test
    fun `the present map agrees with the viewport the gestures use`() {
        // Two independent implementations of the same camera would drift, and the symptom is
        // handles that no longer sit on the artwork they belong to.
        val viewport = ir.pixellab.core.canvas.Viewport(
            offset = Vec2(63f, 210f), zoom = 2.5f, rotation = 24f, screenSize = Vec2(1080f, 2400f),
        )
        val canvas = Vec2(1000f, 1000f)
        val map = Compositing.screenUvToCanvasUv(
            viewport.screenSize, canvas, viewport.offset, viewport.zoom, viewport.rotation,
        )
        for (probe in listOf(Vec2(0.1f, 0.2f), Vec2(0.75f, 0.4f), Vec2(0.5f, 0.9f))) {
            val screenPoint = Vec2(probe.x * viewport.screenSize.x, probe.y * viewport.screenSize.y)
            val expected = viewport.toCanvas(screenPoint)
            val actual = map.map(probe)
            actual.x shouldBe ((expected.x / canvas.x) plusOrMinus 0.0005f)
            actual.y shouldBe ((expected.y / canvas.y) plusOrMinus 0.0005f)
        }
    }
}
