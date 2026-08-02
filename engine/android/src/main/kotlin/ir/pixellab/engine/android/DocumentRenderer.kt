package ir.pixellab.engine.android

import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.canvas.Viewport
import ir.pixellab.core.render.Affine
import ir.pixellab.core.render.BlendShaders
import ir.pixellab.core.render.Compositing
import ir.pixellab.core.render.ExecutionError
import ir.pixellab.core.render.GlDevice
import ir.pixellab.core.render.GraphExecutor
import ir.pixellab.core.render.LayerGraph
import ir.pixellab.core.render.MemoryBudget
import ir.pixellab.core.render.RenderGraphBuilder
import ir.pixellab.core.render.RenderPlanner
import ir.pixellab.core.render.Shaders
import ir.pixellab.core.render.TextureHandle

/**
 * What a layer needs before its graph can run: where it sits, and the pixels of its silhouette.
 *
 * [key] is what the silhouette was drawn *from* — the geometry, or the string and the font. Caching
 * on the revision alone means an edit that leaves the bounds unchanged, such as swapping to a font
 * of the same width, keeps the old pixels and looks like the change was ignored.
 */
data class LayerContent(val bounds: Rect, val texture: TextureHandle, val revision: Int, val key: Int)

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
/**
 * Supplies the font a text layer should be shaped with.
 *
 * A function rather than a prepared map: the renderer is the only thing that knows which layers it
 * is about to draw, and building a map for the whole document means resolving fonts for layers that
 * are hidden, off-canvas or unchanged since the last frame.
 */
fun interface FontResolver {
    fun resolve(ref: ir.pixellab.core.model.FontRef): FontFile?

    companion object {
        /** Resolves nothing; text layers then measure to their placeholder box and draw blank. */
        val NONE = FontResolver { null }

        /**
         * A resolver over a finished catalogue.
         *
         * Immutable on purpose. The canvas decides a rescan happened by comparing resolver
         * identity, so a resolver that mutated in place would leave every text layer holding the
         * substitute it was drawn with — which looks exactly like the newly added font being
         * ignored.
         */
        fun of(catalog: ir.pixellab.core.fonts.FontCatalog) =
            FontResolver { ref -> catalog.resolve(ref).file }
    }
}

class DocumentRenderer(
    private val device: GlDevice,
    private val text: TextRasterizer = TextRasterizer(),
    private val rasterizer: LayerRasterizer = LayerRasterizer(text),
    private val effectTextures: EffectTextures = EffectTextures(device, rasterizer),
    private var fonts: FontResolver = FontResolver.NONE,
) {

    /**
     * Replaces the font source and invalidates every text layer.
     *
     * Adding a font mid-session is normal in this app. Without the invalidation the layers that
     * were drawn with a substitute keep their old pixels, and the newly added font appears to have
     * been ignored.
     */
    fun setFonts(resolver: FontResolver) {
        fonts = resolver
        content.keys.toList().forEach(::invalidate)
        measured.clear()
    }
    private val executor = GraphExecutor(device, textures = effectTextures)
    private val content = HashMap<LayerId, LayerContent>()

    /**
     * Two canvas-sized buffers, swapped after every layer.
     *
     * A layer has to *read* what is beneath it: the four non-separable blend modes are functions of
     * the destination, and frosted glass samples it outright. Fixed-function blending cannot express
     * either, so the composite reads one buffer and writes the other.
     */
    private var canvasFront: TextureHandle? = null
    private var canvasBack: TextureHandle? = null
    private var canvasSize = Vec2.ZERO

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
        effectsBypassed: Boolean = false,
        viewport: Viewport? = null,
    ) {
        val errors = ArrayList<ExecutionError>()
        val width = kotlin.math.ceil(document.canvas.width * scale).toInt().coerceAtLeast(1)
        val height = kotlin.math.ceil(document.canvas.height * scale).toInt().coerceAtLeast(1)
        prepareCanvas(width, height, document.color.precision.bytesPerPixel)

        device.bindTarget(canvasFront)
        device.clearTarget()
        for (layer in document.layers) {
            renderLayer(document, layer, scale, effectsBypassed, errors)
        }

        // The camera applies once, here, rather than to every layer: panning re-runs one textured
        // quad instead of every effect stack, which is what keeps the canvas under the finger.
        present(document, viewport)
        lastErrors = errors
    }

    /**
     * Draws the finished canvas to whatever target is bound after this call returns to the caller.
     *
     * Separated from [render] only so an export can skip it — a saved file wants the canvas itself,
     * not the canvas as the user happens to be looking at it.
     */
    fun present(document: Document, viewport: Viewport?) = present(document, viewport, target = null)

    /**
     * Copies the finished canvas into [target], for an export that must not go to the screen.
     *
     * The camera is deliberately absent: a saved file is the artwork, not the artwork as the user
     * happens to be looking at it.
     */
    fun blitTo(document: Document, target: TextureHandle) = present(document, viewport = null, target = target)

    private fun present(document: Document, viewport: Viewport?, target: TextureHandle?) {
        val canvas = canvasFront ?: return
        device.bindTarget(target)
        device.setBlend(false)
        if (!device.useProgram(Shaders.PRESENT.id)) return
        device.bindInput("uSource", canvas)

        val map = if (viewport == null || viewport.screenSize == Vec2.ZERO) {
            Affine.IDENTITY
        } else {
            Compositing.screenUvToCanvasUv(
                screenSize = viewport.screenSize,
                canvasSize = document.canvas.size,
                offset = viewport.offset,
                zoom = viewport.zoom,
                rotation = viewport.rotation,
            )
        }
        device.setMat3("uMap", map.values)
        device.setVec2("uTexelSize", 1f / document.canvas.width, 1f / document.canvas.height)
        // Neutral grey around the artboard, matching the interface chrome; a tint here would shift
        // how the artwork's own colours read.
        device.setVec4("uSurround", SURROUND_GREY, SURROUND_GREY, SURROUND_GREY, 1f)
        device.draw(1)
    }

    /** Allocates or resizes the composite buffers. */
    private fun prepareCanvas(width: Int, height: Int, bytesPerPixel: Int) {
        val size = Vec2(width.toFloat(), height.toFloat())
        if (canvasFront != null && canvasSize == size) return
        canvasFront?.let(device::deleteTexture)
        canvasBack?.let(device::deleteTexture)
        canvasFront = device.createTexture(width, height, bytesPerPixel)
        canvasBack = device.createTexture(width, height, bytesPerPixel)
        canvasSize = size
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
        canvasFront?.let(device::deleteTexture)
        canvasBack?.let(device::deleteTexture)
        canvasFront = null
        canvasBack = null
        canvasSize = Vec2.ZERO
        effectTextures.dispose()
        executor.dispose()
    }

    private fun renderLayer(
        document: Document,
        layer: Layer,
        scale: Float,
        effectsBypassed: Boolean,
        errors: MutableList<ExecutionError>,
    ) {
        if (!layer.visible || layer.opacity <= 0f) return
        if (layer is Layer.Group) {
            // Pass-through groups composite their children straight onto the target; an isolating
            // group needs its own buffer and is a separate step.
            for (child in layer.children) {
                renderLayer(document, child, scale, effectsBypassed, errors)
            }
            return
        }

        val font = fontFor(layer)
        val graph = graphFor(document, layer, scale, effectsBypassed, font) ?: return
        val silhouette = silhouetteFor(layer, graph.textureBounds, scale, font)

        effectTextures.layerFill = layer.style.fill
        val result = executor.execute(
            graph = graph,
            layerTexture = silhouette,
            // Frosted glass samples what is already composited beneath, which is exactly the
            // buffer being read from this frame.
            backdropTexture = canvasFront,
            scale = scale,
            globalLightAngle = document.globalLight.angle,
            color = document.color,
        )
        errors += result.errors

        val appearance = result.output
        if (appearance == null) {
            errors += ExecutionError(Shaders.COMPOSITE.id, "layer '${layer.name}' produced nothing")
            return
        }
        composite(document, layer, graph.textureBounds, appearance, scale, errors)
        executor.recycle(appearance)
    }

    /**
     * Draws a finished layer onto the canvas.
     *
     * The step that carries the three things a layer has that its effects do not: where it sits,
     * how opaque it is, and how it blends. Without it every layer renders correctly into a buffer
     * that is then thrown away, which looks exactly like a renderer that does nothing at all.
     */
    private fun composite(
        document: Document,
        layer: Layer,
        textureBounds: Rect,
        appearance: TextureHandle,
        scale: Float,
        errors: MutableList<ExecutionError>,
    ) {
        val front = canvasFront ?: return
        val back = canvasBack ?: return
        if (!device.useProgram(Shaders.COMPOSITE.id)) {
            errors += ExecutionError(Shaders.COMPOSITE.id, "composite program unavailable")
            return
        }

        device.bindTarget(back)
        // The blend is done in the shader, so fixed-function blending has to be off or it would
        // be applied a second time on top.
        device.setBlend(false)
        device.bindInput("uSource", appearance)
        device.bindInput("uBackdrop", front)
        device.setMat3(
            "uMap",
            Compositing.canvasUvToLayerUv(
                canvasSize = document.canvas.size * scale,
                textureBounds = textureBounds,
                transform = layer.transform,
            ).values,
        )
        device.setFloat("uOpacity", layer.opacity)
        device.setInt("uBlendMode", BlendShaders.uniformValue(layer.blendMode))
        device.setVec2("uTexelSize", 1f / canvasSize.x, 1f / canvasSize.y)
        device.draw(1)

        // What was just written becomes what the next layer reads.
        canvasFront = back
        canvasBack = front
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
        val key = contentKey(layer, font)
        val cached = content[layer.id]
        if (cached != null && cached.revision == revision && cached.bounds == bounds && cached.key == key) {
            return cached.texture
        }

        cached?.let { device.deleteTexture(it.texture) }
        val raster = rasterizer.silhouette(layer, bounds, scale, font)
        val handle = device.createTexture(raster.bitmap.width, raster.bitmap.height, bytesPerPixel = 4)
        val pixels = IntArray(raster.bitmap.width * raster.bitmap.height)
        raster.bitmap.getPixels(
            pixels, 0, raster.bitmap.width, 0, 0, raster.bitmap.width, raster.bitmap.height,
        )
        device.uploadArgb(handle, raster.bitmap.width, raster.bitmap.height, pixels)
        raster.bitmap.recycle()

        content[layer.id] = LayerContent(bounds, handle, revision, key)
        return handle
    }

    /**
     * Everything the silhouette is drawn from, collapsed to one value.
     *
     * A group and an image have no silhouette of their own, so they have nothing to key on.
     */
    private fun contentKey(layer: Layer, font: FontFile?): Int = when (layer) {
        is Layer.Shape -> layer.geometry.hashCode()
        is Layer.Text -> 31 * layer.spec.hashCode() + (font?.path?.hashCode() ?: 0)
        else -> 0
    }

    /**
     * Where a layer's content sits before its transform.
     *
     * Text has to be shaped to be measured, so the answer is cached against the same revision the
     * silhouette uses — otherwise laying out a paragraph would happen twice per frame, once to find
     * the bounds and once to draw into them.
     */
    /** The font a text layer shapes with, or null for every other kind. */
    private fun fontFor(layer: Layer): FontFile? =
        (layer as? Layer.Text)?.let { fonts.resolve(it.spec.font) }

    fun bounds(layer: Layer, font: FontFile? = null): Rect? = when (layer) {
        is Layer.Shape -> Rect.of(sizeOf(layer))
        is Layer.Text -> {
            val key = contentKey(layer, font)
            val cached = measured[layer.id]
            if (cached != null && cached.first == key) {
                cached.second
            } else {
                val box = font?.let {
                    val measured = text.rasterize(layer.spec, it).bounds
                    Rect(measured.left, measured.top, measured.right, measured.bottom)
                } ?: Rect.of(UNMEASURED_TEXT)
                measured[layer.id] = key to box
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

        /** 0x2A as a linear fraction — the same neutral grey the Compose chrome uses. */
        const val SURROUND_GREY = 42f / 255f
    }
}
