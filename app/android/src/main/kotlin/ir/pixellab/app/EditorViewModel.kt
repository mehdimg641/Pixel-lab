package ir.pixellab.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import ir.pixellab.core.canvas.CanvasGesture
import ir.pixellab.core.canvas.Handle
import ir.pixellab.core.editor.Editor
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.LayerBounds
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2

/**
 * Holds the [Editor] across configuration changes and republishes its state to Compose.
 *
 * The editor itself knows nothing about Compose or Android; this is the only place the two meet.
 * Keeping the boundary that thin is what lets every rule about selection, snapping, sheets and undo
 * be tested without a device.
 */
class EditorViewModel : ViewModel() {

    /**
     * Where a layer's content sits before its transform.
     *
     * Text bounds come from shaping and image bounds from the decoded asset. Until the rasteriser is
     * wired in, shapes report their declared size and everything else a default box — enough for
     * selection and handles to be exercised, and the one place to change when real measurement
     * arrives.
     */
    private val bounds = LayerBounds { layer ->
        when (layer) {
            is Layer.Shape -> when (val g = layer.geometry) {
                is ShapeGeometry.Rectangle -> Rect.of(g.size)
                is ShapeGeometry.Ellipse -> Rect.of(g.size)
                is ShapeGeometry.Polygon -> Rect.of(g.size)
                is ShapeGeometry.Star -> Rect.of(g.size)
                is ShapeGeometry.Line -> Rect(
                    minOf(g.from.x, g.to.x), minOf(g.from.y, g.to.y),
                    maxOf(g.from.x, g.to.x), maxOf(g.from.y, g.to.y),
                )
                is ShapeGeometry.Arrow -> Rect(
                    minOf(g.from.x, g.to.x), minOf(g.from.y, g.to.y),
                    maxOf(g.from.x, g.to.x), maxOf(g.from.y, g.to.y),
                )
                is ShapeGeometry.Path -> g.contours
                    .flatMap { it.nodes }
                    .fold(null as Rect?) { acc, node ->
                        val point = Rect(node.point.x, node.point.y, node.point.x, node.point.y)
                        acc?.union(point) ?: point
                    } ?: Rect.of(DEFAULT_BOX)
            }
            else -> Rect.of(DEFAULT_BOX)
        }
    }

    private val editor = Editor(startingDocument(), bounds)

    var state: EditorState by mutableStateOf(editor.state)
        private set

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

    private companion object {
        val DEFAULT_BOX = Vec2(320f, 160f)

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
