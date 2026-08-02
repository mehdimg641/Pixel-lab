package ir.pixellab.engine.android

import android.graphics.Bitmap
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.render.GlDevice
import ir.pixellab.core.render.TextureHandle
import ir.pixellab.core.render.TextureSource

/**
 * Supplies and caches the textures an effect owns: its fills, its curve tables, its patterns.
 *
 * Caching is not an optimisation here, it is a requirement. A bevel asks for two curve tables on
 * every pass and a ten-shadow stack asks for a fill per shadow; rebuilding a 256-sample table and
 * uploading it sixty times a second would cost more than the effects themselves. The key is the
 * *value* rather than the effect, so two strokes sharing a colour share one texture.
 */
class EffectTextures(
    private val device: GlDevice,
    private val rasterizer: LayerRasterizer = LayerRasterizer(),
) : TextureSource {

    private val cache = HashMap<Any, TextureHandle>()

    /**
     * Size of a fill texture.
     *
     * Fills are sampled in the layer's own normalised space, so they do not need to match the
     * layer's pixel size — a fixed square is enough for a solid and generous for a gradient, and it
     * means resizing a layer does not invalidate its paint.
     */
    var fillSize: Int = DEFAULT_FILL_SIZE

    override fun textureFor(effect: Effect?, sampler: String): TextureHandle? {
        val request = request(effect, sampler) ?: return null
        return cache.getOrPut(request.key) { upload(request.build()) }
    }

    /** Drops everything. Call when the GL context is lost; the handles are invalid after that. */
    fun dispose() {
        cache.values.forEach(device::deleteTexture)
        cache.clear()
    }

    /** Forgets one entry, for when a fill has been edited and its texture is stale. */
    fun invalidate(fill: Fill) {
        cache.remove(fill)?.let(device::deleteTexture)
    }

    private class Request(val key: Any, val build: () -> Bitmap)

    /**
     * Maps a sampler name to the value behind it.
     *
     * The mapping is here rather than in the shaders or the graph because it is the one place that
     * genuinely knows both: `uFill` means a different field on a stroke than on an overlay, and no
     * amount of naming discipline in the shader can express that.
     */
    private fun request(effect: Effect?, sampler: String): Request? {
        val fill: Fill? = when {
            effect is Effect.Stroke && sampler == "uFill" -> effect.fill
            effect is Effect.OuterGlow && sampler == "uFill" -> effect.fill
            effect is Effect.InnerGlow && sampler == "uFill" -> effect.fill
            effect is Effect.Overlay && sampler == "uFill" -> effect.fill
            effect is Effect.Extrude && sampler == "uNearFill" -> effect.nearFill
            effect is Effect.Extrude && sampler == "uFarFill" -> effect.farFill
            effect is Effect.DropShadow && sampler == "uFill" -> Fill.Solid(effect.color)
            effect is Effect.InnerShadow && sampler == "uFill" -> Fill.Solid(effect.color)
            effect is Effect.Satin && sampler == "uFill" -> Fill.Solid(effect.color)
            // A layer's own body: the graph's fill pass carries no effect, so this is where the
            // style's paint arrives.
            effect == null && sampler == "uFill" -> layerFill
            else -> null
        }
        if (fill != null) {
            val size = fillSize
            return Request(fill) { rasterizer.fill(fill, size, size, patternFor(fill)) }
        }

        val curve: Curve? = when {
            effect is Effect.Bevel && sampler == "uProfile" -> effect.profile
            effect is Effect.Bevel && sampler == "uGloss" -> effect.glossContour
            else -> null
        }
        if (curve != null) return Request(curve) { rasterizer.curveLut(curve) }

        return null
    }

    /**
     * The paint for the current layer's fill pass.
     *
     * Set by the renderer before each layer. A field rather than a parameter because the fill pass
     * reaches this through [TextureSource], which deliberately knows nothing about layers.
     */
    var layerFill: Fill = Fill.Solid(Color.BLACK)

    /** Decoded pattern images, supplied by the asset store. */
    var patterns: Map<String, Bitmap> = emptyMap()

    private fun patternFor(fill: Fill): Bitmap? =
        (fill as? Fill.Pattern)?.let { patterns[it.asset.value] }

    private fun upload(bitmap: Bitmap): TextureHandle {
        val handle = device.createTexture(bitmap.width, bitmap.height, bytesPerPixel = 4)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        device.uploadArgb(handle, bitmap.width, bitmap.height, pixels)
        return handle
    }

    private companion object {
        /**
         * Large enough that a gradient across a full-width headline does not band, small enough
         * that a style with a dozen fills costs well under a megabyte.
         */
        const val DEFAULT_FILL_SIZE = 256
    }
}
