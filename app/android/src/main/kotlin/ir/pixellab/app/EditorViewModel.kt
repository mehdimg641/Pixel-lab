package ir.pixellab.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.pixellab.core.canvas.CanvasGesture
import ir.pixellab.core.canvas.Handle
import ir.pixellab.core.editor.Editor
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.Tool
import ir.pixellab.core.fonts.Typeface
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import ir.pixellab.engine.android.AssetSource
import ir.pixellab.engine.android.FontResolver
import ir.pixellab.engine.android.LayerMeasure
import ir.pixellab.engine.android.toMaskImage
import ir.pixellab.engine.android.toRaster
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the [Editor] across configuration changes and republishes its state to Compose.
 *
 * The editor itself knows nothing about Compose or Android; this is the only place the two meet.
 * Keeping the boundary that thin is what lets every rule about selection, snapping, sheets and undo
 * be tested without a device.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * The device's fonts.
     *
     * Owned here rather than by the screen so that the scan survives a rotation — re-walking three
     * hundred files every time the phone turns is both slow and pointless.
     */
    val fontStore = FontStore.forApp(application)

    /** Read during composition, so the canvas re-shapes its text the moment a scan lands. */
    val fonts: FontResolver get() = fontStore.resolver

    /**
     * The one measurement the whole app agrees on.
     *
     * The same instance drives the editor's snapping and hit-testing *and* the selection chrome. A
     * second implementation for the chrome is the standard way this goes wrong: the handles end up
     * drawn around a box the editor never used.
     */
    /** Decoded masks and placed images, shared by the renderer and the measurer. */
    val assetStore = AssetStore()

    val assets: AssetSource get() = assetStore.source

    val bounds = LayerMeasure(images = assetStore.sizes)

    /** The brush, and the stroke it currently has in progress. */
    val paint = PaintController(assetStore)

    /**
     * The chosen pixels.
     *
     * Not part of the document: it survives an undo of the artwork, it is not saved, and every tool
     * that narrows what it touches reads the same one.
     */
    val select = SelectionController()

    /** Liquify strokes, gathered until the user asks for them to be solved. */
    val warp = WarpController()

    /** The path being drawn or edited, before it becomes a layer. */
    val pen = PenController()

    private val editor = Editor(startingDocument(), bounds)

    var state: EditorState by mutableStateOf(editor.state)
        private set

    init {
        // A smart object is measured through its source, and the source is wherever the document
        // currently has it — a captured copy would size an instance from a layer that has since
        // been resized.
        bounds.sources = { id -> editor.state.document.findLayer(id) }

        // Off the main thread from the first frame: the canvas has to draw before the library is
        // known, and text arrives when it is.
        viewModelScope.launch {
            fontStore.rescan()
            // The chrome measures through this too, so the handles would otherwise keep the
            // placeholder box the text was measured with before the scan landed. The screen
            // recomposes off [fonts] changing identity, which redraws them.
            bounds.fonts = fontStore.resolver
        }
    }

    /**
     * One step of history, of either kind.
     *
     * Painted pixels are not part of the document, so the editor's own stack cannot hold them — and
     * two separate stacks would let undo skip a step whenever the user alternated between painting
     * and moving. This interleaves them.
     */
    private sealed interface Step {
        data object Edit : Step
        data class Paint(val edit: PaintEdit) : Step
    }

    private val steps = ArrayList<Step>()
    private val undone = ArrayList<Step>()
    private var recordedDepth = 0

    val canUndo: Boolean get() = steps.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    /** Every mutation goes through here so a state change can never be forgotten. */
    private fun <T> edit(body: Editor.() -> T): T {
        val result = editor.body()
        // The editor decides for itself whether an action was worth a history entry — a tap that
        // selected nothing is not. Asking it afterwards is the only way to stay in step.
        if (editor.undoDepth > recordedDepth) {
            steps += Step.Edit
            undone.clear()
        }
        recordedDepth = editor.undoDepth
        state = editor.state
        return result
    }

    fun undo() {
        val step = steps.removeLastOrNull() ?: return
        when (step) {
            is Step.Paint -> paint.restore(step.edit, redo = false)
            Step.Edit -> {
                editor.undo()
                state = editor.state
            }
        }
        undone += step
        recordedDepth = editor.undoDepth
    }

    fun redo() {
        val step = undone.removeLastOrNull() ?: return
        when (step) {
            is Step.Paint -> paint.restore(step.edit, redo = true)
            Step.Edit -> {
                editor.redo()
                state = editor.state
            }
        }
        steps += step
        recordedDepth = editor.undoDepth
    }

    val current: Editor get() = editor

    fun act(body: Editor.() -> Unit) = edit(body)

    fun handleUnder(screen: Vec2): Handle? = editor.handleUnder(screen)

    /**
     * Routes one canvas gesture.
     *
     * The decision that matters is the first branch: a one-finger drag starting on a handle or on
     * the selected layer manipulates it, and anywhere else pans the camera. Without that split the
     * canvas can only ever do one of the two, and a mobile editor needs both from the same finger.
     */
    fun onGesture(gesture: CanvasGesture) {
        when (gesture) {
            CanvasGesture.Undo -> return undo()
            CanvasGesture.Redo -> return redo()
            else -> Unit
        }
        // While a paint or select tool is active the drag belongs to the tool, not to the layer
        // under it — otherwise the first brush stroke drags whatever happened to be selected.
        if (state.tool.ownsDrag && routeToTool(gesture)) return
        route(gesture)
    }

    private fun routeToTool(gesture: CanvasGesture): Boolean = when (state.tool) {
        Tool.SELECT -> routeToSelection(gesture)
        Tool.RETOUCH -> routeToWarp(gesture)
        Tool.PEN -> routeToPen(gesture)
        else -> routeToBrush(gesture)
    }

    /**
     * The pen.
     *
     * The touch radius is converted from screen pixels into canvas units, so a node stays as easy to
     * hit at 800% zoom as at 25%. A fixed canvas radius is the version that becomes impossible to
     * use the moment the user zooms in to do fine work — which is when they need it most.
     */
    private fun routeToPen(gesture: CanvasGesture): Boolean {
        val canvasPoint = { screen: Vec2 -> state.viewport.toCanvas(screen) }
        val radius = TOUCH_RADIUS / state.viewport.zoom.coerceAtLeast(0.01f)
        return when (gesture) {
            is CanvasGesture.Tap -> {
                pen.tap(canvasPoint(gesture.position), radius)
                true
            }
            is CanvasGesture.DragStart -> {
                pen.dragStart(canvasPoint(gesture.position), radius)
                true
            }
            is CanvasGesture.Drag -> {
                pen.drag(canvasPoint(gesture.position))
                true
            }
            is CanvasGesture.DragEnd -> {
                pen.dragEnd()
                true
            }
            else -> false
        }
    }

    // ---- vector ---------------------------------------------------------------------------------

    /** Turns the path being drawn into a layer. */
    fun commitPenPath(): LayerId? {
        if (pen.isEmpty) return null
        val path = pen.path
        pen.reset()
        return edit {
            addLayer(
                Layer.Shape(id = nextLayerId("path"), geometry = path, name = "مسیر"),
            )
        }
    }

    /**
     * Replaces a stroked path with its filled outline.
     *
     * Illustrator's Outline Stroke. Once it is a fill it takes gradients, effects and boolean
     * operations like any other shape, which is exactly why the width tool composes with everything
     * else there.
     */
    fun outlineStroke(width: Float, profile: ir.pixellab.core.vector.WidthProfile) {
        val source = if (!pen.isEmpty) {
            pen.path
        } else {
            ((state.primaryLayer as? Layer.Shape)?.geometry as? ShapeGeometry.Path) ?: return
        }
        val outlined = ir.pixellab.core.vector.StrokeOutliner.outline(source, width, profile)
        if (!pen.isEmpty) {
            pen.reset()
            edit { addLayer(Layer.Shape(id = nextLayerId("path"), geometry = outlined, name = "خط ضخامت‌دار")) }
        } else {
            val id = state.selection.primary ?: return
            edit { replaceLayer(id) { (it as Layer.Shape).copy(geometry = outlined) } }
        }
    }

    /**
     * Combines the selected shapes.
     *
     * The result replaces the bottom-most of them and the rest are removed, which is what
     * Illustrator does — and it matters, because the bottom shape's style is the one the combined
     * result keeps.
     */
    fun combineShapes(operation: ir.pixellab.engine.android.PathOperation) {
        val shapes = selectedPaths()
        if (shapes.size < 2) return
        val combined = ir.pixellab.engine.android.Pathfinder.apply(shapes.map { it.second }, operation)
        edit {
            replaceLayer(shapes.first().first) { (it as Layer.Shape).copy(geometry = combined) }
            for ((id, _) in shapes.drop(1)) deleteLayer(id)
            select(shapes.first().first)
        }
    }

    /** Illustrator's Divide: every region the shapes cut each other into, as its own layer. */
    fun divideShapes() {
        val shapes = selectedPaths()
        if (shapes.size < 2) return
        val regions = ir.pixellab.engine.android.Pathfinder.divide(shapes.map { it.second })
        if (regions.isEmpty()) return
        val style = (state.document.findLayer(shapes.first().first) as? Layer.Shape)?.style ?: return

        edit {
            for ((id, _) in shapes) deleteLayer(id)
            for (region in regions) {
                addLayer(
                    Layer.Shape(
                        id = nextLayerId("region"),
                        geometry = region,
                        name = "ناحیه",
                        style = style,
                    ),
                )
            }
        }
    }

    fun offsetPath(distance: Float) {
        val id = state.selection.primary ?: return
        val path = ((state.primaryLayer as? Layer.Shape)?.geometry as? ShapeGeometry.Path) ?: return
        val offset = ir.pixellab.engine.android.Pathfinder.offset(path, distance)
        edit { replaceLayer(id) { (it as Layer.Shape).copy(geometry = offset) } }
    }

    /** The path being drawn, as SVG path data — the string every other tool will accept back. */
    fun copyPathAsSvg(): String = ir.pixellab.core.vector.SvgPath.write(pen.path)

    private fun selectedPaths(): List<Pair<LayerId, ShapeGeometry.Path>> =
        state.document.layers
            .filter { it.id in state.selection }
            .mapNotNull { layer ->
                ((layer as? Layer.Shape)?.geometry as? ShapeGeometry.Path)?.let { layer.id to it }
            }

    // ---- library --------------------------------------------------------------------------------

    /** Applies a saved effect stack to the selection, as one undo step. */
    fun applyStyle(preset: ir.pixellab.core.editor.StylePreset) = edit {
        val id = state.selection.primary ?: return@edit
        applyStyle(id, preset.style)
    }

    /**
     * Starts a new document at a template's size.
     *
     * History is cleared rather than recorded: an undo straight after starting a new document
     * should not take the user back into the previous one, which is not the thing they are working
     * on any more.
     */
    fun newFromTemplate(template: ir.pixellab.core.editor.TemplatePreset) {
        assetStore.clear()
        select.clear()
        pen.reset()
        warp.reset()
        openDocument(ir.pixellab.core.editor.Library.documentFor(template, template.name))
    }

    // ---- creating layers ---------------------------------------------------------------------

    /**
     * Gathers a liquify stroke.
     *
     * Only the two ends are kept. A moving-least-squares solve takes a control point per stroke, not
     * per sample, and feeding it every touch event would make the solve slower without moving a
     * single pixel differently.
     */
    private fun routeToWarp(gesture: CanvasGesture): Boolean {
        val canvasPoint = { screen: Vec2 -> state.viewport.toCanvas(screen) }
        return when (gesture) {
            is CanvasGesture.DragStart -> {
                warp.begin(canvasPoint(gesture.position))
                true
            }
            is CanvasGesture.Drag -> true
            is CanvasGesture.DragEnd -> {
                warp.end(canvasPoint(gesture.position))
                true
            }
            else -> false
        }
    }

    private fun routeToSelection(gesture: CanvasGesture): Boolean {
        val canvasPoint = { screen: Vec2 -> state.viewport.toCanvas(screen) }
        val canvas = state.document.canvas
        return when (gesture) {
            is CanvasGesture.DragStart -> {
                select.begin(canvasPoint(gesture.position))
                true
            }
            is CanvasGesture.Drag -> {
                select.extend(canvasPoint(gesture.position))
                true
            }
            is CanvasGesture.DragEnd -> {
                select.end(canvasPoint(gesture.position), canvas.width, canvas.height, sampledPixels())
                paint.selection = select.selection
                true
            }
            // The wand is a tap, not a drag, and it is the tool people reach for first.
            is CanvasGesture.Tap -> {
                val at = canvasPoint(gesture.position)
                select.begin(at)
                select.end(at, canvas.width, canvas.height, sampledPixels())
                paint.selection = select.selection
                true
            }
            else -> false
        }
    }

    /** What the wand samples: the selected layer's own pixels, when it has any. */
    private fun sampledPixels(): ir.pixellab.core.codec.RasterImage? =
        (state.primaryLayer as? Layer.Image)?.let { assetStore.source.load(it.asset) }

    private fun routeToBrush(gesture: CanvasGesture): Boolean {
        if (state.tool != Tool.BRUSH) return false
        val canvasPoint = { screen: Vec2 -> state.viewport.toCanvas(screen) }

        // The armed clone source swallows the whole gesture, including the drag that would otherwise
        // paint. Letting a drag through would set the source and then immediately paint over it.
        if (paint.armingCloneSource) {
            when (gesture) {
                is CanvasGesture.Tap -> paint.setCloneSource(canvasPoint(gesture.position))
                is CanvasGesture.DragEnd -> paint.setCloneSource(canvasPoint(gesture.position))
                else -> Unit
            }
            return true
        }

        return when (gesture) {
            is CanvasGesture.DragStart -> paint.begin(state.primaryLayer, canvasPoint(gesture.position), 1f)
            is CanvasGesture.Drag -> {
                if (!paint.isPainting) return false
                paint.extend(canvasPoint(gesture.position), 1f)
                true
            }
            is CanvasGesture.DragEnd -> {
                if (!paint.isPainting) return false
                paint.end(canvasPoint(gesture.position), 1f)?.let {
                    steps += Step.Paint(it)
                    undone.clear()
                }
                true
            }
            // A tap is a single dab, which is a legitimate thing to draw.
            is CanvasGesture.Tap -> {
                if (!paint.begin(state.primaryLayer, canvasPoint(gesture.position), 1f)) return false
                paint.end(canvasPoint(gesture.position), 1f)?.let {
                    steps += Step.Paint(it)
                    undone.clear()
                }
                true
            }
            else -> false
        }
    }

    private fun route(gesture: CanvasGesture) = edit {
        when (gesture) {
            is CanvasGesture.Tap -> tapCanvas(gesture.position)
            is CanvasGesture.DoubleTap -> tapCanvas(gesture.position)
            is CanvasGesture.LongPress -> longPressCanvas(gesture.position)

            is CanvasGesture.DragStart -> {
                val handle = handleUnder(gesture.position)
                if (handle != null) beginDrag(handle, gesture.position)
            }
            is CanvasGesture.Drag ->
                if (state.drag != null) dragTo(gesture.position) else panViewport(gesture.delta)
            is CanvasGesture.DragEnd -> endDrag()

            is CanvasGesture.TransformStart -> Unit
            is CanvasGesture.Transform ->
                transformViewport(gesture.pan, gesture.scaleFactor, gesture.rotationDegrees, gesture.pivot)
            CanvasGesture.TransformEnd -> endDrag()

            // Routed back out rather than handled here: painted pixels are on the same stack.
            CanvasGesture.Undo -> Unit
            CanvasGesture.Redo -> Unit
        }
    }

    fun onScreenSize(size: Vec2) = edit { resizeScreen(size) }

    /**
     * Replaces the open document with a loaded one.
     *
     * History is cleared rather than recorded: an undo straight after opening a project should not
     * take the user back to whatever happened to be on screen before, which is not their work.
     */
    fun openDocument(document: Document) = edit {
        replaceDocument(document, recordHistory = false)
        fitCanvas()
    }

    /**
     * Opens an imported PSD.
     *
     * The warnings are surfaced rather than logged: an import that lost a drop shadow is something
     * the user needs to know before they build on top of it, not after they export.
     */
    fun openImported(imported: ir.pixellab.core.codec.ImportedPsd) {
        openDocument(imported.document)
        for ((id, image) in imported.assets) assetStore.put(ir.pixellab.core.model.AssetId(id), image)
        paint.bumpGeneration()
    }

    /** Opens a whole project: its document and the assets its masks and images refer to. */
    fun openProject(project: ir.pixellab.core.codec.Project) {
        openDocument(project.document)
        viewModelScope.launch { assetStore.load(project) }
    }

    /** What a save writes: the document plus every asset it refers to. */
    fun currentProject(): ir.pixellab.core.codec.Project =
        ir.pixellab.core.codec.Project(state.document, assets = assetStore.encoded())

    // ---- photo work ----------------------------------------------------------------------------

    /**
     * Turns the current selection into a real matte on the selected image layer.
     *
     * The refined alpha becomes the layer's own pixels rather than a mask asset: the result is a
     * cut-out subject, and keeping the background around behind a mask means every subsequent
     * filter still sees it and every export still carries it.
     */
    suspend fun refineCutout() {
        val layer = state.primaryLayer as? Layer.Image ?: return
        val selection = select.selection ?: return
        val source = assetStore.source.load(layer.asset) ?: return

        val refined = withContext(kotlinx.coroutines.Dispatchers.Default) {
            ir.pixellab.engine.android.Cutout.refine(source, selection).decontaminated
        }
        commitPixels(layer.asset, source, refined)
    }

    suspend fun smoothSkin(amount: Float) = transform { image ->
        ir.pixellab.engine.android.Retouch.smoothSkin(image, amount)
    }

    suspend fun sharpen(amount: Float) = transform { image ->
        ir.pixellab.engine.android.Retouch.sharpen(image, amount)
    }

    suspend fun healSelection() {
        val hole = select.selection ?: return
        transform { image -> ir.pixellab.engine.android.Retouch.heal(image, hole) }
    }

    /**
     * Solves every gathered liquify stroke at once.
     *
     * At once rather than per stroke: applying warps one after another resamples the image each
     * time, so four small nudges come out blurrier than one large one.
     */
    suspend fun applyWarp(amount: Float) {
        if (warp.isEmpty) return
        val strokes = warp.strokes
        val kind = warp.kind
        val size = warp.brushSize
        transform { image ->
            val liquify = ir.pixellab.engine.android.Liquify(image).also { it.brushSize = size }
            for ((from, to) in strokes) {
                when (kind) {
                    WarpKind.PUSH -> liquify.push(from, to)
                    WarpKind.BLOAT -> liquify.scale(from, kotlin.math.abs(amount))
                    WarpKind.PUCKER -> liquify.scale(from, -kotlin.math.abs(amount))
                    WarpKind.TWIRL -> liquify.twirl(from, amount * TWIRL_DEGREES)
                }
            }
            liquify.apply()
        }
        warp.reset()
    }

    /** Runs a pixel operation off the main thread and records it as one undo step. */
    private suspend fun transform(body: (ir.pixellab.core.codec.RasterImage) -> ir.pixellab.core.codec.RasterImage) {
        val layer = state.primaryLayer as? Layer.Image ?: return
        val source = assetStore.source.load(layer.asset) ?: return
        val result = withContext(kotlinx.coroutines.Dispatchers.Default) { body(source) }
        commitPixels(layer.asset, source, result)
    }

    /**
     * Publishes new pixels and puts the old ones on the undo stack.
     *
     * The same stack the brush uses, so an undo after a cut-out and a stroke walks back through
     * both in the order they happened rather than through two lists that disagree.
     */
    private fun commitPixels(
        asset: ir.pixellab.core.model.AssetId,
        before: ir.pixellab.core.codec.RasterImage,
        after: ir.pixellab.core.codec.RasterImage,
    ) {
        if (after === before) return
        assetStore.put(asset, after)
        paint.bumpGeneration()
        steps += Step.Paint(PaintEdit(asset, before, after))
        undone.clear()
    }

    // ---- the canvas ------------------------------------------------------------------------------

    fun resizeCanvas(width: Int, height: Int, anchor: ir.pixellab.core.editor.CanvasAnchor) = edit {
        resizeCanvas(width, height, anchor)
    }

    /**
     * Crops to whatever the user has selected.
     *
     * To the selection's bounding box rather than to its shape: a crop produces a rectangular canvas
     * by definition, and the coverage outside the shape is still wanted — cropping to an ellipse
     * would have to decide whether to erase the corners, which is a different operation entirely.
     */
    fun cropToSelection(): Boolean {
        val box = select.selection?.bounds ?: return false
        edit { cropCanvas(box) }
        // The selection was in the old canvas's coordinates and now means somewhere else entirely.
        select.clear()
        paint.selection = null
        return true
    }

    /** The colour behind everything — Photoshop's background layer, without the layer. */
    fun setCanvasBackground(fill: Fill?) = edit { setCanvasBackground(fill) }

    // ---- pixels ----------------------------------------------------------------------------------

    /**
     * Fills the selection, or the whole layer when nothing is selected.
     *
     * On a paint layer, creating one if the selected layer cannot hold pixels. A fill that reported
     * "select an image layer first" would be right and useless: what the user wants is the colour on
     * the canvas, and the layer is an implementation detail they did not ask about.
     */
    suspend fun fillSelection(color: Color, preserveTransparency: Boolean = false) {
        val target = paintTarget() ?: return
        val source = assetStore.source.load(target) ?: return
        val chosen = select.selection
        val result = withContext(kotlinx.coroutines.Dispatchers.Default) {
            ir.pixellab.engine.android.PixelFilters.fill(source, color, chosen, preserveTransparency)
        }
        commitPixels(target, source, result)
    }

    /** Clears the selection to transparent — the delete key. */
    suspend fun eraseSelection() {
        val layer = state.primaryLayer as? Layer.Image ?: return
        val source = assetStore.source.load(layer.asset) ?: return
        val chosen = select.selection
        val result = withContext(kotlinx.coroutines.Dispatchers.Default) {
            ir.pixellab.engine.android.PixelFilters.clear(source, chosen)
        }
        commitPixels(layer.asset, source, result)
    }

    suspend fun blur(radius: Float) = transform { image ->
        ir.pixellab.engine.android.PixelFilters.gaussian(image, radius, select.selection)
    }

    /**
     * Spin or zoom blur, centred on the selection when there is one.
     *
     * On the selection rather than always on the middle of the layer: the centre is the whole effect,
     * and a user who has drawn a marquee around a face has already told us where it is.
     */
    suspend fun radialBlur(amount: Float, kind: ir.pixellab.core.imaging.RadialBlur.Kind) {
        val chosen = select.selection
        val box = chosen?.bounds
        transform { image ->
            val centre = if (box != null) {
                Vec2((box.left + box.right) / 2f, (box.top + box.bottom) / 2f)
            } else {
                Vec2(image.width / 2f, image.height / 2f)
            }
            ir.pixellab.engine.android.PixelFilters.radial(image, amount, kind, centre, chosen)
        }
    }

    /** Where a fill lands: the selected image layer, or a new one made for the purpose. */
    private fun paintTarget(): ir.pixellab.core.model.AssetId? {
        (state.primaryLayer as? Layer.Image)?.let { return it.asset }
        addPaintLayer(name = "پر شده")
        return (state.primaryLayer as? Layer.Image)?.asset
    }

    // ---- selection -------------------------------------------------------------------------------

    /**
     * Finds the subject of the selected image, with no model involved.
     *
     * Off the main thread: the saliency pass walks every pixel twice, which on a full-resolution
     * photograph is long enough to drop frames.
     */
    suspend fun selectSubject(sensitivity: Float = 0.5f): Boolean {
        val image = sampledPixels() ?: return false
        val found = withContext(kotlinx.coroutines.Dispatchers.Default) {
            ir.pixellab.core.paint.SubjectSelection.select(image.pixels, image.width, image.height, sensitivity)
        }
        if (found.isEmpty) return false
        select.use(found)
        paint.selection = select.selection
        return true
    }

    /** Turns the current selection into a mask on the selected layer, non-destructively. */
    fun maskFromSelection(): Boolean {
        val id = state.selection.primary ?: return false
        val chosen = select.selection ?: return false
        val asset = ir.pixellab.core.model.AssetId("$MASK_PREFIX${id.value}")
        assetStore.put(asset, chosen.toRaster().toMaskImage())
        paint.bumpGeneration()
        edit { setMask(id, ir.pixellab.core.model.LayerMask(asset = asset)) }
        return true
    }

    fun removeMask() {
        val id = state.selection.primary ?: return
        edit { setMask(id, null) }
    }

    // ---- layers ----------------------------------------------------------------------------------

    /**
     * Replaces a text layer with its outlines.
     *
     * The same id, so everything referring to the layer — a clipped layer above it, an instance of
     * it — keeps working. Giving the shape a new id would silently break every one of those.
     */
    fun convertTextToShape(): Boolean {
        val layer = state.primaryLayer as? Layer.Text ?: return false
        val shape = ir.pixellab.engine.android.TextToShape.convert(layer, fonts) ?: return false
        edit { replaceLayer(layer.id) { shape } }
        bounds.invalidate(layer.id)
        return true
    }

    fun canConvertToShape(): Boolean =
        state.primaryLayer?.let { ir.pixellab.engine.android.TextToShape.canConvert(it, fonts) } == true

    /**
     * Places an image from storage as a new layer.
     *
     * Scaled to fit rather than placed at its own pixel size: photographs are routinely larger than
     * the canvas, and a layer that arrives four times the size of the artboard has its handles
     * somewhere off screen where the user cannot reach them.
     */
    fun placeImage(image: ir.pixellab.core.codec.RasterImage, name: String): LayerId = edit {
        val id = nextLayerId("image")
        val asset = ir.pixellab.core.model.AssetId(id.value)
        assetStore.put(asset, image)
        paint.bumpGeneration()

        val canvas = state.document.canvas
        val scale = minOf(
            canvas.width.toFloat() / image.width,
            canvas.height.toFloat() / image.height,
            1f,
        )
        addLayer(
            Layer.Image(
                id = id,
                asset = asset,
                name = name,
                transform = Transform(
                    translation = Vec2(
                        (canvas.width - image.width * scale) / 2f,
                        (canvas.height - image.height * scale) / 2f,
                    ),
                    scale = Vec2(scale, scale),
                ),
            ),
        )
    }

    /** Adds a shape at a readable size in the middle of the canvas. */
    fun addShape(geometry: ShapeGeometry, name: String): LayerId = edit {
        val canvas = state.document.canvas
        val layer = Layer.Shape(id = nextLayerId("shape"), geometry = geometry, name = name)
        val box = bounds.of(layer)
        addLayer(
            layer.copy(
                transform = Transform(
                    translation = Vec2(
                        (canvas.width - box.width) / 2f,
                        (canvas.height - box.height) / 2f,
                    ),
                ),
            ),
        )
    }

    /** Edits the selected shape's geometry in place, as one undo step. */
    fun updateShape(change: (ShapeGeometry) -> ShapeGeometry) {
        val id = state.selection.primary ?: return
        val layer = state.document.findLayer(id) as? Layer.Shape ?: return
        bounds.invalidate(id)
        edit { replaceLayer(id) { (it as Layer.Shape).copy(geometry = change(layer.geometry)) } }
    }

    // ---- creating layers ---------------------------------------------------------------------

    /**
     * Adds a text layer in the middle of the canvas, shaped with [typeface].
     *
     * Centred rather than placed where the sheet was tapped: the picker covers the lower half of the
     * screen, so the tap position is never where the user wants the words.
     *
     * The layer carries a real string. An empty text layer measures to nothing, draws nothing and
     * cannot be selected, which reads as the button having done nothing at all.
     */
    fun addText(typeface: Typeface, weight: Int = DEFAULT_WEIGHT, text: String = SAMPLE_TEXT): LayerId = edit {
        val file = typeface.resolve(weight)
        val spec = TextSpec(
            text = text,
            font = FontRef(
                family = typeface.name,
                postScriptName = file?.postScriptName,
                weight = file?.weight ?: weight,
                italic = file?.italic ?: false,
            ),
            size = DEFAULT_TEXT_SIZE,
        )
        val layer = Layer.Text(id = nextLayerId("text"), spec = spec, name = text.take(24))
        val box = bounds.of(layer)
        addLayer(
            layer.copy(
                transform = Transform(
                    translation = Vec2(
                        (state.document.canvas.width - box.width) / 2f,
                        (state.document.canvas.height - box.height) / 2f,
                    ),
                ),
            ),
        )
    }

    /**
     * Adds an empty layer to paint on.
     *
     * Canvas-sized and transparent. A paint layer smaller than the canvas is the arrangement that
     * makes a brush stop working halfway across the artwork with no explanation, and one that grows
     * to fit the stroke reallocates several megabytes mid-gesture.
     */
    fun addPaintLayer(name: String = "لایهٔ نقاشی"): LayerId = edit {
        val id = nextLayerId("paint")
        val asset = ir.pixellab.core.model.AssetId(id.value)
        val canvas = state.document.canvas
        assetStore.put(
            asset,
            ir.pixellab.core.codec.RasterImage(canvas.width, canvas.height, IntArray(canvas.width * canvas.height)),
        )
        addLayer(Layer.Image(id = id, asset = asset, name = name))
    }

    /**
     * Adds a colour correction above the selection.
     *
     * Above rather than at the top of the stack: an adjustment layer corrects everything beneath it,
     * and dropping every new one at the very top would make each correction apply to the whole
     * document regardless of what the user had selected.
     */
    fun addAdjustment(adjustment: ir.pixellab.core.model.Adjustment, name: String): LayerId = edit {
        addLayer(
            Layer.AdjustmentLayer(
                id = nextLayerId("adjust"),
                adjustment = adjustment,
                name = name,
            ),
        )
    }

    /** Edits an adjustment in place, as one undo step per change. */
    fun setAdjustment(id: LayerId, adjustment: ir.pixellab.core.model.Adjustment) = edit {
        replaceLayer(id) { (it as Layer.AdjustmentLayer).copy(adjustment = adjustment) }
    }

    /** Replaces the string of a text layer, keeping everything else about it. */
    fun setText(id: LayerId, text: String) = edit {
        val layer = state.document.findLayer(id) as? Layer.Text ?: return@edit
        bounds.invalidate(id)
        applyTextSpec(id, layer.spec.copy(text = text))
    }

    /** Restyles a text layer onto another typeface or weight. */
    fun setTextFont(id: LayerId, typeface: Typeface, weight: Int = DEFAULT_WEIGHT) = edit {
        val layer = state.document.findLayer(id) as? Layer.Text ?: return@edit
        val file = typeface.resolve(weight)
        bounds.invalidate(id)
        applyTextSpec(
            id,
            layer.spec.copy(
                font = layer.spec.font.copy(
                    family = typeface.name,
                    postScriptName = file?.postScriptName,
                    weight = file?.weight ?: weight,
                    italic = file?.italic ?: false,
                ),
            ),
        )
    }

    private fun Editor.applyTextSpec(id: LayerId, spec: TextSpec) {
        replaceLayer(id) { (it as Layer.Text).copy(spec = spec) }
    }

    /** The typeface a text layer is currently using, for the picker to show as chosen. */
    fun typefaceOf(id: LayerId): Typeface? {
        val layer = state.document.findLayer(id) as? Layer.Text ?: return null
        return fontStore.catalog.typefaces.firstOrNull { it.name == layer.spec.font.family }
    }

    private companion object {
        /** Regular. A picker that inserted at black weight would be lying about what it showed. */
        const val DEFAULT_WEIGHT = 400

        /** Large enough to read on a 1080-wide canvas without the user resizing first. */
        const val DEFAULT_TEXT_SIZE = 96f

        /** Persian, because the interface and the intended work both are. */
        const val SAMPLE_TEXT = "متن نمونه"

        /** A full turn of the slider gives a quarter turn of the image, which is already a lot. */
        const val TWIRL_DEGREES = 90f

        /**
         * Namespaces a mask asset against the layer's own pixels.
         *
         * Without it a mask made for an image layer would be stored under the same id as the image
         * and overwrite it — the layer would become its own mask.
         */
        const val MASK_PREFIX = "mask-"

        /** The platform's 48dp minimum, in screen pixels, before the zoom is divided out. */
        const val TOUCH_RADIUS = 24f

        /** A blank square canvas with one shape, so the editor has something to select on launch. */
        fun startingDocument(): Document = Document(
            id = DocumentId("scratch"),
            canvas = ir.pixellab.core.model.CanvasSpec(1080, 1080, background = Fill.Solid(Color.WHITE)),
            layers = listOf(
                Layer.Shape(
                    id = ir.pixellab.core.model.LayerId("shape-1"),
                    geometry = ShapeGeometry.Rectangle(Vec2(600f, 300f)),
                    name = "مستطیل",
                    transform = ir.pixellab.core.model.Transform(translation = Vec2(240f, 390f)),
                ),
            ),
            name = "سند تازه",
        )
    }
}
