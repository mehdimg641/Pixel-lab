package ir.pixellab.core.render

import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.ColorSettings
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Style
import kotlin.math.ceil
import kotlin.math.min

/** What a buffer is for, which decides how aggressively it can be recycled. */
enum class BufferRole {
    /** The layer's own rasterised content. */
    LAYER,

    /** Signed distance field of the layer alpha, shared by stroke, shadow, glow and bevel. */
    SDF,

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
}

/** One executable step: a shader, its inputs, and where it writes. */
data class GraphPass(
    val slot: PassSlot,
    val shaderId: String,
    val inputs: List<String>,
    val output: String,
    val instanceCount: Int = 1,
    val effect: Effect? = null,
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
    /** Peak memory, assuming buffers of the same role are recycled between passes. */
    val peakBytes: Long
        get() = buffers.groupBy { it.role }.values.sumOf { group -> group.maxOf { it.bytes } }

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

        fun buffer(id: String, role: BufferRole): String {
            buffers += BufferSpec(id, role, tileWidth, tileHeight, bpp)
            return id
        }

        val layer = buffer("layer", BufferRole.LAYER)
        val target = buffer("target", BufferRole.TARGET)

        // Anything that reasons about the silhouette shares one distance field.
        val needsSdf = plan.passes.any { it.slot in SDF_CONSUMERS }
        val sdf = if (needsSdf) {
            val id = buffer("sdf", BufferRole.SDF)
            passes += GraphPass(PassSlot.PREPARE, Shaders.SDF.id, listOf(layer), id)
            id
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
                passes += GraphPass(PassSlot.FILL, "fill", listOf(layer), target)
                continue
            }
            val effect = pass.effect ?: continue
            val module = builtinEffectRegistry.moduleFor(effect) ?: continue
            val descriptor = module.describe(effect, RenderContext(scale = scale))

            if (pass.slot in BLUR_CONSUMERS && blurA != null && blurB != null) {
                passes += GraphPass(pass.slot, Shaders.BLUR.id, listOf(layer), blurA, effect = effect)
                passes += GraphPass(pass.slot, Shaders.BLUR.id, listOf(blurA), blurB, effect = effect)
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
