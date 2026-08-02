package ir.pixellab.engine.android

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientType
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.TileMode
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.render.Luts
import ir.pixellab.core.render.toArgb
import kotlin.math.max

/** A rasterised layer: its silhouette, and where that silhouette sits in canvas coordinates. */
data class RasterizedLayer(val bitmap: Bitmap, val bounds: Rect, val scale: Float)

/**
 * Turns model objects into the pixels the GL pipeline samples.
 *
 * The split matters: the **silhouette** and the **fill** are rasterised separately. A layer's shape
 * decides where the effects apply, and its paint decides what colour they are — combining them into
 * one bitmap would make hollow text impossible and force every effect to re-derive an alpha from a
 * coloured image.
 */
class LayerRasterizer(private val text: TextRasterizer = TextRasterizer()) {

    /**
     * Renders a vector mask's path into a coverage bitmap.
     *
     * White with the coverage in alpha, matching how a raster mask is uploaded, so the composite
     * pass has one sampling rule for both. [feather] is applied with a blur mask filter rather than
     * by blurring the finished bitmap: Skia feathers the *path*, so a soft edge stays centred on
     * the outline instead of eating into the shape by half the radius.
     */
    fun mask(geometry: ShapeGeometry, bounds: Rect, scale: Float = 1f, feather: Float = 0f): Bitmap {
        val width = pixels(bounds.width, scale)
        val height = pixels(bounds.height, scale)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(-bounds.left, -bounds.top)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            if (feather > 0f) {
                maskFilter = android.graphics.BlurMaskFilter(feather, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
        }
        canvas.drawPath(ShapeRasterizer.path(geometry), paint)
        return bitmap
    }

    /**
     * Renders a layer's coverage into white pixels with correct alpha.
     *
     * White, not the layer's colour: every shader downstream reads `.a` from this and takes its
     * colour from a separate fill texture, so tinting here would double-apply the paint.
     */
    fun silhouette(
        layer: Layer,
        bounds: Rect,
        scale: Float = 1f,
        font: FontFile? = null,
        image: Bitmap? = null,
    ): RasterizedLayer {
        val width = pixels(bounds.width, scale)
        val height = pixels(bounds.height, scale)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        // The layer's own coordinates start at the bounds' origin, which is not the texture's.
        canvas.translate(-bounds.left, -bounds.top)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        when (layer) {
            is Layer.Shape -> canvas.drawPath(ShapeRasterizer.path(layer.geometry), paint)
            is Layer.Text -> if (font != null) {
                canvas.drawPath(text.rasterize(layer.spec, font).outline, paint)
            }
            // A photograph's coverage is its own alpha, which is what makes a stroke or a shadow
            // follow a cut-out subject rather than the rectangle it arrived in.
            is Layer.Image -> if (image != null) {
                // Drawn at the layer's own origin and its own size, not across the whole texture:
                // the texture is larger by however much bleed the effect stack asked for, and
                // stretching into that would move the photograph away from its selection box.
                canvas.drawBitmap(
                    image,
                    null,
                    android.graphics.RectF(0f, 0f, image.width.toFloat(), image.height.toFloat()),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            }
            // A group is composited from its children and an instance from its source; neither has
            // a silhouette of its own to draw here.
            else -> Unit
        }
        return RasterizedLayer(bitmap, bounds, scale)
    }

    /**
     * Renders a paint across the same region as its silhouette.
     *
     * Sized to the layer, not to the canvas: a gradient's angle and extent are defined relative to
     * the shape it fills, so a canvas-sized ramp would slide as the layer moved.
     */
    fun fill(fill: Fill, width: Int, height: Int, pattern: Bitmap? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (fill) {
            is Fill.Solid -> paint.color = fill.color.toArgb()
            is Fill.Gradient -> paint.shader = gradientShader(fill, width, height)
            is Fill.Pattern -> paint.shader = pattern?.let { patternShader(fill, it) }
            // Frosted glass samples what is already composited beneath, which only the GPU has;
            // the fill texture is left clear and the backdrop pass supplies the colour.
            is Fill.Backdrop -> paint.color = android.graphics.Color.TRANSPARENT
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    /**
     * A contour as a one-pixel-tall lookup texture.
     *
     * Stored in all three colour channels rather than one, so the shader can sample it with a plain
     * `.r` regardless of whether the driver gave it a single-channel format.
     */
    fun curveLut(curve: Curve, size: Int = Luts.DEFAULT_SIZE): Bitmap {
        val table = Luts.curve(curve, size)
        val pixels = IntArray(size) { i ->
            val v = (table[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, size, 1, Bitmap.Config.ARGB_8888)
    }

    private fun gradientShader(gradient: Fill.Gradient, width: Int, height: Int): Shader {
        // The ramp is sampled through the shared table so that midpoints and the interpolation
        // curve behave identically here and on the GPU. Handing Android the raw stops instead
        // would silently drop both.
        val ramp = Luts.gradient(gradient)
        val positions = FloatArray(ramp.size) { it.toFloat() / (ramp.size - 1) }

        val centre = Vec2(
            width / 2f + gradient.offset.x * width,
            height / 2f + gradient.offset.y * height,
        )
        val reach = max(width, height) * gradient.scale / 2f
        val direction = Vec2.fromAngle(gradient.angle)

        return when (gradient.type) {
            GradientType.LINEAR, GradientType.REFLECTED -> LinearGradient(
                centre.x - direction.x * reach, centre.y + direction.y * reach,
                centre.x + direction.x * reach, centre.y - direction.y * reach,
                ramp, positions,
                if (gradient.type == GradientType.REFLECTED) Shader.TileMode.MIRROR else Shader.TileMode.CLAMP,
            )
            GradientType.RADIAL, GradientType.DIAMOND -> RadialGradient(
                centre.x, centre.y, max(1f, reach), ramp, positions, Shader.TileMode.CLAMP,
            )
            GradientType.ANGULAR -> SweepGradient(centre.x, centre.y, ramp, positions)
        }
    }

    private fun patternShader(pattern: Fill.Pattern, bitmap: Bitmap): Shader {
        val mode = when (pattern.tileMode) {
            TileMode.CLAMP -> Shader.TileMode.CLAMP
            TileMode.REPEAT -> Shader.TileMode.REPEAT
            TileMode.MIRROR -> Shader.TileMode.MIRROR
        }
        return BitmapShader(bitmap, mode, mode).apply {
            setLocalMatrix(
                Matrix().apply {
                    postScale(pattern.scale.x, pattern.scale.y)
                    postRotate(pattern.rotation)
                    postTranslate(pattern.offset.x, pattern.offset.y)
                },
            )
        }
    }

    private companion object {
        /**
         * A zero-sized texture is illegal in GL, and a layer can legitimately be measured at zero —
         * an empty text run, a shape scaled to nothing — so the floor is one pixel rather than a
         * crash at allocation time.
         */
        fun pixels(extent: Float, scale: Float) =
            kotlin.math.ceil(extent * scale).toInt().coerceAtLeast(1)
    }
}
