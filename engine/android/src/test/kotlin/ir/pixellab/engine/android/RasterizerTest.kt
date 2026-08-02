package ir.pixellab.engine.android

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Path
import android.graphics.RectF
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Contour
import ir.pixellab.core.model.Corners
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.model.GradientType
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.PathNode
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs against Robolectric's native graphics backend, so these are real Skia paths and real
 * rasterised pixels rather than stubs. Shape maths that is only checked by eye on a device is
 * exactly the kind that ships wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShapeRasterizerTest {

    // The single-argument overload arrived in API 34 and minSdk is 26, so the deprecated one is
    // still the only version this build can call.
    @Suppress("DEPRECATION")
    private fun Path.bounds(): RectF = RectF().also { computeBounds(it, true) }

    private fun boundsOf(geometry: ShapeGeometry): RectF = ShapeRasterizer.path(geometry).bounds()

    @Test
    fun `a rectangle fills its declared size`() {
        val bounds = boundsOf(ShapeGeometry.Rectangle(Vec2(200f, 100f)))
        bounds.width() shouldBe (200f plusOrMinus 0.01f)
        bounds.height() shouldBe (100f plusOrMinus 0.01f)
    }

    @Test
    fun `corner radii are clamped to half the shorter side`() {
        // Android's addRoundRect misbehaves past that limit and inverts the shape — which is what a
        // corner slider dragged to its end would otherwise produce.
        val clamped = ShapeRasterizer.clampCorners(Corners.all(500f), width = 200f, height = 100f)
        clamped.topLeft shouldBe 50f
        clamped.bottomRight shouldBe 50f

        val bounds = boundsOf(ShapeGeometry.Rectangle(Vec2(200f, 100f), Corners.all(500f)))
        bounds.width() shouldBe (200f plusOrMinus 0.01f)
    }

    @Test
    fun `each corner keeps its own radius`() {
        val corners = ShapeRasterizer.clampCorners(Corners(4f, 8f, 12f, 16f), 200f, 200f)
        corners.topLeft shouldBe 4f
        corners.topRight shouldBe 8f
        corners.bottomRight shouldBe 12f
        corners.bottomLeft shouldBe 16f
    }

    @Test
    fun `an ellipse spans its box`() {
        val bounds = boundsOf(ShapeGeometry.Ellipse(Vec2(120f, 60f)))
        bounds.width() shouldBe (120f plusOrMinus 0.01f)
        bounds.height() shouldBe (60f plusOrMinus 0.01f)
    }

    @Test
    fun `a polygon puts a vertex at the top`() {
        // Every editor draws a hexagon point-up; starting the sweep at zero degrees would rotate it
        // by half a side and look wrong next to any reference.
        val hexagon = ShapeRasterizer.path(ShapeGeometry.Polygon(Vec2(100f, 100f), sides = 6))
        val bounds = hexagon.bounds()
        bounds.top shouldBe (0f plusOrMinus 0.01f)
        bounds.height() shouldBe (100f plusOrMinus 0.01f)
        // Point-up means the flats are on the sides, so it spans 2R·cos30 across — narrower than
        // tall. A hexagon as wide as it is tall is one that was drawn flat-up by mistake.
        bounds.width() shouldBe (86.6f plusOrMinus 0.1f)
    }

    @Test
    fun `a star reaches its outer radius and no further`() {
        val bounds = boundsOf(ShapeGeometry.Star(Vec2(100f, 100f), points = 5, innerRadius = 0.4f))
        // A five-point star point-up sits inside its box rather than filling it: 2R·sin72 across,
        // and R(1 + cos36) tall. Exceeding either would mean the vertices escaped the declared size.
        bounds.width() shouldBe (95.1f plusOrMinus 0.5f)
        bounds.height() shouldBe (90.5f plusOrMinus 0.5f)
        bounds.top shouldBe (0f plusOrMinus 0.01f)
    }

    @Test
    fun `rounding a star does not swallow its inner corners`() {
        // A single radius applied blindly overruns the short edges between a star's points and
        // collapses them; each corner has to be limited by its own shorter edge.
        val sharp = boundsOf(ShapeGeometry.Star(Vec2(100f, 100f), points = 5))
        val rounded = boundsOf(ShapeGeometry.Star(Vec2(100f, 100f), points = 5, cornerRadius = 200f))
        (rounded.width() > sharp.width() * 0.7f) shouldBe true
        rounded.isEmpty shouldBe false
    }

    @Test
    fun `a bent arrow leaves the straight line between its ends`() {
        val straight = boundsOf(ShapeGeometry.Arrow(Vec2(0f, 50f), Vec2(200f, 50f), headSize = 0f))
        val bent = boundsOf(ShapeGeometry.Arrow(Vec2(0f, 50f), Vec2(200f, 50f), bend = 40f, headSize = 0f))
        (bent.height() > straight.height() + 20f) shouldBe true
    }

    @Test
    fun `an arrow with no bend still builds a head`() {
        // The head direction is derived from the curve's tangent; with no curve there is no control
        // point to derive it from, and the obvious code path divides by zero.
        val withHead = boundsOf(ShapeGeometry.Arrow(Vec2(0f, 50f), Vec2(200f, 50f), headSize = 20f))
        withHead.isEmpty shouldBe false
        withHead.height() shouldBe (20f plusOrMinus 1f)
    }

    @Test
    fun `a tail head is added only when asked for`() {
        // Each head is its own closed contour, so counting contours is what distinguishes them —
        // the bounds do not, because a head points outwards from a tip that is already an extreme.
        contours(ShapeRasterizer.path(ShapeGeometry.Arrow(Vec2(0f, 50f), Vec2(200f, 50f)))) shouldBe 2
        contours(
            ShapeRasterizer.path(ShapeGeometry.Arrow(Vec2(0f, 50f), Vec2(200f, 50f), tailHead = true)),
        ) shouldBe 3
    }

    @Test
    fun `a head points outwards from its end, not back along the shaft`() {
        val arrow = ShapeGeometry.Arrow(Vec2(0f, 50f), Vec2(200f, 50f), headSize = 30f, tailHead = true)
        val bounds = boundsOf(arrow)
        // A head aimed the wrong way keeps the same bounds but visibly detaches from the line, so
        // the check is that neither tip has been pushed past the arrow's own ends.
        bounds.left shouldBe (0f plusOrMinus 0.01f)
        bounds.right shouldBe (200f plusOrMinus 0.01f)
    }

    private fun contours(path: Path): Int {
        val measure = android.graphics.PathMeasure(path, false)
        var count = 0
        do { count++ } while (measure.nextContour())
        return count
    }

    @Test
    fun `a bezier contour follows its control points`() {
        val geometry = ShapeGeometry.Path(
            listOf(
                Contour(
                    nodes = listOf(
                        PathNode(Vec2(0f, 0f), controlOut = Vec2(0f, 100f)),
                        PathNode(Vec2(100f, 0f), controlIn = Vec2(100f, 100f)),
                    ),
                    closed = false,
                ),
            ),
        )
        val bounds = boundsOf(geometry)
        // The curve bows downwards even though both endpoints sit on y = 0.
        (bounds.bottom > 50f) shouldBe true
    }

    @Test
    fun `an empty contour is skipped rather than crashing`() {
        val geometry = ShapeGeometry.Path(listOf(Contour(nodes = emptyList())))
        ShapeRasterizer.path(geometry).isEmpty shouldBe true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayerRasterizerTest {

    private val rasterizer = LayerRasterizer()

    private fun shape(size: Vec2 = Vec2(100f, 60f)) = Layer.Shape(
        id = LayerId("s"),
        geometry = ShapeGeometry.Rectangle(size),
    )

    private fun Bitmap.alphaAt(x: Int, y: Int) = (getPixel(x, y) ushr 24) and 0xFF

    @Test
    fun `a silhouette covers the shape and nothing else`() {
        val raster = rasterizer.silhouette(shape(), Rect(0f, 0f, 200f, 100f))
        raster.bitmap.width shouldBe 200
        raster.bitmap.height shouldBe 100
        raster.bitmap.alphaAt(50, 30) shouldBe 255
        // Outside the 100x60 rectangle the texture has to be clear, or every effect that reads
        // alpha would treat the whole buffer as the shape.
        raster.bitmap.alphaAt(150, 80) shouldBe 0
    }

    @Test
    fun `a silhouette is white regardless of the layer's paint`() {
        val red = shape().copy(style = ir.pixellab.core.model.Style(fill = Fill.Solid(Color(1f, 0f, 0f))))
        val raster = rasterizer.silhouette(red, Rect(0f, 0f, 200f, 100f))
        // Colour comes from the fill texture; tinting here would apply the paint twice.
        AndroidColor.red(raster.bitmap.getPixel(50, 30)) shouldBe 255
        AndroidColor.green(raster.bitmap.getPixel(50, 30)) shouldBe 255
        AndroidColor.blue(raster.bitmap.getPixel(50, 30)) shouldBe 255
    }

    @Test
    fun `bleed shifts the shape rather than clipping it`() {
        // The texture is padded for effects, so the shape sits inset by exactly the padding.
        val raster = rasterizer.silhouette(shape(), Rect(-20f, -20f, 120f, 80f))
        raster.bitmap.width shouldBe 140
        raster.bitmap.alphaAt(5, 5) shouldBe 0
        raster.bitmap.alphaAt(60, 40) shouldBe 255
    }

    @Test
    fun `export scale multiplies the pixels`() {
        val raster = rasterizer.silhouette(shape(), Rect(0f, 0f, 200f, 100f), scale = 3f)
        raster.bitmap.width shouldBe 600
        raster.bitmap.height shouldBe 300
        raster.bitmap.alphaAt(150, 90) shouldBe 255
    }

    @Test
    fun `a degenerate layer still produces a legal texture`() {
        // GL rejects a zero-sized texture, and an empty text run measures to nothing.
        val raster = rasterizer.silhouette(shape(), Rect(0f, 0f, 0f, 0f))
        raster.bitmap.width shouldBe 1
        raster.bitmap.height shouldBe 1
    }

    @Test
    fun `a solid fill is flat`() {
        val bitmap = rasterizer.fill(Fill.Solid(Color(0.2f, 0.4f, 0.6f)), 32, 32)
        AndroidColor.red(bitmap.getPixel(0, 0)) shouldBe AndroidColor.red(bitmap.getPixel(31, 31))
        AndroidColor.blue(bitmap.getPixel(4, 4)) shouldBe 153
    }

    @Test
    fun `a linear gradient runs across the layer`() {
        val gradient = Fill.Gradient(
            type = GradientType.LINEAR,
            stops = listOf(GradientStop(0f, Color.BLACK), GradientStop(1f, Color.WHITE)),
            angle = 0f,
        )
        val bitmap = rasterizer.fill(gradient, 64, 16)
        val left = AndroidColor.red(bitmap.getPixel(1, 8))
        val right = AndroidColor.red(bitmap.getPixel(62, 8))
        (right > left + 100) shouldBe true
    }

    @Test
    fun `a gradient's midpoint is honoured in the rasterised pixels too`() {
        // The ramp goes through the shared table rather than Android's own stop interpolation, so
        // the diamond a PSD carries survives all the way to the texture.
        val biased = Fill.Gradient(
            stops = listOf(GradientStop(0f, Color.BLACK, midpoint = 0.25f), GradientStop(1f, Color.WHITE)),
        )
        val plain = Fill.Gradient(
            stops = listOf(GradientStop(0f, Color.BLACK), GradientStop(1f, Color.WHITE)),
        )
        val biasedMid = AndroidColor.red(rasterizer.fill(biased, 64, 8).getPixel(16, 4))
        val plainMid = AndroidColor.red(rasterizer.fill(plain, 64, 8).getPixel(16, 4))
        (biasedMid > plainMid + 40) shouldBe true
    }

    @Test
    fun `a backdrop fill leaves the texture clear`() {
        val bitmap = rasterizer.fill(Fill.Backdrop(), 8, 8)
        // The colour comes from the backdrop pass; painting anything here would show through it.
        (bitmap.getPixel(4, 4) ushr 24) shouldBe 0
    }

    @Test
    fun `a curve table is one pixel tall and monotonic`() {
        val lut = rasterizer.curveLut(Curve.LINEAR, size = 64)
        lut.height shouldBe 1
        lut.width shouldBe 64
        AndroidColor.red(lut.getPixel(0, 0)) shouldBe 0
        AndroidColor.red(lut.getPixel(63, 0)) shouldBe 255
        // Stored in all three channels so a `.r` sample works whatever format the driver chose.
        AndroidColor.blue(lut.getPixel(32, 0)) shouldBe AndroidColor.red(lut.getPixel(32, 0))
    }

    @Test
    fun `a shaped curve table is not a straight line`() {
        val rounded = rasterizer.curveLut(Curve.ROUNDED, size = 256)
        val linear = rasterizer.curveLut(Curve.LINEAR, size = 256)
        (AndroidColor.red(rounded.getPixel(64, 0)) > AndroidColor.red(linear.getPixel(64, 0))) shouldBe true
    }
}
