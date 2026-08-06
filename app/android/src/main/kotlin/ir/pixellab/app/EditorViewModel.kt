package ir.pixellab.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.pixellab.core.canvas.CanvasGesture
import ir.pixellab.core.canvas.Handle
import ir.pixellab.core.editor.Collage
import ir.pixellab.core.editor.Editor
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.Look
import ir.pixellab.core.editor.wearing
import ir.pixellab.core.editor.withLook
import ir.pixellab.core.editor.withoutLook
import ir.pixellab.core.editor.Tool
import ir.pixellab.core.fonts.Typeface
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.CharacterStyle
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.with
import ir.pixellab.core.model.withTransform
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.withStyle
import ir.pixellab.core.text.StyleRuns
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

    /** The user's own saved grades. Read by the adjustment panel; written when they save one. */
    val lookStore = LookStore()

    val assets: AssetSource get() = assetStore.source

    val bounds = LayerMeasure(images = assetStore.sizes)

    /** The brush, and the stroke it currently has in progress. */
    val paint = PaintController(assetStore).also { controller ->
        // The selection is owned here, so the brush hands its coverage over rather than reaching for
        // it — which keeps the paint controller free of any knowledge of what a selection is.
        controller.onMaskStroke = { coverage, width, height -> paintMask(coverage, width, height) }
    }

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

    /**
     * How the user likes to work, as opposed to what they have drawn.
     *
     * Loaded before the editor is built, because the editor takes the snapping configuration and a
     * preference applied a frame later would mean the first drag of every launch ignored it.
     */
    private var settings: Preferences by mutableStateOf(Preferences.load(application))

    /** Read during composition, so a change to a preference redraws whatever depends on it. */
    val preferences: Preferences get() = settings

    private val editor = Editor(startingDocument(), bounds, snapConfig = settings.snap)

    var state: EditorState by mutableStateOf(editor.state)
        private set

    /**
     * The recovery copy, kept apart from the user's own saves.
     *
     * A save is a decision and names a file; an auto-save is insurance against the process being
     * killed, which on Android happens without warning. Writing over the user's file on a timer
     * would make an accidental edit permanent while they were looking away.
     */
    val autoSave = AutoSave(application, viewModelScope) { currentProject() }

    init {
        // A smart object is measured through its source, and the source is wherever the document
        // currently has it — a captured copy would size an instance from a layer that has since
        // been resized.
        bounds.sources = { id -> editor.state.document.findLayer(id) }
        autoSave.start(settings.autoSaveMinutes)

        // Off the main thread from the first frame: the canvas has to draw before the library is
        // known, and text arrives when it is.
        viewModelScope.launch {
            // Before the fonts, because a built-in style is only built-in if the texture it names
            // is already decoded the first time someone taps it. It is a handful of small files
            // and it settles long before anyone reaches the library.
            assetStore.loadBundled(application.assets)

            fontStore.rescan()
            // The chrome measures through this too, so the handles would otherwise keep the
            // placeholder box the text was measured with before the scan landed. The screen
            // recomposes off [fonts] changing identity, which redraws them.
            bounds.fonts = fontStore.resolver

            // After the fonts because nobody reaches for a saved grade in the first second, and a
            // few small files should not sit in front of the thing the canvas needs to draw.
            lookStore.load(application)
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
        // Marked here rather than at each call site, because every document change goes through
        // this one door — and a flag set in twenty places is a flag that is missed in one.
        autoSave.dirty = true
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

    /**
     * How many steps there are, and where along them the document currently sits.
     *
     * Exposed as plain numbers so the history strip can draw one chip per step without knowing what
     * a step *is* — half of them are document edits and half are painted pixels, and the strip has
     * nothing useful to say about the difference.
     */
    val historyLength: Int get() = steps.size + undone.size

    val historyPosition: Int get() = steps.size

    /**
     * Moves the document to a given point in the history.
     *
     * By replaying [undo] and [redo] rather than by restoring a snapshot. Painted pixels live on
     * their own buffer and are re-applied by the paint controller, so a snapshot of the document
     * alone would silently drop every stroke between here and there — which is the same defect the
     * interleaved stack exists to prevent.
     */
    fun jumpTo(position: Int) {
        val target = position.coerceIn(0, historyLength)
        while (steps.size > target) undo()
        while (steps.size < target) redo()
    }

    /**
     * True while the user is holding the compare control and looking at where they started.
     *
     * Only the tint of that one icon reads it. It exists so the button can show that it is doing
     * something — without it, a held compare on a document whose edits are subtle looks broken.
     */
    var comparing: Boolean by mutableStateOf(false)
        private set

    private var comparedFrom = 0

    /**
     * Press-and-hold "before".
     *
     * By walking the history to zero and back rather than by keeping a snapshot, because a snapshot
     * of the document would be a lie: painted pixels live on the asset raster and are re-applied by
     * the paint controller, so a document captured at load and re-rendered later would show every
     * brush stroke made since. [jumpTo] already replays both kinds of step, which is the whole
     * reason it exists, and reusing it means compare cannot drift away from undo.
     *
     * The cost is replaying the session on press. That is bounded by the history length, each step
     * is either a document swap or a bitmap swap, and it happens once per press rather than per
     * frame — which is why this is a hold rather than a toggle.
     */
    fun beginCompare() {
        if (comparing) return
        comparedFrom = historyPosition
        if (comparedFrom == 0) return
        comparing = true
        jumpTo(0)
    }

    /** Puts the document back exactly where the press found it. */
    fun endCompare() {
        if (!comparing) return
        jumpTo(comparedFrom)
        comparing = false
    }

    // ---- a sheet as a transaction ------------------------------------------------------------------

    /**
     * Where the history stood when the open sheet appeared.
     *
     * Every sheet in this app writes its change the moment a control moves, and the only way back
     * was undo — so a user who opened a panel, moved four sliders and thought better of it had to
     * press undo four times and count. Every one of the eight reference apps instead frames a tool
     * as a transaction: ✕ and ✓ with a live preview between them, and nothing has happened until the
     * tick. That framing is most of what makes those apps read as easy, and it costs no algorithm.
     */
    private var sheetBaseline = 0

    /** Called when a sheet appears, so cancel knows how far back "before this" is. */
    fun noteSheetOpened() {
        sheetBaseline = historyPosition
    }

    /** Whether anything has happened since the sheet opened — the tick is only meaningful if so. */
    val sheetHasChanges: Boolean get() = historyPosition > sheetBaseline

    /**
     * ✕ — puts the document back to where the sheet found it, then closes.
     *
     * **It walks back over document edits only, and stops at the first painted stroke.** A brush
     * stroke made while the brush *settings* sheet was open is the user's work, not the sheet's, and
     * a cancel that ate it would be far worse than no cancel at all. Stopping there costs the rest
     * of the revert, which is the correct trade: the user can still undo, and nothing is destroyed.
     */
    fun cancelSheet() {
        while (steps.size > sheetBaseline && steps.lastOrNull() == Step.Edit) undo()
        act { closeSheet() }
        sheetBaseline = historyPosition
    }

    /** ✓ — keeps everything and closes. The changes are already in the document and on the stack. */
    fun confirmSheet() {
        act { closeSheet() }
        sheetBaseline = historyPosition
    }

    val current: Editor get() = editor

    fun act(body: Editor.() -> Unit) = edit(body)

    override fun onCleared() {
        autoSave.stop()
        super.onCleared()
    }

    /**
     * Applies a preference and writes it down.
     *
     * Deliberately outside [edit]: a preference is not a document change, and putting it on the
     * undo stack would mean undoing back past the moment snapping was turned off silently turned
     * it on again.
     */
    fun setPreferences(next: Preferences) {
        val before = settings.autoSaveMinutes
        settings = next.sane()
        editor.snapConfig = settings.snap
        if (settings.autoSaveMinutes != before) autoSave.start(settings.autoSaveMinutes)
        Preferences.save(getApplication(), settings)
    }

    /**
     * Switches the brush onto a generated tip.
     *
     * Registered under a stable id derived from the kind, so choosing chalk twice reuses the one
     * tile instead of filling the asset store with identical copies — and so a saved preset still
     * finds its tip when the document is reopened.
     */
    fun useGeneratedTip(kind: ir.pixellab.core.imaging.Procedural.Tip) {
        val id = ir.pixellab.core.model.AssetId("tip-${kind.name.lowercase()}")
        if (assetStore.source.load(id) == null) {
            assetStore.put(id, ir.pixellab.core.imaging.Procedural.tip(kind, TEXTURE_SIZE, seed = kind.ordinal).toCoverageImage())
        }
        paint.preset = paint.preset.copy(tip = ir.pixellab.core.paint.BrushTip.Sampled(id))
    }

    /** The same for a pattern tile, returning the asset a `Fill.Pattern` should point at. */
    fun registerPattern(kind: ir.pixellab.core.imaging.Procedural.Pattern): ir.pixellab.core.model.AssetId {
        val id = ir.pixellab.core.model.AssetId("pattern-${kind.name.lowercase()}")
        if (assetStore.source.load(id) == null) {
            assetStore.put(
                id,
                ir.pixellab.core.imaging.Procedural
                    .pattern(kind, TEXTURE_SIZE, seed = kind.ordinal)
                    .toCoverageImage(),
            )
            paint.bumpGeneration()
        }
        return id
    }

    /**
     * The 3D settings of the selected text layer, or the defaults it would start from.
     *
     * Defaults rather than null, so the panel can be filled in and adjusted *before* the layer has
     * ever been rendered in 3D — otherwise the first thing a user sees is a screen of empty
     * controls that only come alive after pressing a button they have no reason to trust yet.
     */
    fun geometry3DOf(id: LayerId): ir.pixellab.core.model.Geometry3D {
        val layer = state.document.findLayer(id) as? Layer.Text
        return layer?.geometry3D ?: defaultGeometry(layer)
    }

    fun setGeometry3D(id: LayerId, geometry: ir.pixellab.core.model.Geometry3D) = edit {
        replaceLayer(id) { (it as Layer.Text).copy(geometry3D = geometry) }
    }

    /**
     * Bakes the selected text layer as 3D and drops it in as an image layer above the text.
     *
     * The text layer is kept and hidden rather than replaced. A 3D bake is a one-way trip — the
     * pixels cannot be turned back into a string — and losing the editable text along with it is
     * the sort of thing a user discovers an hour later when the client changes a word.
     */
    /**
     * Points a 3D layer at an environment map, or back at the generated studio.
     *
     * The pixels go into the asset store under an id derived from the file name, so the document
     * refers to a name rather than carrying a megabyte of photograph, and the same map chosen twice
     * is one asset.
     *
     * @return false when the file could not be decoded, which the caller reports rather than
     *   silently leaving the layer in the studio.
     */
    fun setEnvironment(id: LayerId, image: ir.pixellab.core.codec.RasterImage?, name: String): Boolean {
        val geometry = geometry3DOf(id)
        if (image == null) {
            setGeometry3D(id, geometry.copy(lighting = geometry.lighting.copy(environment = null)))
            return true
        }
        val asset = ir.pixellab.core.model.AssetId("hdr:$name")
        assetStore.put(asset, image)
        setGeometry3D(id, geometry.copy(lighting = geometry.lighting.copy(environment = asset)))
        return true
    }

    suspend fun render3D(id: LayerId, supersample: Int = 2): Boolean {
        val layer = state.document.findLayer(id) as? Layer.Text ?: return false
        if (!ir.pixellab.engine.android.TextTo3D.canRender(layer, fonts)) return false

        val geometry = geometry3DOf(id)
        val canvas = state.document.canvas
        val image = withContext(kotlinx.coroutines.Dispatchers.Default) {
            ir.pixellab.engine.android.TextTo3D.render(
                layer = layer,
                geometry = geometry,
                fonts = fonts,
                width = canvas.width,
                height = canvas.height,
                supersample = supersample,
                assets = assetStore.source,
            )
        } ?: return false

        edit {
            // Stored before the layer is added so the settings survive on the text layer itself,
            // which is what makes a re-bake after a tweak give the same framing.
            replaceLayer(id) { (it as Layer.Text).copy(geometry3D = geometry, visible = false) }
            val newId = nextLayerId("dimensional")
            val asset = ir.pixellab.core.model.AssetId(newId.value)
            assetStore.put(asset, image)
            paint.bumpGeneration()
            addLayer(
                Layer.Image(
                    id = newId,
                    asset = asset,
                    name = "${layer.name} — سه‌بعدی",
                    // The text's effects come across; its fill does not.
                    //
                    // A shadow, a glow or an outer stroke was set against the *shape of the word*,
                    // and the baked layer has that same silhouette — so dropping them means a user
                    // who had built the look they wanted watches it disappear the moment they press
                    // build, with nothing saying why. The fill is the opposite case: it painted the
                    // glyphs, and the render has already been painted by its own materials, so
                    // carrying it over would lay a flat colour across the finished letter.
                    style = layer.style.copy(fill = Fill.Solid(Color.TRANSPARENT), fillOpacity = 1f),
                ),
            )
        }
        return true
    }

    /**
     * Sensible depth and bevel for a layer that has never been in 3D.
     *
     * Scaled to the type size rather than fixed, because a fixed twenty pixels of depth is a slab
     * on a caption and invisible on a poster headline.
     */
    private fun defaultGeometry(layer: Layer.Text?): ir.pixellab.core.model.Geometry3D {
        val size = layer?.spec?.size ?: DEFAULT_TEXT_SIZE
        return ir.pixellab.core.model.Geometry3D(
            depth = size * DEPTH_FRACTION,
            bevelSize = size * BEVEL_FRACTION,
        )
    }

    /**
     * The canvas's resolution, in pixels per inch.
     *
     * Metadata rather than a resample: it decides the physical size of a PDF page and the number a
     * print shop reads, and changing it must not touch a single pixel. Users expect it to resize
     * the file and it does not, which is why the control says so.
     */
    fun setCanvasDpi(dpi: Int) = edit { setCanvasDpi(dpi.coerceIn(MIN_DPI, MAX_DPI)) }

    /**
     * Re-reads the user's own folders.
     *
     * Fonts and everything else in one call, because from the user's side it is one action: they
     * copied files in and want the app to notice. Splitting it into a button per folder would make
     * them press five.
     */
    fun rescanAssets() {
        viewModelScope.launch {
            fontStore.rescan()
            bounds.fonts = fontStore.resolver
        }
    }

    fun setSnapEnabled(enabled: Boolean) {
        editor.setSnapEnabled(enabled)
        state = editor.state
        setPreferences(preferences.copy(snapEnabled = enabled))
    }

    /**
     * The same as [act] for callers that need the editor's answer back.
     *
     * Adding a guide returns its index, and the ruler drag needs it to keep moving the same guide
     * for the rest of the gesture — reaching into [current] instead would skip the state republish
     * and the canvas would not redraw.
     */
    fun <T> mutate(body: Editor.() -> T): T = edit(body)

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
                select.begin(canvasPoint(gesture.position), canvas.width, canvas.height)
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
                select.begin(at, canvas.width, canvas.height)
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

        // An armed eyedropper takes the tap before anything else does, and disarms itself: the
        // user asked for one colour, not for a mode they then have to remember to leave.
        if (armingEyedropper) {
            val at = when (gesture) {
                is CanvasGesture.Tap -> gesture.position
                is CanvasGesture.DragEnd -> gesture.position
                else -> null
            }
            if (at != null) {
                pickColor(canvasPoint(at))?.let { paint.preset = paint.preset.copy(color = it) }
                armingEyedropper = false
            }
            return true
        }

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
                if (state.drag != null) {
                    dragTo(gesture.position)
                } else {
                    framedByHand = true
                    panViewport(gesture.delta)
                }
            is CanvasGesture.DragEnd -> endDrag()

            is CanvasGesture.TransformStart -> Unit
            is CanvasGesture.Transform -> {
                framedByHand = true
                transformViewport(gesture.pan, gesture.scaleFactor, gesture.rotationDegrees, gesture.pivot)
            }
            CanvasGesture.TransformEnd -> endDrag()

            // Routed back out rather than handled here: painted pixels are on the same stack.
            CanvasGesture.Undo -> Unit
            CanvasGesture.Redo -> Unit
        }
    }

    fun onScreenSize(size: Vec2) = edit { resizeScreen(size) }

    /**
     * How tall the fixed bars are, in screen pixels.
     *
     * The canvas fills the whole screen and the bars are drawn over it, so without this the artboard
     * is centred behind them — sitting low, with its bottom edge under the ribbon. Reported from the
     * screen because the bars are laid out in dp and only the screen knows the density.
     */
    fun onChromeInsets(top: Float, bottom: Float) = edit {
        setChrome(top, bottom)
        // Only if the user has not framed the document themselves. Re-fitting after they have
        // zoomed in on a letterform would throw their work away because a bar changed height.
        if (!framedByHand) fitCanvas()
    }

    /** Set the moment a gesture moves the camera, so an automatic fit never overrides the user. */
    private var framedByHand = false

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

    /**
     * Chooses a crop proportion and lays the frame down in one go.
     *
     * One call rather than "set the ratio" and then "now draw one", because pressing 1:1 and seeing
     * nothing happen is the version of this that gets reported as broken. Passing null goes back to
     * freeform and leaves whatever frame is already there — the user is about to drag it, and
     * clearing it would throw away the thing they were adjusting.
     */
    fun cropRatio(ratio: ir.pixellab.core.editor.AspectRatio?) {
        select.ratio = ratio
        if (ratio != null) {
            val canvas = state.document.canvas
            select.frame(canvas.width, canvas.height)
            paint.selection = select.selection
        }
    }

    /** Turns the current crop frame on its side — landscape to portrait without re-choosing. */
    fun flipCropRatio() {
        val current = select.ratio ?: return
        cropRatio(current.flipped())
    }

    /** The colour behind everything — Photoshop's background layer, without the layer. */
    fun setCanvasBackground(fill: Fill?) = edit { setCanvasBackground(fill) }

    // ---- arranging -------------------------------------------------------------------------------

    /**
     * Sets a layer's placement from typed numbers.
     *
     * Position is the *bounding box's* top-left, not the transform's translation, because that is
     * what the user is reading off the canvas. On a rotated or scaled layer the two differ, and
     * showing the raw translation would put a number in the field that does not match the ruler.
     */
    fun setPlacement(
        id: LayerId,
        left: Float?,
        top: Float?,
        rotation: Float?,
        skewX: Float? = null,
        skewY: Float? = null,
    ) = edit {
        val layer = state.document.findLayer(id) ?: return@edit
        val limit = ir.pixellab.core.canvas.Handles.SKEW_LIMIT
        val skewed = layer.transform.copy(
            rotation = rotation ?: layer.transform.rotation,
            skew = Vec2(
                (skewX ?: layer.transform.skew.x).coerceIn(-limit, limit),
                (skewY ?: layer.transform.skew.y).coerceIn(-limit, limit),
            ),
        )
        // The box is measured *after* the rotation and skew are applied, because slanting a layer
        // moves its bounding box even when nothing was translated. Measuring first and translating
        // by the old delta would leave the typed X and Y off by however much the skew shifted it.
        val box = ir.pixellab.core.canvas.Handles.canvasBounds(bounds.of(layer), skewed)
        val delta = Vec2((left ?: box.left) - box.left, (top ?: box.top) - box.top)
        replaceLayer(id) {
            it.withTransform(skewed.copy(translation = it.transform.translation + delta))
        }
    }

    /**
     * Adds a Color Lookup layer pointing at a table that was just read from a file.
     *
     * The asset goes in first and the layer second, because a layer naming an asset that is not
     * there yet renders as nothing for a frame — which reads as the import having failed.
     *
     * The id is derived from the name so the same table imported twice is the same asset rather
     * than two copies of half a megabyte each, and prefixed so it can never collide with a mask or
     * a photograph the project brought with it.
     */
    fun addColorLookup(strip: ir.pixellab.core.codec.RasterImage, name: String): LayerId {
        val asset = ir.pixellab.core.model.AssetId("lut:$name")
        assetStore.put(asset, strip)
        return addAdjustment(ir.pixellab.core.model.Adjustment.ColorLookup(asset), name)
    }

    /** Points an existing Color Lookup layer at a different table. */
    fun setColorLookup(id: LayerId, strip: ir.pixellab.core.codec.RasterImage, name: String) {
        val asset = ir.pixellab.core.model.AssetId("lut:$name")
        assetStore.put(asset, strip)
        val current = state.document.findLayer(id) as? Layer.AdjustmentLayer ?: return
        val lookup = current.adjustment as? ir.pixellab.core.model.Adjustment.ColorLookup ?: return
        setAdjustment(id, lookup.copy(asset = asset))
    }

    /**
     * Photoshop's Edit ▸ Transform ▸ Perspective, as one number.
     *
     * The full command is a corner drag, and dragging one corner moves its pair symmetrically —
     * that symmetry is what makes it *perspective* rather than *distort*, and it is why the whole
     * gesture has one degree of freedom. So it is one slider: positive narrows the top edge and
     * widens the bottom, which is the plane tilting away from the viewer.
     *
     * Stated in canvas coordinates because that is what the model's four corners are, and measured
     * against the layer's own box so the same value means the same tilt on a caption and on a
     * headline.
     */
    fun setPerspective(id: LayerId, amount: Float) = edit {
        val layer = state.document.findLayer(id) ?: return@edit
        val box = bounds.of(layer)
        replaceLayer(id) {
            if (amount == 0f) {
                it.withTransform(it.transform.copy(perspective = null))
            } else {
                val inset = box.width * 0.5f * amount.coerceIn(-0.9f, 0.9f)
                it.withTransform(
                    it.transform.copy(
                        perspective = ir.pixellab.core.model.Perspective(
                            topLeft = Vec2(box.left + inset, box.top),
                            topRight = Vec2(box.right - inset, box.top),
                            bottomRight = Vec2(box.right, box.bottom),
                            bottomLeft = Vec2(box.left, box.bottom),
                        ),
                    ),
                )
            }
        }
    }

    /** The box the placement fields show, in canvas units. */
    fun placementOf(id: LayerId): ir.pixellab.core.model.Rect? {
        val layer = state.document.findLayer(id) ?: return null
        return ir.pixellab.core.canvas.Handles.canvasBounds(bounds.of(layer), layer.transform)
    }

    /**
     * Merges layers into one picture.
     *
     * The pixels have to be rendered, which needs the GL thread, so the caller supplies the render
     * rather than this holding a view. Each merged layer is drawn *with everything else hidden* —
     * rendering the whole document and cropping would bake in whatever was underneath.
     *
     * @param render draws a document and hands back its pixels, or null if the canvas is not ready.
     * @return true when the document changed.
     */
    suspend fun mergeLayers(
        ids: List<LayerId>,
        name: String,
        render: suspend (Document) -> ir.pixellab.core.codec.RasterImage?,
    ): Boolean {
        if (ids.size < 2) return false
        val document = state.document
        val onlyThese = document.copy(
            layers = document.layers.map { it.with(visible = it.id in ids && it.visible) },
        )
        val pixels = render(onlyThese) ?: return false

        val id = edit { nextLayerId("merged") }
        val asset = ir.pixellab.core.model.AssetId(id.value)
        assetStore.put(asset, pixels)
        paint.bumpGeneration()
        // At the origin at natural size: the render already covers the whole canvas, so any
        // transform on it would move pixels that are already where they belong.
        return edit {
            replaceWithMerged(ids, Layer.Image(id = id, asset = asset, name = name))
        }
    }

    /** Merge down: this layer and the one beneath it. */
    suspend fun mergeDown(render: suspend (Document) -> ir.pixellab.core.codec.RasterImage?): Boolean {
        val id = state.selection.primary ?: return false
        val pair = editor.mergeableBelow(id)
        return mergeLayers(pair, name = "ادغام‌شده", render = render)
    }

    suspend fun mergeVisible(render: suspend (Document) -> ir.pixellab.core.codec.RasterImage?): Boolean =
        mergeLayers(editor.visibleLayers(), name = "ادغام مرئی‌ها", render = render)

    /**
     * Rasterises a layer: replaces it with its own pixels.
     *
     * The step that turns an editable thing into a picture, and the only way an effect stack ever
     * becomes something a brush can paint over. Like text-to-shape it is a one-way door, and the
     * sheet says so before it happens.
     */
    suspend fun rasterize(render: suspend (Document) -> ir.pixellab.core.codec.RasterImage?): Boolean {
        val id = state.selection.primary ?: return false
        val document = state.document
        val alone = document.copy(layers = document.layers.map { it.with(visible = it.id == id) })
        val pixels = render(alone) ?: return false

        val asset = ir.pixellab.core.model.AssetId("raster-${id.value}")
        assetStore.put(asset, pixels)
        paint.bumpGeneration()
        val name = document.findLayer(id)?.name ?: "لایه"
        edit { replaceLayer(id) { Layer.Image(id = id, asset = asset, name = name) } }
        bounds.invalidate(id)
        return true
    }

    // ---- colour ----------------------------------------------------------------------------------

    /**
     * The eyedropper: the colour under a canvas point.
     *
     * Samples the selected image layer rather than the composited screen, and the sheet says which.
     * Reading the composite means a round trip to the GL thread for a single pixel, and it would
     * also pick up the checkerboard behind a transparent area — which is not a colour the user
     * can see in their document.
     *
     * @param radius averages a small square, so a sample on a noisy photograph gives the colour
     *   the eye reads rather than one stray pixel of sensor noise.
     */
    fun pickColor(at: Vec2, radius: Int = SAMPLE_RADIUS): Color? {
        val layer = state.primaryLayer as? Layer.Image ?: return null
        val image = assetStore.source.load(layer.asset) ?: return null
        // Through the layer's own transform, so a moved or scaled photograph is sampled where the
        // finger actually is rather than where it would have been at the origin.
        val local = at - layer.transform.translation
        val scale = layer.transform.scale
        val x = (local.x / (if (scale.x == 0f) 1f else scale.x)).toInt()
        val y = (local.y / (if (scale.y == 0f) 1f else scale.y)).toInt()

        var r = 0L
        var g = 0L
        var b = 0L
        var a = 0L
        var count = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val sx = x + dx
                val sy = y + dy
                if (sx !in 0 until image.width || sy !in 0 until image.height) continue
                val pixel = image[sx, sy]
                a += (pixel ushr 24) and 0xFF
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
                count++
            }
        }
        if (count == 0) return null
        return Color(
            r.toFloat() / count / MAX_CHANNEL,
            g.toFloat() / count / MAX_CHANNEL,
            b.toFloat() / count / MAX_CHANNEL,
            a.toFloat() / count / MAX_CHANNEL,
        )
    }

    /** True while the next canvas tap samples a colour rather than doing what the tool does. */
    var armingEyedropper: Boolean by mutableStateOf(false)

    // ---- the two colours the toolbar reads from --------------------------------------------------

    /**
     * Foreground and background — Photoshop's pair, absent from this project until now.
     *
     * Kept beside the selection rather than in the document: it is a tool setting, so it must survive
     * an undo and must not be written into a saved file. Photoshop keeps it in preferences for the
     * same reason.
     */
    var palette: ir.pixellab.core.editor.Palette by mutableStateOf(
        ir.pixellab.core.editor.Palette.DEFAULT,
    )
        private set

    /** Which slot a colour picker is currently editing. */
    var editingSlot: ir.pixellab.core.editor.Palette.Slot by mutableStateOf(
        ir.pixellab.core.editor.Palette.Slot.FOREGROUND,
    )

    /**
     * Sets the slot being edited and keeps the brush in step.
     *
     * The brush's own colour is not a second source of truth — it mirrors the foreground, which is
     * what makes picking a colour anywhere reach the brush without every sheet knowing about every
     * other one.
     */
    fun setPaletteColor(color: Color) {
        palette = palette.with(editingSlot, color)
        if (editingSlot == ir.pixellab.core.editor.Palette.Slot.FOREGROUND) {
            paint.preset = paint.preset.copy(color = color)
        }
    }

    /** Photoshop's X, and pressed constantly: masking alternates between adding and removing. */
    fun swapPalette() {
        palette = palette.swapped()
        paint.preset = paint.preset.copy(color = palette.foreground)
    }

    /** Photoshop's D — black on white, which is what every mask starts from. */
    fun resetPalette() {
        palette = palette.reset()
        paint.preset = paint.preset.copy(color = palette.foreground)
    }

    // ---- Quick Mask ------------------------------------------------------------------------------

    /**
     * Photoshop's Q: edit the selection as if it were a picture.
     *
     * The reason it exists is that marching ants cannot show a *partial* selection. A feathered edge,
     * a gradient mask, the soft boundary every tool in this app produces — all of it renders as one
     * line at the fifty per cent mark, and the user is left guessing about the half of the
     * information that matters most. Quick Mask draws the coverage itself.
     *
     * And once it is visible it is paintable, which is the second half: the brush becomes a selection
     * tool with all its own machinery — soft tips, flow, pressure — and *that* is how a selection
     * gets from good to exact.
     */
    var quickMask: Boolean by mutableStateOf(false)
        private set

    fun toggleQuickMask() {
        val canvas = state.document.canvas
        if (!quickMask && select.selection == null) {
            // Entering with nothing selected starts from an empty mask rather than a full one, so
            // the first brush stroke *adds*. Starting full would mean the first stroke appeared to
            // do nothing until the user found the eraser.
            select.use(ir.pixellab.core.paint.PixelSelection.nothing(canvas.width, canvas.height))
        }
        quickMask = !quickMask
        paint.quickMask = quickMask
        paint.selection = if (quickMask) null else select.selection
    }

    /** Paints into the mask instead of the layer, using the palette's own black-and-white rule. */
    fun paintMask(coverage: FloatArray, width: Int, height: Int) {
        val existing = select.selection ?: ir.pixellab.core.paint.PixelSelection.nothing(width, height)
        if (existing.width != width || existing.height != height) return
        // White adds, black subtracts — the rule Quick Mask is defined by, and the reason the
        // palette had to exist before this could.
        val adding = luminance(palette.foreground) >= 0.5f
        val bytes = existing.coverage.copyOf()
        for (i in coverage.indices) {
            val w = coverage[i]
            if (w <= 0f) continue
            val was = bytes[i].toInt() and 0xFF
            val target = if (adding) 255 else 0
            bytes[i] = (was + (target - was) * w).toInt().coerceIn(0, 255).toByte()
        }
        // A fresh selection rather than a mutated one: bounds are computed at construction, and a
        // selection whose bounds disagree with its coverage is one every downstream tool trusts.
        select.set(ir.pixellab.core.paint.PixelSelection(width, height, bytes))
    }

    private fun luminance(c: Color) = 0.2126f * c.r + 0.7152f * c.g + 0.0722f * c.b

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

    suspend fun motionBlur(angle: Float, distance: Float) = transform { image ->
        ir.pixellab.engine.android.PixelFilters.motion(image, angle, distance, select.selection)
    }

    suspend fun surfaceBlur(radius: Float, threshold: Float) = transform { image ->
        ir.pixellab.engine.android.PixelFilters.surface(image, radius, threshold, paint.selection)
    }

    suspend fun lensBlur(radius: Float, blades: Int) = transform { image ->
        ir.pixellab.engine.android.PixelFilters.lens(image, radius, blades, selection = select.selection)
    }

    /**
     * Tilt-shift or iris blur, focused on the selection when there is one.
     *
     * Not narrowed by the selection the way the other filters are — the whole point is a gradual
     * transition from sharp to blurred, and clipping that to a selection's edge would put a hard
     * boundary in the middle of the softest thing in the picture.
     */
    suspend fun gradientBlur(
        shape: ir.pixellab.core.imaging.GradientBlur.Shape,
        radius: Float,
        focus: Float,
        transition: Float,
    ) {
        val box = select.selection?.bounds
        transform { image ->
            val centre = if (box != null) {
                Vec2((box.left + box.right) / 2f, (box.top + box.bottom) / 2f)
            } else {
                Vec2(image.width / 2f, image.height / 2f)
            }
            ir.pixellab.engine.android.PixelFilters
                .gradientBlur(image, shape, radius, focus, transition, centre = centre)
        }
    }

    suspend fun unsharpMask(amount: Float, radius: Float, threshold: Float) = transform { image ->
        ir.pixellab.engine.android.PixelFilters.sharpen(image, amount, radius, threshold, select.selection)
    }

    /** Haze removal — the one control in a photo app's adjust row that a curve cannot imitate. */
    suspend fun dehaze(strength: Float) = transform { image ->
        ir.pixellab.engine.android.PixelFilters.dehaze(image, strength, select.selection)
    }

    /**
     * The filter gallery — oil paint, watercolour, coloured pencil, crystallize.
     *
     * Destructive, like every entry on the pixel-filter tab, and honestly so: these rewrite the
     * picture's structure rather than its colours, and there is no per-pixel function a
     * non-destructive adjustment layer could hold that would reproduce them.
     */
    suspend fun artistic(style: ir.pixellab.core.editor.ArtStyle, strength: Float) = transform { image ->
        ir.pixellab.engine.android.PixelFilters.artistic(image, style, strength, select.selection)
    }

    /** Texture, clarity or brilliance — one operation at three sizes of detail. */
    suspend fun localContrast(scale: ir.pixellab.core.imaging.LocalContrast.Scale, amount: Float) =
        transform { image ->
            ir.pixellab.engine.android.PixelFilters.localContrast(image, scale, amount, select.selection)
        }

    /**
     * God rays from a point, defaulting to the middle of the selection.
     *
     * Not narrowed by the selection: rays leave the light and cross the whole frame, and clipping
     * them to the marquee that located the sun would cut every one of them off at its own source.
     */
    suspend fun lightRays(threshold: Float, length: Float, intensity: Float) {
        val box = select.selection?.bounds
        transform { image ->
            val at = if (box != null) {
                Vec2((box.left + box.right) / 2f, (box.top + box.bottom) / 2f)
            } else {
                Vec2(image.width / 2f, image.height / 2f)
            }
            ir.pixellab.engine.android.PixelFilters.lightRays(image, at, threshold, length, intensity)
        }
    }

    /**
     * Opens the shadows and pulls back the highlights, each on a local mask.
     *
     * Here among the filters rather than among the adjustment layers, and that is not a shortcut:
     * the correction at a pixel depends on the pixels a radius away, which is not something a
     * compositing shader can read. Photoshop puts it under Image for the same reason.
     */
    suspend fun shadowHighlight(
        shadowAmount: Float,
        highlightAmount: Float,
        radius: Float,
        midtoneContrast: Float = 0f,
    ) = transform { image ->
        ir.pixellab.engine.android.PixelFilters.shadowHighlight(
            source = image,
            shadowAmount = shadowAmount,
            highlightAmount = highlightAmount,
            radius = radius,
            midtoneContrast = midtoneContrast,
            selection = select.selection,
        )
    }

    suspend fun reduceNoise(strength: Float, colorStrength: Float, preserveDetail: Float) =
        transform { image ->
            ir.pixellab.engine.android.PixelFilters
                .reduceNoise(image, strength, colorStrength, preserveDetail, select.selection)
        }

    suspend fun dustAndScratches(radius: Int, threshold: Float) = transform { image ->
        ir.pixellab.engine.android.PixelFilters.dustAndScratches(image, radius, threshold, select.selection)
    }

    suspend fun smartSharpen(amount: Float, radius: Float, lens: Boolean) = transform { image ->
        ir.pixellab.engine.android.PixelFilters
            .smartSharpen(image, amount, radius, lens, selection = select.selection)
    }

    /**
     * High pass, onto a copy of the layer set to Overlay.
     *
     * Onto a copy because a high pass applied in place destroys the picture — it *is* the detail
     * with the picture subtracted. The only useful form of it is as a layer over the original, and
     * building that arrangement is the whole operation rather than a convenience.
     */
    suspend fun highPass(radius: Float) {
        val layer = state.primaryLayer as? Layer.Image ?: return
        val source = assetStore.source.load(layer.asset) ?: return
        val detail = withContext(kotlinx.coroutines.Dispatchers.Default) {
            ir.pixellab.engine.android.PixelFilters.highPass(source, radius)
        }
        edit {
            val id = nextLayerId("highpass")
            val asset = ir.pixellab.core.model.AssetId(id.value)
            assetStore.put(asset, detail)
            paint.bumpGeneration()
            addLayer(
                layer.copy(
                    id = id,
                    asset = asset,
                    name = "جزئیات",
                    blendMode = ir.pixellab.core.model.BlendMode.OVERLAY,
                    mask = null,
                ),
            )
        }
    }

    suspend fun vignette(amount: Float) = transform { image ->
        ir.pixellab.engine.android.PixelFilters.vignette(image, amount)
    }

    suspend fun pixelate(blockSize: Int) = transform { image ->
        ir.pixellab.engine.android.PixelFilters.pixelate(image, blockSize, select.selection)
    }

    /**
     * Film grain.
     *
     * The seed is bumped per application rather than fixed, so grain applied twice is not the same
     * pattern laid over itself — which would double its contrast instead of adding texture.
     */
    suspend fun grain(amount: Float, monochrome: Boolean) {
        val seed = grainSeed++
        transform { image ->
            ir.pixellab.engine.android.PixelFilters.grain(image, amount, monochrome, seed, select.selection)
        }
    }

    private var grainSeed = 0

    // ---- measuring -------------------------------------------------------------------------------

    /**
     * The tone distribution of the selected image layer.
     *
     * Computed on demand rather than kept live: it costs a full pass over the pixels, and a
     * histogram recomputed every frame while a slider is dragged would be the slowest thing on
     * screen for a readout nobody is watching mid-drag.
     */
    fun histogram(channel: ir.pixellab.core.imaging.HistogramChannel): ir.pixellab.core.imaging.Histogram? {
        val image = sampledPixels() ?: return null
        return ir.pixellab.core.imaging.Histogram.of(image.pixels, channel)
    }

    /**
     * Adds a Levels layer set from the image's own histogram — Photoshop's Auto Levels.
     *
     * As an adjustment *layer* rather than baked into the pixels, so it stays adjustable: auto is a
     * starting point that is usually nearly right and occasionally wrong, and one that could not be
     * nudged afterwards would be worse than none.
     */
    fun autoLevels(): Boolean {
        val histogram = histogram(ir.pixellab.core.imaging.HistogramChannel.LUMINANCE) ?: return false
        val (black, white) = ir.pixellab.core.imaging.Histogram.autoLevels(histogram) ?: return false
        addAdjustment(
            ir.pixellab.core.model.Adjustment.Levels(
                inputBlack = black / MAX_CHANNEL,
                inputWhite = white / MAX_CHANNEL,
            ),
            name = "سطوح خودکار",
        )
        return true
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
            // Rebuilt per call rather than held. These networks are hundreds of megabytes of native
            // memory; keeping one resident between cut-outs would make this app the first thing the
            // system kills, and reloading costs a second on an operation the user asked for.
            cutout().select(image.pixels, image.width, image.height, sensitivity)
        }
        if (found.isEmpty) return false
        select.use(found)
        paint.selection = select.selection
        return true
    }

    /**
     * The cut-out path, with a model behind it if the user has installed one.
     *
     * Looked up each time so a model dropped into the folder mid-session is picked up without a
     * restart — which is how it will actually be installed, since the transfer happens outside the
     * app and there is no event to react to.
     */
    private fun cutout(): ir.pixellab.core.ai.SubjectCutout {
        val directory = AssetKind.MODELS.directoryIn(getApplication())
        return ir.pixellab.core.ai.SubjectCutout(
            ir.pixellab.engine.android.OnnxSegmentation.bestIn(directory),
        )
    }

    /** What the settings screen says is answering: the model's name, or the classical path. */
    fun cutoutDescription(): String = cutout().describe

    // ---- faces -----------------------------------------------------------------------------------

    /**
     * The face detector, created once and kept.
     *
     * Unlike the segmentation model, this one is bundled in the APK rather than installed by the
     * user, so there is nothing to re-check per call — and construction loads a 3.7 MB graph, which
     * is not something to repeat on every slider drag.
     */
    private val faceModel: ir.pixellab.core.ai.FaceModel? by lazy {
        ir.pixellab.engine.android.MediaPipeFaceModel.create(getApplication())
    }

    /** The faces found in the selected layer, cached until the layer's pixels change. */
    var faces: List<ir.pixellab.core.ai.FaceLandmarks> by mutableStateOf(emptyList())
        private set

    /** Null until a detection has been run; then a message the panel can show. */
    var faceOutcome: String? by mutableStateOf(null)
        private set

    var detecting: Boolean by mutableStateOf(false)
        private set

    /**
     * Finds the faces in the selected image layer.
     *
     * Explicit rather than automatic on selection. Detection is a hundred-millisecond pass over a
     * full-resolution photograph, and running it every time a layer is touched would tax every user
     * for a feature most of them are not using at that moment.
     */
    suspend fun detectFaces() {
        val layer = state.primaryLayer as? Layer.Image
        if (layer == null) {
            faceOutcome = "یک لایهٔ تصویر انتخاب کنید"
            return
        }
        val model = faceModel
        if (model == null) {
            faceOutcome = "مدل چهره روی این دستگاه بارگذاری نشد"
            return
        }
        val source = assetStore.source.load(layer.asset) ?: return
        detecting = true
        val found = try {
            withContext(kotlinx.coroutines.Dispatchers.Default) {
                model.detect(source.pixels, source.width, source.height)
            }
        } finally {
            detecting = false
        }
        faces = found
        faceOutcome = when (found.size) {
            0 -> "چهره‌ای پیدا نشد"
            1 -> "یک چهره پیدا شد"
            else -> "${Digits.prose(found.size)} چهره پیدا شد"
        }
    }

    /** Applies every face setting in one pass, so the picture is resampled once. */
    suspend fun applyFaceSettings(settings: ir.pixellab.engine.android.FaceTools.Settings) {
        val found = faces
        if (found.isEmpty() || settings.isIdentity) return
        transform { image -> ir.pixellab.engine.android.FaceTools.apply(image, found, settings) }
        // The mesh described the pixels as they were. After a warp it describes something that is no
        // longer there, and a second adjustment computed from stale points moves the wrong part of
        // the face — so the panel asks for a fresh detection rather than silently drifting.
        if (settings.reshape.values.any { it != 0f }) {
            faces = emptyList()
            faceOutcome = "تغییر شکل اعمال شد — برای ادامه دوباره تشخیص بدهید"
        }
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

    /**
     * Sets a layer's own paint.
     *
     * The single most basic control in the application and until now it existed nowhere a user
     * could reach: a text layer's colour could be changed by applying a whole saved style, or not at
     * all. Every effect parameter had a fill picker; the letter itself did not.
     */
    fun setLayerFill(id: LayerId, fill: Fill) = edit {
        replaceLayer(id) { it.withStyle(it.style.copy(fill = fill)) }
    }

    /**
     * Adds a text layer without making the user choose a typeface first.
     *
     * "Add text" has to be one press. It was reachable only by opening the font picker and tapping a
     * face — so a user who wanted to type had to make a typographic decision before they could write
     * a word, and the panel actually labelled متن told them to select a text layer that no control
     * in the application could create.
     *
     * @return null only when no typeface has loaded yet, which is the one honest failure here.
     */
    fun addTextLayer(text: String = SAMPLE_TEXT): LayerId? {
        val typeface = fontStore.catalog.typefaces.firstOrNull() ?: return null
        return addText(typeface, text = text)
    }

    /** Whether [addTextLayer] would do anything, so a button is dimmed rather than dead. */
    val canAddText: Boolean get() = fontStore.catalog.typefaces.isNotEmpty()

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

    /**
     * Measures what an adjustment layer needs from the picture beneath it, and freezes the answer.
     *
     * Three of the twenty-two are not per-pixel functions at all. Equalize's mapping *is* the
     * picture's own cumulative histogram; Match Color transplants another image's mean and spread;
     * Shadows/Highlights ends in a clip, and a clip is a percentile. None of the three can be
     * evaluated by a compositor that sees one texel at a time, which is exactly why Photoshop
     * refuses to offer them as adjustment layers and keeps them as destructive commands under Image.
     *
     * Taking the measurement once and storing it in the layer buys back the layer. It also makes the
     * result *stable*, which the live alternative would not be: a histogram recounted whenever
     * anything underneath moved would have every one of these corrections drift while the user
     * worked on something else entirely.
     *
     * What is measured is the document with this layer and everything above it hidden — that is what
     * "beneath" means, and including itself would have it measure its own output.
     *
     * @return true when a measurement was taken and the layer changed.
     */
    suspend fun measureAdjustment(
        id: LayerId,
        render: suspend (Document) -> ir.pixellab.core.codec.RasterImage?,
    ): Boolean {
        val layer = state.document.findLayer(id) as? Layer.AdjustmentLayer ?: return false

        // Match Color is the one that does not measure what is beneath it — it transplants another
        // picture's look, so measuring the backdrop would be an elaborate way of changing nothing.
        (layer.adjustment as? ir.pixellab.core.model.Adjustment.MatchColor)?.let { match ->
            val asset = match.source ?: return false
            val image = assetStore.source.load(asset) ?: return false
            setAdjustment(
                id,
                match.copy(statistics = ir.pixellab.core.imaging.Adjust.statistics(image.toRaster())),
            )
            return true
        }

        val document = state.document
        var reached = false
        val beneath = document.copy(
            layers = document.layers.map {
                if (it.id == id) reached = true
                if (reached) it.with(visible = false) else it
            },
        )
        val raster = (render(beneath) ?: return false).toRaster()

        val measured = when (val adjustment = layer.adjustment) {
            is ir.pixellab.core.model.Adjustment.Equalize ->
                adjustment.copy(table = ir.pixellab.core.imaging.Adjust.equalizeTable(raster).toList())

            is ir.pixellab.core.model.Adjustment.ShadowsHighlights -> {
                val (black, white) = ir.pixellab.core.imaging.Adjust.clipPoints(
                    raster, adjustment.blackClip, adjustment.whiteClip,
                )
                adjustment.copy(blackPoint = black, whitePoint = white)
            }

            else -> return false
        }
        setAdjustment(id, measured)
        return true
    }

    /**
     * Replaces the string of a text layer, keeping everything else about it — including which parts
     * of it were styled differently.
     *
     * The ranges have to move with the letters. The editor hands back a whole new string rather than
     * a keystroke, so `retarget` recovers the edit from the difference between the two; without it,
     * correcting a typo at the front of a sentence slides every colour along by one letter.
     */
    fun setText(id: LayerId, text: String) = edit {
        val layer = state.document.findLayer(id) as? Layer.Text ?: return@edit
        bounds.invalidate(id)
        applyTextSpec(
            id,
            layer.spec.copy(
                text = text,
                runs = StyleRuns.retarget(layer.spec.runs, layer.spec.text, text),
            ),
        )
    }

    /**
     * Changes how one stretch of a text layer is painted — the point of the whole text panel.
     *
     * Aimed at [range], or at the whole string when it is null, and expressed as an *edit* to
     * whatever style is already there rather than as a replacement. That is what lets a user set a
     * colour and then a size on the same word without the second undoing the first, and it is why
     * every control in the panel routes through here instead of building runs of its own.
     *
     * [continuous] carries a drag: the whole sweep of a slider is one undo entry, the same contract
     * every other scrubbing control in the application follows.
     */
    fun setCharacterStyle(
        id: LayerId,
        range: IntRange?,
        continuous: Boolean = false,
        change: (CharacterStyle) -> CharacterStyle,
    ) = edit {
        val layer = state.document.findLayer(id) as? Layer.Text ?: return@edit
        val spec = layer.spec
        val target = range ?: spec.text.indices
        if (target.isEmpty()) return@edit
        val runs = StyleRuns.apply(spec.text, spec.runs, target, change)
        if (runs == spec.runs) return@edit
        // Only the metrics-changing properties can move a letter, so only they need the measured
        // bounds thrown away. A colour change that invalidated them would re-shape the string on
        // every frame of a colour drag.
        if (runs.any { it.style.changesMetrics } || spec.runs.any { it.style.changesMetrics }) {
            bounds.invalidate(id)
        }
        setTextSpec(id, continuous) { it.copy(runs = runs) }
    }

    /** Clears every override on [range], returning it to the layer's own settings. */
    fun clearCharacterStyle(id: LayerId, range: IntRange?) = edit {
        val layer = state.document.findLayer(id) as? Layer.Text ?: return@edit
        val spec = layer.spec
        val target = range ?: spec.text.indices
        bounds.invalidate(id)
        applyTextSpec(id, spec.copy(runs = StyleRuns.clear(spec.text, spec.runs, target)))
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

    /**
     * Edits a text layer's spec through one door.
     *
     * Every typographic control goes through here rather than each writing its own `replaceLayer`,
     * because all of them share the same two obligations: invalidate the cached bounds, since any
     * of them can change the measured size, and go through the editor so the change is one undo
     * step. A control that forgot the first leaves the selection handles around the old shape.
     */
    fun editText(id: LayerId, body: (TextSpec) -> TextSpec) = edit {
        val layer = state.document.findLayer(id) as? Layer.Text ?: return@edit
        bounds.invalidate(id)
        applyTextSpec(id, body(layer.spec))
    }

    fun setTextSize(id: LayerId, size: Float) = editText(id) { it.copy(size = size) }

    fun setParagraph(id: LayerId, paragraph: ir.pixellab.core.model.ParagraphStyle) =
        editText(id) { it.copy(paragraph = paragraph) }

    fun setTextWarp(id: LayerId, warp: ir.pixellab.core.model.TextWarp) =
        editText(id) { it.copy(warp = warp) }

    /**
     * Switches between point and area type.
     *
     * Going to area gives the box the size the text already occupies, so the words do not reflow
     * the instant the mode changes — a box that arrived at some arbitrary default would rewrap the
     * paragraph and look like the text had been damaged.
     */
    fun setTextBox(id: LayerId, area: Boolean) = edit {
        val layer = state.document.findLayer(id) as? Layer.Text ?: return@edit
        val spec = layer.spec
        val next = if (!area) {
            spec.copy(boxMode = ir.pixellab.core.model.TextBoxMode.POINT)
        } else {
            val measured = bounds.of(layer)
            spec.copy(
                boxMode = ir.pixellab.core.model.TextBoxMode.AREA,
                boxSize = spec.boxSize ?: Vec2(measured.width, measured.height),
            )
        }
        bounds.invalidate(id)
        applyTextSpec(id, next)
    }

    /** Drives a variable-font axis — how kashida works on the faces that expose it. */
    fun setFontAxis(id: LayerId, axis: String, value: Float?) = editText(id) { spec ->
        val variations = spec.font.variations.toMutableMap()
        if (value == null) variations.remove(axis) else variations[axis] = value
        spec.copy(font = spec.font.copy(variations = variations))
    }

    /**
     * Turns an OpenType feature on or off.
     *
     * Six of the supplied typefaces ship stylistic sets carrying alternate Persian letterforms, and
     * no mobile editor exposes them — they are the cheapest way to make a cover look bespoke.
     */
    fun setFontFeature(id: LayerId, tag: String, on: Boolean) = editText(id) { spec ->
        val features = spec.font.features.toMutableMap()
        if (on) features[tag] = 1 else features.remove(tag)
        spec.copy(font = spec.font.copy(features = features))
    }

    /** The catalogue entry behind a text layer, for the panels that offer its axes and features. */
    fun typefaceFor(spec: TextSpec): Typeface? =
        fontStore.catalog.typefaces.firstOrNull { it.name == spec.font.family }

    private fun Editor.applyTextSpec(id: LayerId, spec: TextSpec) {
        replaceLayer(id) { (it as Layer.Text).copy(spec = spec) }
    }

    /** The typeface a text layer is currently using, for the picker to show as chosen. */
    fun typefaceOf(id: LayerId): Typeface? {
        val layer = state.document.findLayer(id) as? Layer.Text ?: return null
        return fontStore.catalog.typefaces.firstOrNull { it.name == layer.spec.font.family }
    }

    // ---- collage -----------------------------------------------------------------------------------

    /**
     * The collage being assembled, if one is.
     *
     * Kept beside the document rather than inside it because a layout and a spacing are not artwork:
     * they are the recipe the artwork was built from, and the moment the user closes the panel the
     * result is an ordinary stack of layers they can paint on, filter and export like any other.
     *
     * The photographs are held as [Collage.Photo] — an asset id and a pixel size — rather than as
     * images, because the images are already in the asset store and the layout only needs to know
     * how large each one is in order to fill its cell.
     */
    var collage: List<Collage.Photo> by mutableStateOf(emptyList())
        private set

    var collageLayout: Collage.Layout by mutableStateOf(Collage.LAYOUTS.first())
        private set

    var collageStyle: Collage.Style by mutableStateOf(Collage.Style())
        private set

    /**
     * Adds a picked photograph to the collage.
     *
     * The layout follows the count until the user picks one themselves: someone who chose three
     * pictures wants three cells, and making them find the 1+2 layout afterwards is a step that
     * exists only because the program did not look at what it had been given. [chooseCollageLayout]
     * sets [collageLayoutChosen], after which the layout stops moving under them.
     */
    fun addCollagePhoto(image: ir.pixellab.core.codec.RasterImage, name: String) {
        val asset = ir.pixellab.core.model.AssetId("collage-photo-${collage.size}")
        assetStore.put(asset, image)
        paint.bumpGeneration()
        collage = collage + Collage.Photo(
            asset = asset,
            size = Vec2(image.width.toFloat(), image.height.toFloat()),
            name = name,
        )
        if (!collageLayoutChosen) collageLayout = Collage.bestFor(collage.size)
        rebuildCollage()
    }

    /** Whether the user has picked a layout, after which adding a photograph must not change it. */
    private var collageLayoutChosen = false

    fun chooseCollageLayout(layout: Collage.Layout) {
        collageLayoutChosen = true
        collageLayout = layout
        rebuildCollage()
    }

    fun updateCollageStyle(style: Collage.Style) {
        collageStyle = style
        rebuildCollage()
    }

    /** Drops a photograph from the collage; its cell stays, empty. */
    fun removeCollagePhoto(index: Int) {
        if (index !in collage.indices) return
        collage = collage.filterIndexed { i, _ -> i != index }
        rebuildCollage()
    }

    /** Starts over, so opening the panel on a second document does not inherit the first's pictures. */
    fun clearCollage() {
        collage = emptyList()
        collageLayoutChosen = false
        collageLayout = Collage.LAYOUTS.first()
        collageStyle = Collage.Style()
    }

    /**
     * Lays the collage out again, into the document the user already has.
     *
     * **Only the collage's own layers are replaced.** Everything else in the document — a caption
     * typed over the top, an adjustment, a painted layer — is kept and stays above the collage. The
     * obvious implementation, rebuilding the whole document from the recipe, would silently delete
     * that work every time the spacing slider moved, and a slider that eats your caption is worse
     * than no slider.
     *
     * Not recorded as history per drag either: the panel is a transaction with its own ✕ and ✓
     * (see [noteSheetOpened]), and a hundred undo steps from one gesture is what that machinery
     * exists to avoid.
     */
    private fun rebuildCollage() = edit {
        val canvas = state.document.canvas
        val built = Collage.build(
            photos = collage,
            layout = collageLayout,
            width = canvas.width,
            height = canvas.height,
            style = collageStyle,
        )
        val ours = built.layers.mapTo(HashSet()) { it.id.value }
        // Anything of ours already in the document goes; anything of the user's stays, and stays on
        // top, because a collage is a background for the work rather than a cover over it.
        val theirs = state.document.layers.filterNot { it.id.value in ours || isCollageLayer(it.id) }
        replaceDocument(
            state.document.copy(
                canvas = canvas.copy(background = built.canvas.background),
                layers = built.layers + theirs,
            ),
            recordHistory = true,
        )
    }

    private fun isCollageLayer(id: LayerId) =
        id.value.startsWith("collage-cell-") || id.value.startsWith("collage-photo-")

    // ---- looks -------------------------------------------------------------------------------------

    /**
     * Saves the document's current grade under a name.
     *
     * The whole point of the feature is the *second* photograph, so what is captured is deliberately
     * only the adjustments — see [Look] for why a Look that carried layers or a crop would be worse
     * than useless. The frozen measurements are stripped there too, so a grade saved from a dark
     * photograph does not equalise a bright one by the dark one's histogram.
     */
    fun saveLook(context: android.content.Context, name: String) {
        viewModelScope.launch { lookStore.save(context, state.document, name) }
    }

    /**
     * Puts a saved grade on, or takes it back off if it is already on.
     *
     * A toggle rather than an apply, because the first thing anyone does with a row of presets is
     * try one, dislike it, and want it gone — and "press it again" is the only version of that which
     * needs no explaining. Applying a second Look does not remove the first: stacking a warm grade
     * under a grain preset is a real thing to want.
     */
    fun toggleLook(look: Look) = edit {
        val document = state.document
        replaceDocument(
            if (document.wearing(look)) document.withoutLook(look) else document.withLook(look),
        )
    }

    fun deleteLook(context: android.content.Context, look: Look) {
        viewModelScope.launch { lookStore.delete(context, look) }
        edit { replaceDocument(state.document.withoutLook(look)) }
    }

    /** Whether the document is currently wearing a Look, so its chip can read as chosen. */
    fun wearing(look: Look) = state.document.wearing(look)

    /** Whether there is a grade worth saving — a save that produced an empty Look is a trap. */
    val hasGrade: Boolean
        get() = state.document.walk().any { it is Layer.AdjustmentLayer && it.visible }

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

        /** A 5x5 average: enough to ignore sensor noise, small enough to stay on one feature. */
        const val SAMPLE_RADIUS = 2

        const val MAX_CHANNEL = 255f

        /**
         * Generated tips and pattern tiles are built at this resolution.
         *
         * Larger than any swatch and larger than most brushes are used at, because a tip scaled up
         * shows its own pixels and a pattern is often tiled across a whole canvas.
         */
        const val TEXTURE_SIZE = 256

        /**
         * Depth and bevel as fractions of the type size.
         *
         * A fixed twenty pixels of depth is a slab on a caption and invisible on a poster headline;
         * a fifth of the cap height reads as the same amount of depth at every size.
         */
        /** Below 30 nothing prints; above 1200 the number is larger than any device can output. */
        const val MIN_DPI = 30
        const val MAX_DPI = 1200

        const val DEPTH_FRACTION = 0.2f

        /**
         * The bevel a letter starts with, against the type size.
         *
         * One per cent, and the number is small because a Persian stroke is thin against the size
         * that names it. At 260 point Vazirmatn draws a stem around a tenth of that, so the four
         * per cent this used to be asked for a bevel a third of the stroke — on each side. The
         * guard held the geometry together and the word still came out as gold ribbon: legible as
         * an outline, not as carved type. A per-cent bevel leaves the face the letter and puts the
         * gold on its edge, which is the cover treatment this was built for.
         */
        const val BEVEL_FRACTION = 0.01f

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
