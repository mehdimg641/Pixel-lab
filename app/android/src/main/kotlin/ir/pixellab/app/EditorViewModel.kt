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
import kotlinx.coroutines.launch

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

    /** Every mutation goes through here so a state change can never be forgotten. */
    private fun <T> edit(body: Editor.() -> T): T {
        val result = editor.body()
        state = editor.state
        return result
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
    fun onGesture(gesture: CanvasGesture) = edit {
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

            CanvasGesture.Undo -> undo()
            CanvasGesture.Redo -> redo()
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

    /** Opens a whole project: its document and the assets its masks and images refer to. */
    fun openProject(project: ir.pixellab.core.codec.Project) {
        openDocument(project.document)
        viewModelScope.launch { assetStore.load(project) }
    }

    /** What a save writes: the document plus every asset it refers to. */
    fun currentProject(): ir.pixellab.core.codec.Project =
        ir.pixellab.core.codec.Project(state.document, assets = assetStore.encoded())

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
