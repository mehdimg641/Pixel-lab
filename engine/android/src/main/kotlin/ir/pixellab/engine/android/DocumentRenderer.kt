package ir.pixellab.engine.android

import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.render.ExecutionError
import ir.pixellab.core.render.GlDevice
import ir.pixellab.core.render.GraphExecutor
import ir.pixellab.core.render.LayerGraph
import ir.pixellab.core.render.MemoryBudget
import ir.pixellab.core.render.RenderGraphBuilder
import ir.pixellab.core.render.RenderPlanner
import ir.pixellab.core.render.TextureHandle

/** What a layer needs before its graph can run: where it sits, and the pixels of its silhouette. */
data class LayerContent(val bounds: Rect, val texture: TextureHandle, val revision: Int)

/**
 * Composites a whole document.
 *
 * The division of labour is the point: `core:render` decides *what* to draw, `AndroidGlDevice` knows
 * *how* to issue it, and this class only decides *when* — which layers need re-rasterising, which
 * order they composite in, and when a silhouette can be reused rather than rebuilt.
 *
 * Reuse is the difference between an editor that tracks the finger and one that does not. Dragging
 * a layer changes its transform, not its pixels: re-rasterising text on every frame of a drag would
 * re-shape a paragraph sixty times a second for no visible gain.
 */
class DocumentRenderer(
    private val device: GlDevice,
    private val text: TextRasterizer = TextRasterizer(),
    private val rasterizer: LayerRasterizer = LayerRasterizer(text),
    private val effectTextures: EffectTextures = EffectTextures(device, rasterizer),
) {
    private val executor = GraphExecutor(device, textures = effectTextures)
    private val content = HashMap<LayerId, LayerContent>()

    /** Bumped when a layer's *pixels* change, as opposed to where it sits. */
    private val revisions = HashMap<LayerId, Int>()

    var lastErrors: List<ExecutionError> = emptyList()
        private set

    /** Tells the renderer a layer's content changed, so its silhouette is rebuilt on the next frame. */
    fun invalidate(id: LayerId) {
        revisions[id] = (revisions[id] ?: 0) + 1
    }

    fun invalidateAll() {
        revisions.keys.toList().forEach(::invalidate)
        content.keys.toList().forEach { revisions[it] = (revisions[it] ?: 0) + 1 }
    }

    /**
     * Renders every layer of [document] into the bound target.
     *
     * @param scale export multiplier; 1 for the on-screen canvas
     * @param fonts resolved fonts by layer, since shaping is the caller's concern
     */
    fun render(
        document: Document,
        scale: Float = 1f,
        fonts: Map<LayerId, FontFile> = emptyMap(),
        effectsBypassed: Boolean = false,
    ) {
        val errors = ArrayList<ExecutionError>()
        for (layer in document.layers) {
            renderLayer(document, layer, scale, fonts, effectsBypassed, errors)
        }
        lastErrors = errors
    }

    /** Peak memory the current document would need, so a big export can be refused before it starts. */
    fun estimateBytes(document: Document, scale: Float = 1f): Long {
        val graphs = document.layers.mapNotNull { layer ->
            graphFor(document, layer, scale, effectsBypassed = false)
        }
        return MemoryBudget.estimate(graphs, document.canvas, document.color)
    }

    fun dispose() {
        content.values.forEach { device.deleteTexture(it.texture) }
        content.clear()
        effectTextures.dispose()
        executor.dispose()
    }

    private fun renderLayer(
        document: Document,
        layer: Layer,
        scale: Float,
        fonts: Map<LayerId, FontFile>,
        effectsBypassed: Boolean,
        errors: MutableList<ExecutionError>,
    ) {
        if (!layer.visible || layer.opacity <= 0f) return
        if (layer is Layer.Group) {
            // Pass-through groups composite their children straight onto the target; an isolating
            // group needs its own buffer and is a separate step.
            for (child in layer.children) {
                renderLayer(document, child, scale, fonts, effectsBypassed, errors)
            }
            return
        }

        val font = fonts[layer.id]
        val graph = graphFor(document, layer, scale, effectsBypassed, font) ?: return
        val silhouette = silhouetteFor(layer, graph.textureBounds, scale, font)

        effectTextures.layerFill = layer.style.fill
        val result = executor.execute(
            graph = graph,
            layerTexture = silhouette,
            scale = scale,
            globalLightAngle = document.globalLight.angle,
            color = document.color,
        )
        errors += result.errors
        result.output?.let(executor::recycle)
    }

    private fun graphFor(
        document: Document,
        layer: Layer,
        scale: Float,
        effectsBypassed: Boolean,
        font: FontFile? = null,
    ): LayerGraph? {
        val style = if (effectsBypassed) layer.style.copy(effects = emptyList()) else layer.style
        val shape = bounds(layer, font) ?: return null
        val plan = RenderPlanner.plan(style, document.globalLight.angle, shape.height)
        return RenderGraphBuilder.build(
            plan = plan,
            shapeBounds = shape,
            scale = scale,
            color = document.color,
            maxTextureSize = device.maxTextureSize,
        )
    }

    /**
     * Rasterises a layer's silhouette, or hands back the one from last frame.
     *
     * The revision — not the transform — is what invalidates it. Moving, scaling or rotating a layer
     * changes where its texture is sampled, never what is in it, so a drag costs no rasterising at
     * all. Keying on the transform instead is the obvious implementation and it re-shapes text on
     * every frame of every gesture.
     */
    private fun silhouetteFor(layer: Layer, bounds: Rect, scale: Float, font: FontFile?): TextureHandle {
        val revision = revisions.getOrPut(layer.id) { 0 }
        val cached = content[layer.id]
        if (cached != null && cached.revision == revision && cached.bounds == bounds) return cached.texture

        cached?.let { device.deleteTexture(it.texture) }
        val raster = rasterizer.silhouette(layer, bounds, scale, font)
        val handle = device.createTexture(raster.bitmap.width, raster.bitmap.height, bytesPerPixel = 4)
        val pixels = IntArray(raster.bitmap.width * raster.bitmap.height)
        raster.bitmap.getPixels(
            pixels, 0, raster.bitmap.width, 0, 0, raster.bitmap.width, raster.bitmap.height,
        )
        device.uploadArgb(handle, raster.bitmap.width, raster.bitmap.height, pixels)
        raster.bitmap.recycle()

        content[layer.id] = LayerContent(bounds, handle, revision)
        return handle
    }

    /**
     * Where a layer's content sits before its transform.
     *
     * Text has to be shaped to be measured, so the answer is cached against the same revision the
     * silhouette uses — otherwise laying out a paragraph would happen twice per frame, once to find
     * the bounds and once to draw into them.
     */
    fun bounds(layer: Layer, font: FontFile? = null): Rect? = when (layer) {
        is Layer.Shape -> Rect.of(sizeOf(layer))
        is Layer.Text -> {
            val revision = revisions.getOrPut(layer.id) { 0 }
            val cached = measured[layer.id]
            if (cached != null && cached.first == revision) {
                cached.second
            } else {
                val box = font?.let {
                    val measured = text.rasterize(layer.spec, it).bounds
                    Rect(measured.left, measured.top, measured.right, measured.bottom)
                } ?: Rect.of(UNMEASURED_TEXT)
                measured[layer.id] = revision to box
                box
            }
        }
        // An image's extent comes from its decoded asset and a group's from its children; neither
        // is this class's to invent.
        else -> null
    }

    private val measured = HashMap<LayerId, Pair<Int, Rect>>()

    private fun sizeOf(shape: Layer.Shape) = when (val geometry = shape.geometry) {
        is ShapeGeometry.Rectangle -> geometry.size
        is ShapeGeometry.Ellipse -> geometry.size
        is ShapeGeometry.Polygon -> geometry.size
        is ShapeGeometry.Star -> geometry.size
        is ShapeGeometry.Line -> Vec2(
            kotlin.math.abs(geometry.to.x - geometry.from.x),
            kotlin.math.abs(geometry.to.y - geometry.from.y),
        )
        is ShapeGeometry.Arrow -> Vec2(
            kotlin.math.abs(geometry.to.x - geometry.from.x) + geometry.headSize,
            kotlin.math.abs(geometry.to.y - geometry.from.y) + geometry.headSize + kotlin.math.abs(geometry.bend),
        )
        is ShapeGeometry.Path -> geometry.contours
            .flatMap { it.nodes }
            .fold(null as Vec2?) { acc, node ->
                Vec2(maxOf(acc?.x ?: 0f, node.point.x), maxOf(acc?.y ?: 0f, node.point.y))
            } ?: UNMEASURED_TEXT
    }

    private companion object {
        /** Stand-in extent when a text layer has no resolved font yet, so it still selects. */
        val UNMEASURED_TEXT = Vec2(320f, 160f)
    }
}
