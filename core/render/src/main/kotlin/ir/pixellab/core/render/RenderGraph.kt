package ir.pixellab.core.render

import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.ColorSettings
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Style
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What a buffer is for, which decides how aggressively it can be recycled. */
enum class BufferRole {
    /** The layer's own rasterised content. */
    LAYER,

    /** Signed distance field of the layer alpha, shared by stroke, shadow, glow and bevel. */
    SDF,

    /**
     * Ping-pong buffer for the jump flood that builds the field.
     *
     * Separate from [SDF] because it holds seed offsets rather than distances and must stay at
     * float precision even when the document is rendering at eight bits.
     */
    SDF_FLOOD,

    /** Intermediate for a separable blur. */
    BLUR,

    /** A copy of what is already composited beneath, for frosted glass. */
    BACKDROP,

    /** The running composite. */
    TARGET,
}

data class BufferSpec(
    val id: String,
    val role: BufferRole,
    val width: Int,
    val height: Int,
    val bytesPerPixel: Int,
) {
    val bytes: Long get() = width.toLong() * height.toLong() * bytesPerPixel

    /**
     * How the buffer is sampled.
     *
     * **A distance field must be read at exact texels, and was not.** Jump flooding stores the
     * *offset to the nearest seed* in a texel and reads it back at integer strides; interpolating
     * between two such offsets produces a vector that points at no seed at all. Linear filtering
     * on that data is not a quality choice made badly, it is a category error.
     *
     * It also removes a dependency nobody had checked. Filtering a half-float texture needs
     * `OES_texture_half_float_linear`, which is a *separate* extension from the
     * `EXT_color_buffer_half_float` this engine tests for; a driver that has the second and not the
     * first returns undefined values for every sample. That collapses the field to a constant, and
     * a constant field makes the stroke shader's coverage `1.0` everywhere outside the letters —
     * an opaque rectangle painted over the artwork, which is exactly what a user reported.
     */
    val filter: TextureFilter
        get() = when (role) {
            BufferRole.SDF, BufferRole.SDF_FLOOD -> TextureFilter.NEAREST
            else -> TextureFilter.LINEAR
        }
}

/** How a sampler reads between texels. */
enum class TextureFilter { LINEAR, NEAREST }

/**
 * One executable step: a shader, its inputs, and where it writes.
 *
 * [floats] and [vectors] carry the uniforms the *graph* decides rather than the effect module — the
 * axis and radius of each half of a separable blur, the stride of each jump-flood step. Those cannot
 * come from the module because the module does not know it was expanded into several passes, and
 * leaving the executor to infer them from pass order is exactly the kind of implicit coupling that
 * breaks the first time a pass is inserted.
 */
data class GraphPass(
    val slot: PassSlot,
    val shaderId: String,
    val inputs: List<String>,
    val output: String,
    val instanceCount: Int = 1,
    val effect: Effect? = null,
    val floats: Map<String, Float> = emptyMap(),
    val vectors: Map<String, FloatArray> = emptyMap(),
)

/**
 * The plan for rendering one layer: which buffers to allocate, in what order to run passes, and
 * what it will cost.
 *
 * Separated from execution so it can be reasoned about and tested without a GPU. Allocation
 * mistakes — a shadow clipped by an undersized texture, a peak that will not fit in memory — are
 * caught here rather than discovered on a device.
 */
data class LayerGraph(
    val buffers: List<BufferSpec>,
    val passes: List<GraphPass>,
    val textureBounds: Rect,
    val readsBackdrop: Boolean,
    val tiles: Int,
) {
    /**
     * Peak memory for this layer.
     *
     * Every buffer in a layer graph is live at once — the blur pair ping-pongs, the flood pair
     * ping-pongs, and the distance field has to outlive both — so the peak is the sum, not a maximum
     * per role. Reporting a smaller number would be the comfortable answer and the one that turns
     * into an out-of-memory crash halfway through a 4K export. Recycling happens *between* layers,
     * which is the executor's job, not the graph's.
     */
    val peakBytes: Long get() = totalBytes

    val totalBytes: Long get() = buffers.sumOf { it.bytes }
}

/**
 * Turns a [RenderPlan] into concrete buffers and passes.
 *
 * Two decisions matter more than the rest.
 *
 * **The distance field is computed once.** Stroke, shadow, glow and bevel all need to know how far
 * each pixel is from the shape's edge. Deriving that separately in four shaders is the obvious
 * implementation and roughly quadruples the cost of a typical style; the reference PSDs stack ten
 * shadows on one layer, so the saving is not theoretical.
 *
 * **Large canvases are tiled.** A GPU refuses textures beyond its maximum size, and a 4K canvas
 * with bleed easily exceeds it. Splitting into horizontal bands keeps every allocation legal.
 */
object RenderGraphBuilder {

    /** Conservative floor for `GL_MAX_TEXTURE_SIZE`; real devices report more. */
    const val DEFAULT_MAX_TEXTURE = 4096

    fun build(
        plan: RenderPlan,
        shapeBounds: Rect,
        scale: Float = 1f,
        color: ColorSettings = ColorSettings(),
        maxTextureSize: Int = DEFAULT_MAX_TEXTURE,
    ): LayerGraph {
        require(scale > 0f) { "scale must be positive, got $scale" }
        require(maxTextureSize >= 64) { "max texture size is implausibly small: $maxTextureSize" }

        val bounds = plan.textureBounds(shapeBounds)
        val width = ceil(bounds.width * scale).toInt().coerceAtLeast(1)
        val height = ceil(bounds.height * scale).toInt().coerceAtLeast(1)
        val bpp = color.precision.bytesPerPixel

        val tiles = ceil(height.toDouble() / maxTextureSize).toInt().coerceAtLeast(1)
        val tileHeight = min(height, maxTextureSize)
        val tileWidth = min(width, maxTextureSize)

        val buffers = ArrayList<BufferSpec>()
        val passes = ArrayList<GraphPass>()

        fun buffer(id: String, role: BufferRole, bytes: Int = bpp): String {
            buffers += BufferSpec(id, role, tileWidth, tileHeight, bytes)
            return id
        }

        val layer = buffer("layer", BufferRole.LAYER)
        val target = buffer("target", BufferRole.TARGET)

        // Anything that reasons about the silhouette shares one distance field.
        val needsSdf = plan.passes.any { it.slot in SDF_CONSUMERS }
        val sdf = if (needsSdf) {
            buildDistanceField(
                buffers = ::buffer,
                passes = passes,
                layer = layer,
                width = tileWidth,
                height = tileHeight,
                reach = sdfReach(plan, scale),
            )
        } else {
            null
        }

        // Blurred alpha is shared too: a stack of ten shadows differs only in radius and offset,
        // so each gets its own blur pass but they reuse the same ping-pong buffers.
        val needsBlur = plan.passes.any { it.slot in BLUR_CONSUMERS }
        val blurA = if (needsBlur) buffer("blurA", BufferRole.BLUR) else null
        val blurB = if (needsBlur) buffer("blurB", BufferRole.BLUR) else null

        val backdrop = if (plan.readsBackdrop) buffer("backdrop", BufferRole.BACKDROP) else null

        for (pass in plan.passes) {
            if (pass.isFill) {
                passes += GraphPass(
                    slot = PassSlot.FILL,
                    shaderId = Shaders.FILL.id,
                    inputs = listOf(layer),
                    output = target,
                    floats = mapOf("uFillOpacity" to plan.style.fillOpacity),
                )
                continue
            }
            val effect = pass.effect ?: continue
            val module = builtinEffectRegistry.moduleFor(effect) ?: continue
            val descriptor = module.describe(effect, RenderContext(scale = scale))

            if (pass.slot in BLUR_CONSUMERS && blurA != null && blurB != null) {
                val radius = blurRadiusOf(effect) * scale
                passes += GraphPass(
                    slot = pass.slot, shaderId = Shaders.BLUR.id, inputs = listOf(layer),
                    output = blurA, effect = effect,
                    floats = mapOf("uRadius" to radius),
                    vectors = mapOf("uDirection" to floatArrayOf(1f, 0f)),
                )
                passes += GraphPass(
                    slot = pass.slot, shaderId = Shaders.BLUR.id, inputs = listOf(blurA),
                    output = blurB, effect = effect,
                    floats = mapOf("uRadius" to radius),
                    vectors = mapOf("uDirection" to floatArrayOf(0f, 1f)),
                )
            }

            val inputs = buildList {
                add(layer)
                if (pass.slot in SDF_CONSUMERS && sdf != null) add(sdf)
                if (pass.slot in BLUR_CONSUMERS && blurB != null) add(blurB)
                if (pass.slot == PassSlot.BACKDROP && backdrop != null) add(backdrop)
            }
            passes += GraphPass(
                slot = pass.slot,
                shaderId = descriptor.shaderId,
                inputs = inputs,
                output = target,
                instanceCount = descriptor.instanceCount,
                effect = effect,
            )
        }

        return LayerGraph(
            buffers = buffers,
            passes = passes,
            textureBounds = bounds,
            readsBackdrop = plan.readsBackdrop,
            tiles = tiles,
        )
    }

    /**
     * Emits the seed, flood and resolve passes that build the shared distance field.
     *
     * The flood halves its stride each step, so the number of passes is logarithmic in the distance
     * that has to travel — and that distance is not the canvas, it is [reach]: how far the widest
     * stroke or bevel actually looks. A 4096 px tile floods in twelve passes if the whole texture
     * must be covered but in six if the widest consumer only reaches 40 px, which is the normal
     * case. Starting below the true reach is what would silently truncate the field, so the value is
     * rounded up to a power of two rather than down.
     */
    private fun buildDistanceField(
        buffers: (String, BufferRole, Int) -> String,
        passes: MutableList<GraphPass>,
        layer: String,
        width: Int,
        height: Int,
        reach: Float,
    ): String {
        val floodA = buffers("sdfFloodA", BufferRole.SDF_FLOOD, FLOOD_BYTES_PER_PIXEL)
        val floodB = buffers("sdfFloodB", BufferRole.SDF_FLOOD, FLOOD_BYTES_PER_PIXEL)
        val sdf = buffers("sdf", BufferRole.SDF, FLOOD_BYTES_PER_PIXEL)

        passes += GraphPass(PassSlot.PREPARE, Shaders.SDF_SEED.id, listOf(layer), floodA)

        val span = min(ceil(reach).toInt(), max(width, height)).coerceIn(1, MAX_FLOOD_STRIDE)
        // Computed by doubling rather than through a logarithm: at exact powers of two the
        // floating-point version lands a hair above the integer and doubles the pass count.
        var stride = 1
        while (stride < span) stride *= 2
        var source = floodA
        var destination = floodB
        while (stride >= 1) {
            passes += GraphPass(
                slot = PassSlot.PREPARE,
                shaderId = Shaders.SDF_FLOOD.id,
                inputs = listOf(source),
                output = destination,
                floats = mapOf("uStep" to stride.toFloat()),
            )
            val swap = source
            source = destination
            destination = swap
            stride /= 2
        }

        // `source` now names whichever ping-pong buffer the last step wrote.
        passes += GraphPass(PassSlot.PREPARE, Shaders.SDF_RESOLVE.id, listOf(layer, source), sdf)
        return sdf
    }

    /** How far from the edge any consumer of the field actually looks, in device pixels. */
    private fun sdfReach(plan: RenderPlan, scale: Float): Float {
        val fromEffects = plan.passes.mapNotNull { it.effect }.maxOfOrNull { effect ->
            when (effect) {
                is Effect.Stroke -> effect.width
                is Effect.Bevel -> effect.size + effect.soften
                is Effect.EdgeRoughen -> effect.amount
                else -> 0f
            }
        } ?: 0f
        return (max(fromEffects, plan.bleed.maxExtent) * scale).coerceAtLeast(MIN_SDF_REACH)
    }

    /**
     * Half float per channel.
     *
     * The flood stores offsets to the nearest seed, which stay small and so survive half float
     * exactly; absolute coordinates would not, and the resolved field is kept at the same width so a
     * document rendering at eight bits does not quantise its own outlines into steps.
     */
    private const val FLOOD_BYTES_PER_PIXEL = 8

    /** Below this the flood costs more in setup than it saves, and antialiasing needs a few pixels. */
    private const val MIN_SDF_REACH = 8f

    /** 8192 px of reach is already past any sane effect and past every mobile texture limit. */
    private const val MAX_FLOOD_STRIDE = 8192

    private fun blurRadiusOf(effect: Effect): Float = when (effect) {
        is Effect.DropShadow -> effect.blur
        is Effect.InnerShadow -> effect.blur
        is Effect.OuterGlow -> effect.blur
        is Effect.InnerGlow -> effect.blur
        is Effect.Satin -> effect.blur
        else -> 0f
    }

    /** Effects that read the distance field rather than deriving an edge themselves. */
    private val SDF_CONSUMERS = setOf(PassSlot.STROKE, PassSlot.BEVEL, PassSlot.POST)

    /** Effects that need a blurred copy of the layer alpha. */
    private val BLUR_CONSUMERS = setOf(
        PassSlot.DROP_SHADOW, PassSlot.OUTER_GLOW, PassSlot.INNER_GLOW,
        PassSlot.INNER_SHADOW, PassSlot.SATIN,
    )
}

/**
 * Whole-document memory budgeting.
 *
 * Export is where this matters: a 4K canvas at half float is 35 MB per buffer, and a style with a
 * ten-shadow stack allocates several at once. Deciding up front whether a render fits — and
 * lowering precision or tiling when it does not — is cheaper than discovering it as an out-of-memory
 * crash halfway through a save.
 */
object MemoryBudget {

    /**
     * @param availableBytes what the process may use; on Android derive it from
     *   `ActivityManager.getMemoryClass`, leaving headroom for the rest of the app
     */
    fun fits(graphs: List<LayerGraph>, canvas: CanvasSpec, color: ColorSettings, availableBytes: Long): Boolean =
        estimate(graphs, canvas, color) <= availableBytes

    /** Peak bytes: the composite target plus the most expensive single layer. */
    fun estimate(graphs: List<LayerGraph>, canvas: CanvasSpec, color: ColorSettings): Long {
        val composite = color.bufferBytes(canvas) * 2 // running target plus the backdrop copy
        val heaviestLayer = graphs.maxOfOrNull { it.peakBytes } ?: 0L
        return composite + heaviestLayer
    }

    /**
     * Suggests how to make an over-budget render fit.
     *
     * Dropping precision is offered before tiling because it is a single flag, while tiling changes
     * the shape of the whole pass; but precision loss is visible in gradients, so the caller decides.
     */
    fun advise(required: Long, available: Long, color: ColorSettings): List<String> = buildList {
        if (required <= available) return@buildList
        if (color.precision.bytesPerPixel > 4) {
            add("precision:U8")
        }
        add("tile")
        if (required > available * 4) add("reduce-resolution")
    }
}

/** Estimated cost of a style, used to warn before a stack stops being interactive. */
fun Style.renderCost(): Int = RenderPlanner.plan(this).estimatedCost
