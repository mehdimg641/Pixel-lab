package ir.pixellab.engine.android

import ir.pixellab.core.canvas.Viewport
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.LayerMask
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.VectorMask
import ir.pixellab.core.model.with
import ir.pixellab.core.model.withStyle
import ir.pixellab.core.render.Affine
import ir.pixellab.core.render.AdjustmentUniforms
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

/**
 * Where a mask's pixels come from.
 *
 * A mask is stored in the document as an asset id rather than as pixels, because a mask is often
 * the largest thing in a project and the same one is often shared between layers. The store that
 * owns decoded assets supplies them through this.
 */
fun interface AssetSource {
    /** Straight ARGB pixels, or null when the asset has not been loaded. */
    fun load(id: ir.pixellab.core.model.AssetId): ir.pixellab.core.codec.RasterImage?

    companion object {
        val NONE = AssetSource { null }
    }
}

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

    /** Where masks and patterns come from. Replacing it drops every cached mask texture. */
    var assets: AssetSource = AssetSource.NONE
        set(value) {
            if (value === field) return
            field = value
            clearMasks()
            imageTextures.values.forEach(device::deleteTexture)
            imageTextures.clear()
            imageBitmaps.clear()
        }

    private val executor = GraphExecutor(device, textures = effectTextures)
    private val content = HashMap<LayerId, LayerContent>()

    /**
     * A pair of buffers a stack of layers composites into, swapped after every layer.
     *
     * A layer has to *read* what is beneath it: the four non-separable blend modes are functions of
     * the destination, and frosted glass samples it outright. Fixed-function blending cannot express
     * either, so the composite reads one buffer and writes the other.
     *
     * There is one of these per isolation level rather than one per document. An isolating group,
     * and every clipping group, needs its children composited among themselves before the result
     * meets the backdrop — which is precisely a second pair.
     */
    private class Surface(val front: TextureHandle, val back: TextureHandle) {
        /** Which of the two currently holds the result. */
        var flipped = false

        val read: TextureHandle get() = if (flipped) back else front
        val write: TextureHandle get() = if (flipped) front else back

        fun swap() {
            flipped = !flipped
        }
    }

    private var canvas: Surface? = null
    private var canvasSize = Vec2.ZERO
    private var canvasBytesPerPixel = 4

    /**
     * Spare canvas-sized surfaces, reused across frames.
     *
     * Allocating a pair per isolating group per frame is two texture allocations and two clears at
     * sixty hertz; a design with nested groups would spend more time allocating than drawing.
     */
    private val freeSurfaces = ArrayDeque<Surface>()
    private val freeTextures = ArrayDeque<TextureHandle>()
    private val liveSurfaces = ArrayList<Surface>()
    private val liveTextures = ArrayList<TextureHandle>()

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
     * Everything one frame needs that does not change inside it.
     *
     * Threaded through rather than held in fields because the walk is recursive — groups nest, and
     * an instance re-enters the walk somewhere else in the tree entirely.
     */
    private class Frame(
        val document: Document,
        val scale: Float,
        val effectsBypassed: Boolean,
        val errors: MutableList<ExecutionError>,
    ) {
        /** Sources currently being rendered, so an instance pointing at its own ancestor stops. */
        val instances = ArrayList<LayerId>()

        val bytesPerPixel: Int get() = document.color.precision.bytesPerPixel

        /** The artboard in canvas units, which is the extent of every isolated buffer. */
        val canvasRect: Rect get() = Rect(0f, 0f, document.canvas.width.toFloat(), document.canvas.height.toFloat())
    }

    /**
     * Renders every layer of [document] into the bound target.
     *
     * @param scale export multiplier; 1 for the on-screen canvas
     */
    fun render(
        document: Document,
        scale: Float = 1f,
        effectsBypassed: Boolean = false,
        viewport: Viewport? = null,
    ) {
        val frame = Frame(document, scale, effectsBypassed, ArrayList())
        val width = kotlin.math.ceil(document.canvas.width * scale).toInt().coerceAtLeast(1)
        val height = kotlin.math.ceil(document.canvas.height * scale).toInt().coerceAtLeast(1)
        prepareCanvas(width, height, frame.bytesPerPixel)

        val surface = canvas ?: return
        surface.flipped = false
        device.bindTarget(surface.read)
        device.clearTarget()
        renderStack(frame, document.layers, surface)

        // The camera applies once, here, rather than to every layer: panning re-runs one textured
        // quad instead of every effect stack, which is what keeps the canvas under the finger.
        present(document, viewport)
        lastErrors = frame.errors
        recycleAll()
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
        val source = canvas?.read ?: return
        device.bindTarget(target)
        device.setBlend(false)
        if (!device.useProgram(Shaders.PRESENT.id)) return
        device.bindInput("uSource", source)

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
        if (canvas != null && canvasSize == size && canvasBytesPerPixel == bytesPerPixel) return
        canvas?.let { device.deleteTexture(it.front); device.deleteTexture(it.back) }
        // Every spare surface is the old size, so keeping them would hand a group a buffer that
        // does not line up with the canvas it composites onto.
        discardPools()
        canvas = Surface(
            device.createTexture(width, height, bytesPerPixel),
            device.createTexture(width, height, bytesPerPixel),
        )
        canvasSize = size
        canvasBytesPerPixel = bytesPerPixel
    }

    /**
     * Peak memory the current document would need, so a big export can be refused before it starts.
     *
     * Isolation is counted: every nested isolating or clipping group holds a pair of canvas-sized
     * buffers plus a clip source for as long as its children are being drawn, and on a large export
     * those dominate everything the effect graphs ask for.
     */
    fun estimateBytes(document: Document, scale: Float = 1f): Long {
        val graphs = document.walk().mapNotNull { layer ->
            graphFor(document, layer, scale, effectsBypassed = false)
        }.toList()
        val width = kotlin.math.ceil(document.canvas.width * scale).toDouble()
        val height = kotlin.math.ceil(document.canvas.height * scale).toDouble()
        val buffer = (width * height * document.color.precision.bytesPerPixel).toLong()
        val isolation = buffer * SURFACE_BUFFERS * isolationDepth(document.layers)
        return MemoryBudget.estimate(graphs, document.canvas, document.color) + isolation
    }

    /** How many isolated surfaces can be live at once, which is the deepest nesting in the tree. */
    private fun isolationDepth(layers: List<Layer>): Int {
        var deepest = 0
        var index = 0
        while (index < layers.size) {
            val layer = layers[index]
            var next = index + 1
            while (next < layers.size && layers[next].clipped) next++
            val clips = next > index + 1

            val inner = if (layer is Layer.Group) {
                isolationDepth(layer.children) + if (isolating(layer)) 1 else 0
            } else {
                0
            }
            deepest = maxOf(deepest, inner + if (clips) 1 else 0)
            index = next
        }
        return deepest
    }

    fun dispose() {
        content.values.forEach { device.deleteTexture(it.texture) }
        content.clear()
        canvas?.let { device.deleteTexture(it.front); device.deleteTexture(it.back) }
        canvas = null
        canvasSize = Vec2.ZERO
        discardPools()
        clearMasks()
        adjustmentTables.values.forEach(device::deleteTexture)
        adjustmentTables.clear()
        imageTextures.values.forEach(device::deleteTexture)
        imageTextures.clear()
        imageBitmaps.clear()
        whiteTexture?.let(device::deleteTexture)
        whiteTexture = null
        effectTextures.dispose()
        executor.dispose()
    }

    // ---- the walk --------------------------------------------------------------------------

    /**
     * Renders one stack of siblings, honouring clipping groups.
     *
     * A clipping group is a run in the list rather than a nesting in the tree: a layer marked
     * clipped attaches to the first unclipped layer *below* it, and every clipped layer above that
     * one joins the same group. Walking the list one layer at a time — the obvious implementation —
     * cannot express that, because the base has to be rendered before its followers and then
     * composited after them.
     */
    private fun renderStack(frame: Frame, layers: List<Layer>, surface: Surface) {
        var index = 0
        while (index < layers.size) {
            val base = layers[index]
            var next = index + 1
            while (next < layers.size && layers[next].clipped) next++

            if (next == index + 1) {
                renderLayer(frame, base, surface, clip = null)
            } else {
                renderClippingGroup(frame, base, layers.subList(index + 1, next), surface)
            }
            index = next
        }
    }

    /**
     * Photoshop's clipping group: a base and everything clipped to it.
     *
     * Rendered as its own unit, which is what "blend clipped layers as group" means and what
     * Photoshop does by default. The alternative — clipping each follower straight onto the canvas
     * with the base's alpha as an extra mask — gives a different picture as soon as the base has a
     * blend mode or an opacity below full, because the followers would then blend with the document
     * instead of with the base.
     *
     * The base's own alpha is copied aside first. It is what the followers are clipped to, and it
     * would otherwise be overwritten by the first follower composited on top of it.
     */
    private fun renderClippingGroup(frame: Frame, base: Layer, clipped: List<Layer>, surface: Surface) {
        // A hidden base hides the whole group; the followers have nothing to be clipped to.
        if (!base.visible || base.opacity <= 0f) return

        val isolated = obtainSurface(frame.bytesPerPixel)
        // The base goes in at full strength: its opacity and blend belong to the finished group,
        // not to the pixels its followers are clipped against.
        renderLayer(frame, base.with(opacity = 1f, blendMode = BlendMode.NORMAL), isolated, clip = null)

        val clipSource = obtainTexture(frame.bytesPerPixel)
        copy(isolated.read, clipSource)

        for (layer in clipped) {
            renderLayer(frame, layer.with(clipped = false), isolated, clip = clipSource)
        }

        compositeTexture(
            frame = frame,
            source = isolated.read,
            textureBounds = frame.canvasRect,
            transform = Transform.IDENTITY,
            opacity = base.opacity,
            blendMode = base.blendMode,
            mask = null,
            clip = null,
            surface = surface,
        )
    }

    private fun renderLayer(frame: Frame, layer: Layer, surface: Surface, clip: TextureHandle?) {
        if (!layer.visible || layer.opacity <= 0f) return
        when (layer) {
            is Layer.Group -> renderGroup(frame, layer, surface, clip)
            is Layer.Instance -> renderInstance(frame, layer, surface, clip)
            is Layer.AdjustmentLayer -> renderAdjustment(frame, layer, surface, clip)
            else -> renderContent(frame, layer, surface, clip)
        }
    }

    /**
     * A group.
     *
     * Pass-through is not a style, it is the absence of isolation: the children composite straight
     * onto whatever is beneath the group, so a Multiply child multiplies with the document. The
     * moment the group has a blend mode, an opacity, a mask or an effect of its own, its children
     * have to be flattened among themselves first — otherwise the group's opacity would be applied
     * to each child separately and overlapping children would show through one another.
     */
    private fun renderGroup(frame: Frame, group: Layer.Group, surface: Surface, clip: TextureHandle?) {
        if (!isolating(group) && clip == null) {
            renderStack(frame, group.children, surface)
            return
        }

        val isolated = obtainSurface(frame.bytesPerPixel)
        renderStack(frame, group.children, isolated)

        // The group's own effects apply to the flattened result, over the whole artboard: a drop
        // shadow on a group is cast by the group's silhouette, not by each child's.
        val flattened = groupAppearance(frame, group, isolated.read)

        compositeTexture(
            frame = frame,
            source = flattened,
            textureBounds = frame.canvasRect,
            // A group's transform applies to the flattened artboard, so it pivots on the artboard
            // rather than on the group's content. Photoshop has no group transform at all; this is
            // the interpretation that keeps a transformed group predictable.
            transform = group.transform,
            opacity = group.opacity,
            blendMode = group.blendMode,
            mask = maskFor(frame, group, frame.canvasRect),
            clip = clip,
            surface = surface,
        )
    }

    private fun groupAppearance(frame: Frame, group: Layer.Group, flattened: TextureHandle): TextureHandle {
        if (frame.effectsBypassed || group.style.activeEffects.isEmpty()) return flattened
        val plan = RenderPlanner.plan(group.style, frame.document.globalLight.angle, frame.canvasRect.height)
        val graph = RenderGraphBuilder.build(
            plan = plan,
            shapeBounds = frame.canvasRect,
            scale = frame.scale,
            color = frame.document.color,
            maxTextureSize = device.maxTextureSize,
        )
        effectTextures.layerFill = group.style.fill
        val result = executor.execute(
            graph = graph,
            layerTexture = flattened,
            backdropTexture = canvas?.read,
            scale = frame.scale,
            globalLightAngle = frame.document.globalLight.angle,
            color = frame.document.color,
        )
        frame.errors += result.errors
        return result.output ?: flattened
    }

    /**
     * A smart object: a live reference to another layer.
     *
     * Rendered by borrowing the source's content and overriding what the instance owns — where it
     * sits, how opaque it is, how it blends, its masks and its effects. Deliberately *not* a copy:
     * the silhouette cache is keyed on the source's id, so twenty-nine instances of one shape
     * rasterise once between them. That is the whole reason the reference PSDs are built this way.
     */
    private fun renderInstance(frame: Frame, instance: Layer.Instance, surface: Surface, clip: TextureHandle?) {
        if (instance.source in frame.instances) {
            // An instance whose source contains it would otherwise recurse until the stack ends.
            frame.errors += ExecutionError(
                Shaders.COMPOSITE.id,
                "'${instance.name}' refers to a layer that contains it",
            )
            return
        }
        val source = frame.document.findLayer(instance.source)
        if (source == null) {
            frame.errors += ExecutionError(Shaders.COMPOSITE.id, "'${instance.name}' has no source layer")
            return
        }

        val effective = source
            .with(
                transform = instance.transform,
                opacity = instance.opacity,
                blendMode = instance.blendMode,
                visible = true,
                mask = instance.mask ?: source.mask,
                vectorMask = instance.vectorMask ?: source.vectorMask,
                clipped = false,
            )
            // An instance with no effects of its own shows the source's; one with effects replaces
            // them, which is how a single shape becomes twenty-nine differently-lit copies.
            .let { if (instance.style.activeEffects.isEmpty()) it else it.withStyle(instance.style) }

        frame.instances += instance.source
        renderLayer(frame, effective, surface, clip)
        frame.instances.removeAt(frame.instances.lastIndex)
    }

    /**
     * A colour correction over everything beneath it.
     *
     * An adjustment layer adds no pixels of its own: it re-reads what is already composited, writes
     * a corrected copy, and that copy is then blended back through the ordinary composite path with
     * the layer's own opacity, blend mode, mask and clip. Correcting the buffer in place would be
     * simpler and would make a half-opacity Curves layer mean "half the picture" rather than "half
     * the correction", and would leave its mask with nothing to modulate.
     */
    private fun renderAdjustment(
        frame: Frame,
        layer: Layer.AdjustmentLayer,
        surface: Surface,
        clip: TextureHandle?,
    ) {
        val uniforms = AdjustmentUniforms.of(layer.adjustment)

        // Base layers first, because they run their own programs and would otherwise unbind this
        // one. Two of the twenty-two corrections read the neighbourhood rather than the pixel, and
        // these are what they read: Shadows/Highlights wants a plain blur at each of its two radii,
        // and HDR Toning's local adaptation wants an edge-following one.
        val bases = baseLayers(uniforms, surface.read)

        if (!device.useProgram(Shaders.ADJUST.id)) {
            frame.errors += ExecutionError(Shaders.ADJUST.id, "adjustment program unavailable")
            bases.release()
            return
        }
        val corrected = obtainTexture(frame.bytesPerPixel)

        device.bindTarget(corrected)
        device.setBlend(false)
        device.bindInput("uSource", surface.read)
        // Every sampler is bound whether the branch reads it or not: an unbound sampler in GL ES
        // reads texture unit zero, which holds whatever the previous draw left there.
        val white = whiteTexture ?: solidWhite().also { whiteTexture = it }
        device.bindInput("uCurves", if (uniforms.needsCurves) curveTable(layer.adjustment) ?: white else white)
        device.bindInput("uRamp", if (uniforms.needsRamp) rampTable(layer.adjustment) ?: white else white)
        device.bindInput("uLut", if (uniforms.needsLut) lutTable(layer.adjustment) ?: white else white)
        device.bindInput("uLocal", bases.first ?: white)
        device.bindInput("uLocal2", bases.second ?: bases.first ?: white)
        device.setInt("uMode", uniforms.mode.ordinal)
        device.setVec4("uP0", uniforms.p0[0], uniforms.p0[1], uniforms.p0[2], uniforms.p0[3])
        device.setVec4("uP1", uniforms.p1[0], uniforms.p1[1], uniforms.p1[2], uniforms.p1[3])
        device.setVec4("uP2", uniforms.p2[0], uniforms.p2[1], uniforms.p2[2], uniforms.p2[3])
        device.setVec2("uTexelSize", 1f / canvasSize.x, 1f / canvasSize.y)
        device.draw(1)
        bases.release()

        compositeTexture(
            frame = frame,
            source = corrected,
            textureBounds = frame.canvasRect,
            transform = Transform.IDENTITY,
            opacity = layer.opacity,
            blendMode = layer.blendMode,
            mask = maskFor(frame, layer, frame.canvasRect),
            clip = clip,
            surface = surface,
        )
    }

    // ---- adjustment base layers --------------------------------------------------------------

    /**
     * The blurred copies of the backdrop that a neighbourhood correction reads.
     *
     * Photoshop refuses to offer either of these two as an adjustment layer, and this is the reason:
     * the answer at a pixel depends on pixels a radius away, which its per-pixel adjustment pipeline
     * cannot supply. Ours can, because a blur is a pass, and being non-destructive is worth the pass.
     *
     * The textures are created rather than pooled because their format is not the canvas's: the
     * guided filter subtracts a mean squared from a squared mean, and eight bits per channel would
     * leave that difference in the noise. They are deleted the moment the draw that reads them is
     * issued.
     */
    private fun baseLayers(uniforms: AdjustmentUniforms, source: TextureHandle): BaseLayers {
        if (uniforms.localRadius <= 0f) return BaseLayers(null, null, device)
        val width = canvasSize.x.toInt()
        val height = canvasSize.y.toInt()

        if (uniforms.mode == ir.pixellab.core.render.AdjustmentMode.HDR_TONING) {
            return BaseLayers(guidedBase(source, width, height, uniforms.localRadius), null, device)
        }

        val first = blurredCopy(source, width, height, uniforms.localRadius, BASE_BYTES)
        // The two radii are usually left equal, and one blur is worth the comparison to find out.
        val second =
            if (uniforms.localRadius2 == uniforms.localRadius) null
            else blurredCopy(source, width, height, uniforms.localRadius2, BASE_BYTES)
        return BaseLayers(first, second, device)
    }

    /**
     * A guided filter's per-window line, ready for the adjustment branch to evaluate.
     *
     * Three programs would be the obvious shape — seed, coefficients, resolve — and the third is not
     * needed: `a * guide + b` is a multiply-add on values the adjustment shader already has, so it
     * happens inside the branch that wants the answer.
     *
     * A Gaussian window rather than the literature's box. It is the same integral to within a
     * constant and it does not leave the square footprint of a box blur faintly visible in the base,
     * which a large radius over a smooth sky otherwise does.
     */
    private fun guidedBase(source: TextureHandle, width: Int, height: Int, radius: Float): TextureHandle? {
        if (!device.useProgram(Shaders.HDR_BASE_SEED.id)) return null
        val seed = device.createTexture(width, height, BASE_BYTES)
        device.bindTarget(seed)
        device.setBlend(false)
        device.bindInput("uSource", source)
        device.setVec2("uTexelSize", 1f / width, 1f / height)
        device.draw(1)

        val means = blurredCopy(seed, width, height, radius, BASE_BYTES)
        device.deleteTexture(seed)
        if (means == null) return null

        if (!device.useProgram(Shaders.HDR_BASE_COEFF.id)) {
            device.deleteTexture(means)
            return null
        }
        val coefficients = device.createTexture(width, height, BASE_BYTES)
        device.bindTarget(coefficients)
        device.setBlend(false)
        device.bindInput("uSource", means)
        device.setVec2("uTexelSize", 1f / width, 1f / height)
        device.draw(1)
        device.deleteTexture(means)

        val smoothed = blurredCopy(coefficients, width, height, radius, BASE_BYTES)
        device.deleteTexture(coefficients)
        return smoothed
    }

    private fun blurredCopy(
        source: TextureHandle,
        width: Int,
        height: Int,
        radius: Float,
        bytesPerPixel: Int,
    ): TextureHandle? {
        if (!device.useProgram(Shaders.BLUR.id)) return null
        val horizontal = device.createTexture(width, height, bytesPerPixel)
        val vertical = device.createTexture(width, height, bytesPerPixel)
        for ((input, output, dx, dy) in listOf(
            Blur(source, horizontal, 1f, 0f),
            Blur(horizontal, vertical, 0f, 1f),
        )) {
            device.useProgram(Shaders.BLUR.id)
            device.bindTarget(output)
            device.setBlend(false)
            device.bindInput("uSource", input)
            device.setVec2("uDirection", dx, dy)
            device.setFloat("uRadius", radius)
            device.setVec2("uTexelSize", 1f / width, 1f / height)
            device.draw(1)
        }
        device.deleteTexture(horizontal)
        return vertical
    }

    /** What a correction borrowed for one draw, and how to give it back. */
    private class BaseLayers(
        val first: TextureHandle?,
        val second: TextureHandle?,
        private val device: GlDevice,
    ) {
        fun release() {
            first?.let(device::deleteTexture)
            second?.let(device::deleteTexture)
        }
    }

    // ---- adjustment tables -------------------------------------------------------------------

    private val adjustmentTables = HashMap<Any, TextureHandle>()

    /**
     * The four curves as one 256-sample table.
     *
     * Sampled rather than evaluated in the shader: a Catmull-Rom through an arbitrary number of
     * control points is a loop per pixel, and the answer only ever depends on one input value.
     */
    private fun curveTable(adjustment: ir.pixellab.core.model.Adjustment): TextureHandle? {
        val key = "curves" to adjustment
        adjustmentTables[key]?.let { return it }

        // Selective Color rides in the same texture as the curves — nine texels of ink rather than
        // 256 of tone — so it is answered before the curve path rather than through it.
        if (adjustment is ir.pixellab.core.model.Adjustment.SelectiveColor) {
            val handle = device.createTexture(TABLE_SIZE, 1, bytesPerPixel = 4)
            device.uploadArgb(handle, TABLE_SIZE, 1, AdjustmentUniforms.selectiveColorTable(adjustment, TABLE_SIZE))
            adjustmentTables[key] = handle
            return handle
        }

        // Equalize's frozen cumulative histogram is already a 256-sample tone mapping, so it rides
        // in the same red channel a composite curve does and needs no curve to be fitted to it.
        if (adjustment is ir.pixellab.core.model.Adjustment.Equalize) {
            if (adjustment.table.isEmpty()) return null
            val handle = device.createTexture(TABLE_SIZE, 1, bytesPerPixel = 4)
            val table = adjustment.table
            device.uploadArgb(
                handle, TABLE_SIZE, 1,
                IntArray(TABLE_SIZE) { i ->
                    val at = (i.toFloat() / (TABLE_SIZE - 1) * (table.size - 1) + 0.5f).toInt()
                    val v = (table[at.coerceIn(0, table.size - 1)].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    (0xFF shl 24) or (v shl 16)
                },
            )
            adjustmentTables[key] = handle
            return handle
        }

        val curves = when (adjustment) {
            is ir.pixellab.core.model.Adjustment.Curves ->
                listOf(adjustment.rgb, adjustment.red, adjustment.green, adjustment.blue)
            // HDR Toning's toning curve is a composite curve and nothing else; the three channel
            // slots stay linear so the same table layout serves both.
            is ir.pixellab.core.model.Adjustment.HdrToning -> listOf(
                adjustment.toningCurve,
                ir.pixellab.core.model.Curve.LINEAR,
                ir.pixellab.core.model.Curve.LINEAR,
                ir.pixellab.core.model.Curve.LINEAR,
            )
            is ir.pixellab.core.model.Adjustment.Levels -> listOf(
                ir.pixellab.core.model.Curve.LINEAR,
                levelsCurve(adjustment.perChannel.getOrNull(0)),
                levelsCurve(adjustment.perChannel.getOrNull(1)),
                levelsCurve(adjustment.perChannel.getOrNull(2)),
            )
            else -> return null
        }
        val tables = curves.map { ir.pixellab.core.render.Luts.curve(it, TABLE_SIZE) }
        val pixels = IntArray(TABLE_SIZE) { i ->
            val composite = (tables[0][i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val red = (tables[1][i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val green = (tables[2][i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val blue = (tables[3][i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            // The composite curve rides in red and the three channel curves in the rest, matching
            // how the shader reads them back.
            (blue shl 24) or (composite shl 16) or (red shl 8) or green
        }
        val handle = device.createTexture(TABLE_SIZE, 1, bytesPerPixel = 4)
        device.uploadArgb(handle, TABLE_SIZE, 1, pixels)
        adjustmentTables[key] = handle
        return handle
    }

    /** A per-channel Levels turned into the curve the table wants. */
    private fun levelsCurve(levels: ir.pixellab.core.model.Adjustment.Levels?): ir.pixellab.core.model.Curve {
        if (levels == null) return ir.pixellab.core.model.Curve.LINEAR
        val points = (0..LEVELS_SAMPLES).map { i ->
            val t = i.toFloat() / LEVELS_SAMPLES
            val range = (levels.inputWhite - levels.inputBlack).coerceAtLeast(EPSILON)
            val n = ((t - levels.inputBlack) / range).coerceIn(0f, 1f)
            val gamma = Math.pow(n.toDouble(), 1.0 / levels.gamma.coerceAtLeast(EPSILON).toDouble()).toFloat()
            Vec2(t, levels.outputBlack + gamma * (levels.outputWhite - levels.outputBlack))
        }
        return ir.pixellab.core.model.Curve(points)
    }

    private fun rampTable(adjustment: ir.pixellab.core.model.Adjustment): TextureHandle? {
        val map = adjustment as? ir.pixellab.core.model.Adjustment.GradientMap ?: return null
        val key = "ramp" to map.gradient
        adjustmentTables[key]?.let { return it }

        val ramp = ir.pixellab.core.render.Luts.gradient(map.gradient, TABLE_SIZE)
        val handle = device.createTexture(TABLE_SIZE, 1, bytesPerPixel = 4)
        device.uploadArgb(handle, TABLE_SIZE, 1, ramp)
        adjustmentTables[key] = handle
        return handle
    }

    private fun lutTable(adjustment: ir.pixellab.core.model.Adjustment): TextureHandle? {
        val lookup = adjustment as? ir.pixellab.core.model.Adjustment.ColorLookup ?: return null
        val key = "lut" to lookup.asset
        adjustmentTables[key]?.let { return it }

        val image = assets.load(lookup.asset) ?: return null
        val handle = device.createTexture(image.width, image.height, bytesPerPixel = 4)
        device.uploadArgb(handle, image.width, image.height, image.pixels)
        adjustmentTables[key] = handle
        return handle
    }

    private fun renderContent(frame: Frame, layer: Layer, surface: Surface, clip: TextureHandle?) {
        val font = fontFor(layer)
        val graph = graphFor(frame.document, layer, frame.scale, frame.effectsBypassed, font) ?: return
        val shape = bounds(layer, font) ?: return
        val silhouette = silhouetteFor(layer, graph.textureBounds, frame.scale, font)

        effectTextures.layerFill = layer.style.fill
        // A placed photograph is its own paint. Everything else takes its colour from the style,
        // and leaving a previous layer's image set would paint a shape with the last photo.
        effectTextures.layerImage = (layer as? Layer.Image)?.let { imageTexture(it.asset) }
        // Where that paint sits inside the texture. The texture is larger than the layer whenever
        // an effect has asked for bleed, and sampling the paint across the whole of it slides a
        // photograph inside its own frame by exactly that much.
        executor.fillMap = Compositing.textureUvToShapeUv(graph.textureBounds, shape).values
        val result = executor.execute(
            graph = graph,
            layerTexture = silhouette,
            // Frosted glass samples what is already composited beneath, which is exactly the
            // buffer being read from this frame.
            backdropTexture = surface.read,
            scale = frame.scale,
            globalLightAngle = frame.document.globalLight.angle,
            color = frame.document.color,
        )
        frame.errors += result.errors

        val appearance = result.output
        if (appearance == null) {
            frame.errors += ExecutionError(Shaders.COMPOSITE.id, "layer '${layer.name}' produced nothing")
            return
        }
        effectTextures.layerImage = null
        compositeTexture(
            frame = frame,
            source = appearance,
            textureBounds = graph.textureBounds,
            transform = layer.transform,
            opacity = layer.opacity,
            blendMode = layer.blendMode,
            mask = maskFor(frame, layer, graph.textureBounds),
            clip = clip,
            surface = surface,
        )
        executor.recycle(appearance)
    }

    // ---- compositing -----------------------------------------------------------------------

    /**
     * Draws a finished layer onto a surface.
     *
     * The step that carries the four things a layer has that its effects do not: where it sits, how
     * opaque it is, how it blends, and what hides it. Without it every layer renders correctly into
     * a buffer that is then thrown away, which looks exactly like a renderer that does nothing.
     */
    private fun compositeTexture(
        frame: Frame,
        source: TextureHandle,
        textureBounds: Rect,
        transform: Transform,
        opacity: Float,
        blendMode: BlendMode,
        mask: MaskBinding?,
        clip: TextureHandle?,
        surface: Surface,
    ) {
        if (!device.useProgram(Shaders.COMPOSITE.id)) {
            frame.errors += ExecutionError(Shaders.COMPOSITE.id, "composite program unavailable")
            return
        }

        device.bindTarget(surface.write)
        // The blend is done in the shader, so fixed-function blending has to be off or it would
        // be applied a second time on top.
        device.setBlend(false)
        device.bindInput("uSource", source)
        device.bindInput("uBackdrop", surface.read)
        device.setMat3(
            "uMap",
            // Both in canvas units. Scaling the canvas here and not the bounds is the classic
            // export bug: everything lands in the right place on screen and in the wrong place at
            // any export multiplier.
            Compositing.canvasUvToLayerUv(
                canvasSize = frame.document.canvas.size,
                textureBounds = textureBounds,
                transform = transform,
            ).values,
        )
        device.setFloat("uOpacity", opacity)
        device.setInt("uBlendMode", BlendShaders.uniformValue(blendMode))
        bindMasks(mask, clip)
        device.setVec2("uTexelSize", 1f / canvasSize.x, 1f / canvasSize.y)
        device.draw(1)

        // What was just written becomes what the next layer reads.
        surface.swap()
    }

    /** Copies one canvas-sized buffer into another, alpha included. */
    private fun copy(source: TextureHandle, target: TextureHandle) {
        if (!device.useProgram(Shaders.COPY.id)) return
        device.bindTarget(target)
        device.setBlend(false)
        device.bindInput("uSource", source)
        device.setVec2("uTexelSize", 1f / canvasSize.x, 1f / canvasSize.y)
        device.draw(1)
    }

    // ---- masks -----------------------------------------------------------------------------

    /** Everything the composite pass needs to narrow a layer's coverage. */
    private class MaskBinding(
        val raster: TextureHandle?,
        val rasterMap: Affine,
        val rasterInverted: Boolean,
        val density: Float,
        val vector: TextureHandle?,
        val vectorMap: Affine,
        val vectorInverted: Boolean,
    )

    private val maskTextures = HashMap<Any, TextureHandle>()
    private var whiteTexture: TextureHandle? = null

    private fun maskFor(frame: Frame, layer: Layer, textureBounds: Rect): MaskBinding? {
        val raster = layer.mask?.takeIf { it.enabled && it.density > 0f }
        val vector = layer.vectorMask?.takeIf { it.enabled }
        if (raster == null && vector == null) return null

        return MaskBinding(
            raster = raster?.let { rasterMaskTexture(it, frame.scale) },
            // A linked mask travels with the layer, so it is sampled through the layer's own frame;
            // an unlinked one stays pinned to the artboard while the layer slides underneath it,
            // which is what makes a mask usable for a reveal.
            rasterMap = when {
                raster == null -> Affine.IDENTITY
                raster.unlinked -> Affine.IDENTITY
                else -> Compositing.canvasUvToLayerUv(
                    frame.document.canvas.size, textureBounds, layer.transform,
                )
            },
            rasterInverted = raster?.inverted ?: false,
            density = raster?.density ?: 1f,
            vector = vector?.let { vectorMaskTexture(it, frame.scale) },
            vectorMap = if (vector == null) {
                Affine.IDENTITY
            } else {
                Compositing.canvasUvToLayerUv(
                    frame.document.canvas.size, vectorMaskBounds(vector), layer.transform,
                )
            },
            vectorInverted = vector?.inverted ?: false,
        )
    }

    private fun bindMasks(mask: MaskBinding?, clip: TextureHandle?) {
        val white = whiteTexture ?: solidWhite().also { whiteTexture = it }
        var flags = 0
        if (mask?.raster != null) {
            flags = flags or MASK_RASTER
            if (mask.rasterInverted) flags = flags or MASK_RASTER_INVERTED
        }
        if (mask?.vector != null) {
            flags = flags or MASK_VECTOR
            if (mask.vectorInverted) flags = flags or MASK_VECTOR_INVERTED
        }
        if (clip != null) flags = flags or MASK_CLIP

        // Every sampler is bound whether it is used or not: an unbound sampler in GL ES reads
        // texture unit zero, which is whatever the previous layer happened to leave there.
        device.bindInput("uMask", mask?.raster ?: white)
        device.bindInput("uVectorMask", mask?.vector ?: white)
        device.bindInput("uClip", clip ?: white)
        device.setMat3("uMaskMap", (mask?.rasterMap ?: Affine.IDENTITY).values)
        device.setMat3("uVectorMaskMap", (mask?.vectorMap ?: Affine.IDENTITY).values)
        device.setInt("uMaskFlags", flags)
        device.setFloat("uMaskDensity", mask?.density ?: 1f)
    }

    /**
     * A raster mask's pixels, feathered if it asks to be.
     *
     * Cached on the asset *and* the feather, because feathering is a blur over the whole mask and
     * a mask is often the largest image in a project. Recomputing it per frame would cost more than
     * everything the layer's own effects do.
     */
    private fun rasterMaskTexture(mask: LayerMask, scale: Float): TextureHandle? {
        val key = MaskKey(mask.asset.value, mask.feather, scale)
        maskTextures[key]?.let { return it }

        val image = assets.load(mask.asset) ?: return null
        val handle = device.createTexture(image.width, image.height, bytesPerPixel = 4)
        // A mask is greyscale, and the composite pass reads alpha. Copying the luminance into the
        // alpha channel here means one sampling rule for both kinds of mask.
        device.uploadArgb(handle, image.width, image.height, luminanceToAlpha(image.pixels))
        val finished = if (mask.feather > 0f) {
            feather(handle, image.width, image.height, mask.feather * scale).also {
                if (it != handle) device.deleteTexture(handle)
            }
        } else {
            handle
        }
        maskTextures[key] = finished
        return finished
    }

    /**
     * A vector mask, rasterised.
     *
     * Drawn rather than sampled, at the scale the frame is being rendered at, which is what makes
     * it resolution-independent: an export at four times size gets a mask edge four times sharper
     * instead of an upscaled one.
     */
    private fun vectorMaskTexture(mask: VectorMask, scale: Float): TextureHandle? {
        val key = MaskKey(mask.path.hashCode().toString(), mask.feather, scale)
        maskTextures[key]?.let { return it }

        val bounds = vectorMaskBounds(mask)
        val bitmap = rasterizer.mask(mask.path, bounds, scale, mask.feather)
        val handle = device.createTexture(bitmap.width, bitmap.height, bytesPerPixel = 4)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        device.uploadArgb(handle, bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        maskTextures[key] = handle
        return handle
    }

    private data class MaskKey(val id: String, val feather: Float, val scale: Float)

    /**
     * The extent a vector mask is drawn over.
     *
     * Grown by the feather so a soft edge is not cut off by the very rectangle it fades inside —
     * the failure that turns a feathered mask into a hard-edged one at exactly the radius chosen.
     */
    private fun vectorMaskBounds(mask: VectorMask): Rect {
        val size = shapeSize(mask.path)
        val pad = mask.feather
        return Rect(-pad, -pad, size.x + pad, size.y + pad)
    }

    private fun feather(source: TextureHandle, width: Int, height: Int, radius: Float): TextureHandle {
        if (!device.useProgram(Shaders.BLUR.id)) return source
        val horizontal = device.createTexture(width, height, bytesPerPixel = 4)
        val vertical = device.createTexture(width, height, bytesPerPixel = 4)

        for ((input, output, dx, dy) in listOf(
            Blur(source, horizontal, 1f, 0f),
            Blur(horizontal, vertical, 0f, 1f),
        )) {
            device.useProgram(Shaders.BLUR.id)
            device.bindTarget(output)
            device.setBlend(false)
            device.bindInput("uSource", input)
            device.setVec2("uDirection", dx, dy)
            device.setFloat("uRadius", radius)
            device.setVec2("uTexelSize", 1f / width, 1f / height)
            device.draw(1)
        }
        device.deleteTexture(horizontal)
        return vertical
    }

    private data class Blur(
        val input: TextureHandle,
        val output: TextureHandle,
        val dx: Float,
        val dy: Float,
    )

    /** Greyscale to alpha, so both kinds of mask are sampled the same way. */
    private fun luminanceToAlpha(pixels: IntArray): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Rec. 709 luma. A plain average would make a mask painted in pure blue far lighter
            // than it looks, and masks are read by eye.
            val luma = ((r * 2126 + g * 7152 + b * 722) / 10000).coerceIn(0, 255)
            out[i] = (luma shl 24) or 0xFFFFFF
        }
        return out
    }

    private fun solidWhite(): TextureHandle {
        val handle = device.createTexture(1, 1, bytesPerPixel = 4)
        device.uploadArgb(handle, 1, 1, intArrayOf(-1))
        return handle
    }

    private fun clearMasks() {
        maskTextures.values.forEach(device::deleteTexture)
        maskTextures.clear()
    }

    /** Drops every cached mask, for when an asset behind one has been repainted. */
    fun invalidateMasks() = clearMasks()

    // ---- buffers ---------------------------------------------------------------------------

    private fun obtainSurface(bytesPerPixel: Int): Surface {
        val surface = freeSurfaces.removeLastOrNull() ?: Surface(
            device.createTexture(canvasSize.x.toInt(), canvasSize.y.toInt(), bytesPerPixel),
            device.createTexture(canvasSize.x.toInt(), canvasSize.y.toInt(), bytesPerPixel),
        )
        surface.flipped = false
        // Both halves, not just the one that will be read: the other becomes the read buffer after
        // the first composite, and a stale one would show the previous group through this one.
        device.bindTarget(surface.front)
        device.clearTarget()
        device.bindTarget(surface.back)
        device.clearTarget()
        liveSurfaces += surface
        return surface
    }

    private fun obtainTexture(bytesPerPixel: Int): TextureHandle {
        val handle = freeTextures.removeLastOrNull()
            ?: device.createTexture(canvasSize.x.toInt(), canvasSize.y.toInt(), bytesPerPixel)
        device.bindTarget(handle)
        device.clearTarget()
        liveTextures += handle
        return handle
    }

    /**
     * Returns every borrowed buffer at the end of a frame.
     *
     * At the end rather than as each group finishes, because a group's buffer is still being read
     * while its result is composited, and handing it to the next group mid-frame would have that
     * group draw into the very texture being sampled.
     */
    private fun recycleAll() {
        freeSurfaces.addAll(liveSurfaces)
        freeTextures.addAll(liveTextures)
        liveSurfaces.clear()
        liveTextures.clear()
    }

    private fun discardPools() {
        (freeSurfaces + liveSurfaces).forEach { device.deleteTexture(it.front); device.deleteTexture(it.back) }
        (freeTextures + liveTextures).forEach(device::deleteTexture)
        freeSurfaces.clear()
        liveSurfaces.clear()
        freeTextures.clear()
        liveTextures.clear()
    }

    // ---- measurement -----------------------------------------------------------------------

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
        val raster = rasterizer.silhouette(layer, bounds, scale, font, imageBitmapFor(layer))
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
        // A painted layer's pixels change under the same asset id, so the id alone is not enough
        // to notice a brush stroke; the store bumps a generation whenever it repaints one.
        is Layer.Image -> 31 * layer.asset.hashCode() + assetGeneration
        else -> 0
    }

    /**
     * Bumped by the caller when an asset's pixels have changed.
     *
     * A painted layer keeps the same asset id from the first stroke to the last, so nothing else
     * about it tells the cache that the pixels moved.
     */
    var assetGeneration: Int = 0
        set(value) {
            if (value == field) return
            field = value
            imageTextures.values.forEach(device::deleteTexture)
            imageTextures.clear()
            imageBitmaps.clear()
        }

    private val imageTextures = HashMap<String, TextureHandle>()
    private val imageBitmaps = HashMap<String, android.graphics.Bitmap>()

    /**
     * A placed image as a paint texture, with alpha forced opaque.
     *
     * Opaque because the fill pass multiplies the paint's alpha by the silhouette's, and the
     * silhouette is already the image's alpha — leaving it in both would square it, which shows up
     * as a dark fringe all the way round a cut-out subject.
     */
    private fun imageTexture(asset: ir.pixellab.core.model.AssetId): TextureHandle? {
        imageTextures[asset.value]?.let { return it }
        val image = assets.load(asset) ?: return null
        val handle = device.createTexture(image.width, image.height, bytesPerPixel = 4)
        val opaque = IntArray(image.pixels.size) { image.pixels[it] or (0xFF shl 24) }
        device.uploadArgb(handle, image.width, image.height, opaque)
        imageTextures[asset.value] = handle
        return handle
    }

    private fun imageBitmapFor(layer: Layer): android.graphics.Bitmap? {
        val asset = (layer as? Layer.Image)?.asset ?: return null
        imageBitmaps[asset.value]?.let { return it }
        val image = assets.load(asset) ?: return null
        return android.graphics.Bitmap
            .createBitmap(image.pixels, image.width, image.height, android.graphics.Bitmap.Config.ARGB_8888)
            .also { imageBitmaps[asset.value] = it }
    }

    /** The font a text layer shapes with, or null for every other kind. */
    private fun fontFor(layer: Layer): FontFile? =
        (layer as? Layer.Text)?.let { fonts.resolve(it.spec.font) }

    /**
     * Where a layer's content sits before its transform.
     *
     * Text has to be shaped to be measured, so the answer is cached against the same key the
     * silhouette uses — otherwise laying out a paragraph would happen twice per frame, once to find
     * the bounds and once to draw into them.
     */
    fun bounds(layer: Layer, font: FontFile? = null): Rect? = when (layer) {
        is Layer.Shape -> Rect.of(shapeSize(layer.geometry))
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
        // A placed image is exactly as large as its pixels, in canvas units, cropped if it says so.
        is Layer.Image -> assets.load(layer.asset)?.let { image ->
            val crop = layer.crop
            if (crop == null) {
                Rect(0f, 0f, image.width.toFloat(), image.height.toFloat())
            } else {
                Rect(0f, 0f, crop.width * image.width, crop.height * image.height)
            }
        }
        // A group's extent comes from its children, which is not this class's to invent.
        else -> null
    }

    private val measured = HashMap<LayerId, Pair<Int, Rect>>()

    private fun shapeSize(geometry: ShapeGeometry) = when (geometry) {
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

        /** A surface is a ping-pong pair, plus the clip source a clipping group copies aside. */
        const val SURFACE_BUFFERS = 3

        /** 256 samples: one per eight-bit input value, so the table is exact at that precision. */
        const val TABLE_SIZE = 256

        /** Enough control points that a gamma curve reads as smooth through the table. */
        const val LEVELS_SAMPLES = 16

        /**
         * Half float for a neighbourhood correction's base layer, whatever the document's precision.
         *
         * The guided filter subtracts a mean squared from a squared mean, and at eight bits per
         * channel that difference is entirely rounding error — the variance comes out as noise, the
         * base follows it, and the tone mapping it feeds turns to mush. Two extra bytes a pixel on a
         * texture that lives for one draw is not a cost worth arguing about.
         */
        const val BASE_BYTES = 8

        const val EPSILON = 0.0001f

        const val MASK_RASTER = 1
        const val MASK_RASTER_INVERTED = 2
        const val MASK_VECTOR = 4
        const val MASK_VECTOR_INVERTED = 8
        const val MASK_CLIP = 16

        /**
         * Whether a group has to flatten its children before meeting the backdrop.
         *
         * Photoshop isolates for any of these, and getting the list wrong is visible immediately:
         * a group at 50% whose children were composited individually shows every overlap.
         */
        fun isolating(group: Layer.Group): Boolean =
            !group.passThrough ||
                group.style.activeEffects.isNotEmpty() ||
                group.opacity < 1f ||
                group.mask != null ||
                group.vectorMask != null
    }
}
