package ir.pixellab.engine.android

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode as AndroidBlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.paint.BrushPreset
import ir.pixellab.core.paint.BrushTip
import ir.pixellab.core.paint.PixelSelection
import ir.pixellab.core.paint.Stamp
import ir.pixellab.core.render.toArgb

/**
 * Paints dabs onto pixels.
 *
 * The division from [ir.pixellab.core.paint.StampPlanner] is the point: the planner decides
 * everything about *what* the brush does, this decides only how a single resolved dab is drawn. So
 * the whole of the brush's behaviour — pressure, jitter, spacing, taper — is testable without a
 * device, and this class has no opinions left to get wrong.
 *
 * A stroke is accumulated in its own buffer before it reaches the layer. That is what separates flow
 * from opacity: dabs build up against each other inside the buffer at their own flow, and the
 * finished buffer meets the layer once at the stroke's opacity. Painting each dab straight onto the
 * layer instead — the obvious implementation — makes opacity behave exactly like flow, and a stroke
 * at 50% that crosses itself comes out at 75%.
 */
class BrushRasterizer {

    /**
     * A stroke in progress.
     *
     * Held rather than recreated because the buffer is layer-sized: allocating one per touch event
     * would allocate several megabytes sixty times a second.
     */
    class Stroke internal constructor(
        internal val buffer: Bitmap,
        internal val canvas: Canvas,
        val preset: BrushPreset,
    ) {
        internal var touched = false

        fun recycle() = buffer.recycle()
    }

    fun begin(width: Int, height: Int, preset: BrushPreset): Stroke {
        val buffer = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return Stroke(buffer, Canvas(buffer), preset)
    }

    /**
     * Adds dabs to a stroke in progress.
     *
     * Additive so the canvas can show the stroke as it is drawn: a rasteriser that needed the
     * finished stroke would leave the user painting blind.
     */
    fun add(stroke: Stroke, stamps: List<Stamp>, tip: Bitmap? = null) {
        for (stamp in stamps) {
            if (stamp.size <= 0f || stamp.flow <= 0f) continue
            stroke.touched = true
            when (val shape = stroke.preset.tip) {
                is BrushTip.Round -> drawRound(stroke.canvas, stamp, shape.hardness)
                is BrushTip.Sampled -> if (tip != null) drawSampled(stroke.canvas, stamp, tip)
            }
        }
    }

    /**
     * Adds dabs that copy from elsewhere on the layer — the clone stamp.
     *
     * Each dab is the source image, shifted by [offset], seen through the same round falloff an
     * ordinary dab has. That is done with a single composed shader rather than by drawing the patch
     * and then masking it: masking needs an offscreen layer per dab, and a stroke lays down hundreds.
     *
     * The source is a snapshot taken when the stroke began, not the layer as it is now. Sampling the
     * live layer makes the brush read its own output the moment the two overlap, and the copied
     * texture smears into a spiral — which is the classic way a clone tool is got wrong.
     *
     * @param offset how far the destination is from the source, so a dab at `p` copies from
     *   `p - offset`.
     */
    fun addClone(stroke: Stroke, stamps: List<Stamp>, source: Bitmap, offset: ir.pixellab.core.model.Vec2) {
        val patch = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            // The shader maps a device point through the inverse of this matrix, so translating by
            // the offset makes a dab at p read the source at p - offset.
            setLocalMatrix(Matrix().apply { setTranslate(offset.x, offset.y) })
        }

        for (stamp in stamps) {
            if (stamp.size <= 0f || stamp.flow <= 0f) continue
            stroke.touched = true
            val radius = stamp.size / 2f
            val hardness = (stroke.preset.tip as? BrushTip.Round)?.hardness ?: DEFAULT_CLONE_HARDNESS
            val core = ((stamp.flow * MAX_ALPHA).toInt().coerceIn(0, MAX_ALPHA.toInt()) shl 24) or WHITE

            val falloff = RadialGradient(
                stamp.position.x, stamp.position.y, radius,
                intArrayOf(core, core, WHITE),
                floatArrayOf(0f, hardness.coerceIn(0f, ALMOST_ONE), 1f),
                Shader.TileMode.CLAMP,
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // DST_IN keeps the patch's colour and multiplies its alpha by the falloff's, which
                // is exactly a soft-edged dab of copied pixels.
                shader = ComposeShader(patch, falloff, PorterDuff.Mode.DST_IN)
            }
            stroke.canvas.drawCircle(stamp.position.x, stamp.position.y, radius, paint)
        }
    }

    /**
     * A round dab.
     *
     * The falloff is a radial gradient whose solid core ends at the hardness, which is Photoshop's
     * definition: a hardness of 0.8 means the dab is opaque out to 80% of its radius and fades over
     * the rest. Implementing hardness as a blur radius instead makes a soft brush stop being soft
     * when it is resized, which is exactly the behaviour people rely on it for.
     */
    private fun drawRound(canvas: Canvas, stamp: Stamp, hardness: Float) {
        val radius = stamp.size / 2f
        val color = stamp.color.copy(a = stamp.color.a * stamp.flow).toArgb()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = if (hardness >= 1f) {
                null
            } else {
                RadialGradient(
                    0f, 0f, radius,
                    intArrayOf(color, color, color and 0x00FFFFFF),
                    // Three stops rather than two: without the middle one the dab starts fading at
                    // the very centre and a "hard" brush of hardness 0.9 still looks soft.
                    floatArrayOf(0f, hardness.coerceIn(0f, ALMOST_ONE), 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            if (hardness >= 1f) this.color = color
        }

        canvas.save()
        canvas.translate(stamp.position.x, stamp.position.y)
        canvas.rotate(stamp.angle)
        // Roundness squashes the tip along its own angle, which is how a chisel nib behaves and why
        // the rotation has to come first.
        canvas.scale(1f, stamp.roundness.coerceAtLeast(MIN_ROUNDNESS))
        canvas.drawCircle(0f, 0f, radius, paint)
        canvas.restore()
    }

    private fun drawSampled(canvas: Canvas, stamp: Stamp, tip: Bitmap) {
        val radius = stamp.size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (stamp.flow * stamp.color.a * MAX_ALPHA).toInt().coerceIn(0, MAX_ALPHA.toInt())
            // The tip image supplies coverage; the colour comes from the brush. Drawing the image's
            // own colours is what turns a texture brush into a rubber stamp.
            colorFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                BlendModeColorFilter(stamp.color.toArgb(), AndroidBlendMode.SRC_IN)
            } else {
                @Suppress("DEPRECATION")
                android.graphics.PorterDuffColorFilter(stamp.color.toArgb(), PorterDuff.Mode.SRC_IN)
            }
        }
        canvas.save()
        canvas.translate(stamp.position.x, stamp.position.y)
        canvas.rotate(stamp.angle)
        canvas.scale(1f, stamp.roundness.coerceAtLeast(MIN_ROUNDNESS))
        canvas.drawBitmap(
            tip,
            null,
            android.graphics.RectF(-radius, -radius, radius, radius),
            paint,
        )
        canvas.restore()
    }

    /**
     * Puts a finished stroke onto a layer.
     *
     * Returns a new image rather than editing in place, because undo stores the previous pixels and
     * an in-place edit would have already destroyed them. A stroke is at most a few megabytes and
     * the alternative is a paint tool with no undo.
     */
    fun commit(target: RasterImage, stroke: Stroke, selection: PixelSelection? = null): RasterImage {
        if (!stroke.touched) return target

        val result = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(
            Bitmap.createBitmap(target.pixels, target.width, target.height, Bitmap.Config.ARGB_8888),
            0f, 0f, null,
        )

        val masked = if (selection == null) stroke.buffer else applySelection(stroke.buffer, selection)
        val paint = Paint().apply {
            alpha = (stroke.preset.opacity * MAX_ALPHA).toInt().coerceIn(0, MAX_ALPHA.toInt())
            xfermode = xfermodeFor(stroke.preset)
        }
        canvas.drawBitmap(masked, 0f, 0f, paint)
        if (masked !== stroke.buffer) masked.recycle()

        val pixels = IntArray(target.width * target.height)
        result.getPixels(pixels, 0, target.width, 0, 0, target.width, target.height)
        result.recycle()
        return RasterImage(target.width, target.height, pixels)
    }

    /**
     * Narrows a stroke to the selection.
     *
     * Applied to the stroke rather than to the layer, so the pixels outside the selection are never
     * touched at all. Masking afterwards would mean the layer had already been painted and then
     * partly restored, which loses whatever was under the stroke where the selection was partial.
     */
    private fun applySelection(buffer: Bitmap, selection: PixelSelection): Bitmap {
        val width = buffer.width
        val height = buffer.height
        val pixels = IntArray(width * height)
        buffer.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val x = i % width
            val y = i / width
            val coverage = selection[x, y]
            if (coverage == 255) continue
            if (coverage == 0) {
                pixels[i] = 0
            } else {
                val alpha = ((pixels[i] ushr 24) * coverage / 255) shl 24
                pixels[i] = alpha or (pixels[i] and 0x00FFFFFF)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * How the stroke meets the layer.
     *
     * Only the modes Android's 2D pipeline expresses. The full twenty-seven live on the GPU in the
     * compositor, and routing a brush through it would mean a round trip to a texture for every
     * touch event — the rest are reachable by painting on a layer that carries the blend mode
     * instead, which is what Photoshop users do anyway.
     */
    private fun xfermodeFor(preset: BrushPreset): PorterDuffXfermode? = when {
        preset.erase -> PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        preset.blendMode == BlendMode.MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        preset.blendMode == BlendMode.SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        preset.blendMode == BlendMode.OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        preset.blendMode == BlendMode.DARKEN -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)
        preset.blendMode == BlendMode.LIGHTEN -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
        else -> null
    }

    private companion object {
        const val MAX_ALPHA = 255f

        /** A dab thinner than this cannot be antialiased usefully and shows as a hard line. */
        const val MIN_ROUNDNESS = 0.05f

        /** A hardness of exactly 1 is handled without a gradient; this keeps the stops ordered. */
        const val ALMOST_ONE = 0.999f

        /** Transparent white: the falloff carries coverage only, and never tints the copied pixels. */
        const val WHITE = 0x00FFFFFF

        /** Only reached by a sampled tip in clone mode, where the falloff still has to be soft. */
        const val DEFAULT_CLONE_HARDNESS = 0.5f
    }
}
