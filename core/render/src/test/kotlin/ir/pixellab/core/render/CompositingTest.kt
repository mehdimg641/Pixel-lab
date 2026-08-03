package ir.pixellab.core.render

import ir.pixellab.core.model.Affine
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

    @Test
    fun `a skew slants the layer and keeps its anchor still`() {
        // Skew was in the model and read by nothing — the compositor built its matrix from
        // translation, rotation and scale only, so the panel accepted an angle and the picture
        // never moved. Photoshop's own Skew command is exactly this.
        val bounds = Rect(0f, 0f, 100f, 100f)
        val skewed = Transform(skew = Vec2(45f, 0f))
        val map = Compositing.layerToCanvas(bounds, skewed)

        // The anchor is the fixed point of every transform about it.
        map.map(Vec2(50f, 50f)) shouldBeNear Vec2(50f, 50f)
        // A tangent of one at forty-five degrees: a point fifty above the anchor slides fifty left.
        map.map(Vec2(50f, 0f)) shouldBeNear Vec2(0f, 0f)
        map.map(Vec2(50f, 100f)) shouldBeNear Vec2(100f, 100f)
    }

    @Test
    fun `the layer map agrees with the handles the gestures use`() {
        // The same reason the camera test above exists, for the other of the two maps: these are
        // independent implementations of one placement, and the order they compose scale, skew and
        // rotation in has to match or a skewed layer's handles drift off its artwork.
        val bounds = Rect(10f, 20f, 210f, 140f)
        val transform = Transform(
            translation = Vec2(35f, -18f),
            scale = Vec2(1.4f, 0.8f),
            rotation = 22f,
            skew = Vec2(14f, -7f),
            anchor = Vec2(0.35f, 0.6f),
        )
        val map = Compositing.layerToCanvas(bounds, transform)
        for (probe in listOf(Vec2(10f, 20f), Vec2(210f, 20f), Vec2(210f, 140f), Vec2(97f, 63f))) {
            map.map(probe) shouldBeNear
                ir.pixellab.core.canvas.Handles.localToCanvas(probe, bounds, transform)
        }
    }

    @Test
    fun `a four-corner warp takes the layer's corners exactly where they were dragged`() {
        // Perspective was in the model — a whole `Perspective` class with four corners — and read
        // by no renderer. A document could be authored with a warp and it would silently vanish.
        val bounds = Rect(0f, 0f, 200f, 100f)
        val warp = ir.pixellab.core.model.Perspective(
            topLeft = Vec2(40f, 10f),
            topRight = Vec2(260f, 30f),
            bottomRight = Vec2(230f, 120f),
            bottomLeft = Vec2(10f, 90f),
        )
        val map = Compositing.layerToCanvas(bounds, Transform(perspective = warp))
        map.map(Vec2(0f, 0f)) shouldBeNear warp.topLeft
        map.map(Vec2(200f, 0f)) shouldBeNear warp.topRight
        map.map(Vec2(200f, 100f)) shouldBeNear warp.bottomRight
        map.map(Vec2(0f, 100f)) shouldBeNear warp.bottomLeft
    }

    @Test
    fun `a warp is projective, so its midpoint is not the average of its corners`() {
        // The property that separates a real perspective transform from a bilinear fudge. Under a
        // genuine homography the centre of the source lands where the *diagonals* cross, which on a
        // trapezium is nearer the short edge — that shift is what the eye reads as depth. A bilinear
        // warp puts it at the average of the four corners and looks like a bent sheet of rubber.
        val bounds = Rect(0f, 0f, 100f, 100f)
        val trapezium = ir.pixellab.core.model.Perspective(
            topLeft = Vec2(30f, 0f),
            topRight = Vec2(70f, 0f),
            bottomRight = Vec2(100f, 100f),
            bottomLeft = Vec2(0f, 100f),
        )
        val map = Compositing.layerToCanvas(bounds, Transform(perspective = trapezium))
        val centre = map.map(Vec2(50f, 50f))
        val average = Vec2(50f, 50f)
        centre.x shouldBe (average.x plusOrMinus 0.001f)
        // Nearer the short top edge than halfway, because that edge is the far one.
        (centre.y < average.y - 1f) shouldBe true
    }

    @Test
    fun `a warp inverts, so a warped layer can be picked up where it is drawn`() {
        val bounds = Rect(0f, 0f, 200f, 100f)
        val transform = Transform(
            translation = Vec2(25f, 40f),
            rotation = 15f,
            perspective = ir.pixellab.core.model.Perspective(
                topLeft = Vec2(20f, 5f),
                topRight = Vec2(240f, 25f),
                bottomRight = Vec2(210f, 115f),
                bottomLeft = Vec2(0f, 95f),
            ),
        )
        val map = Compositing.layerToCanvas(bounds, transform)
        for (probe in listOf(Vec2(0f, 0f), Vec2(200f, 0f), Vec2(130f, 60f))) {
            map.inverse().map(map.map(probe)) shouldBeNear probe
            // And the handles agree with the compositor, which is the only reason a drag lands on
            // the pixel it looks like it landed on.
            map.map(probe) shouldBeNear
                ir.pixellab.core.canvas.Handles.localToCanvas(probe, bounds, transform)
            ir.pixellab.core.canvas.Handles.canvasToLocal(
                map.map(probe), bounds, transform,
            ) shouldBeNear probe
        }
    }

    @Test
    fun `an affine matrix still has a bottom row of zero, zero, one`() {
        // The divide by w is free only if every ordinary transform leaves w at one. If a builder
        // ever wrote something else there, every layer in the document would be scaled by it.
        val ordinary = Affine.translate(3f, 4f) * Affine.rotate(31f) *
            Affine.shear(12f, -5f) * Affine.scale(2f, 0.5f)
        ordinary.values[2] shouldBe 0f
        ordinary.values[5] shouldBe 0f
        ordinary.values[8] shouldBe 1f
    }

    @Test
    fun `a skew survives the round trip back to layer coordinates`() {
        // The inverse is what a drag uses to turn a finger position into a point on the artwork, so
        // an un-inverted shear means a skewed layer cannot be picked up where it is drawn.
        val bounds = Rect(0f, 0f, 200f, 120f)
        val transform = Transform(scale = Vec2(1.3f, 0.9f), rotation = -12f, skew = Vec2(20f, 10f))
        for (probe in listOf(Vec2(0f, 0f), Vec2(200f, 0f), Vec2(140f, 95f))) {
            val onCanvas = ir.pixellab.core.canvas.Handles.localToCanvas(probe, bounds, transform)
            ir.pixellab.core.canvas.Handles.canvasToLocal(onCanvas, bounds, transform) shouldBeNear probe
        }
    }
}
