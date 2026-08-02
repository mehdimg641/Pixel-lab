package ir.pixellab.core.canvas

import ir.pixellab.core.model.Vec2
import kotlin.math.max

enum class PointerAction { DOWN, MOVE, UP, CANCEL }

/** One pointer's state at one instant. [id] is stable for the life of that finger. */
data class PointerEvent(
    val id: Int,
    val position: Vec2,
    val action: PointerAction,
    val timeMillis: Long,
)

/**
 * What the recogniser decided a touch meant.
 *
 * Deliberately a closed set of high-level intents rather than raw pointer data: the canvas should
 * never have to reason about finger counts, and the two-finger undo can then be tested without a
 * screen.
 */
sealed interface CanvasGesture {
    data class Tap(val position: Vec2) : CanvasGesture

    data class DoubleTap(val position: Vec2) : CanvasGesture

    data class LongPress(val position: Vec2) : CanvasGesture

    data class DragStart(val position: Vec2) : CanvasGesture

    /** [delta] is the movement since the previous event, already free of pointer-count jumps. */
    data class Drag(val position: Vec2, val delta: Vec2) : CanvasGesture

    data class DragEnd(val position: Vec2) : CanvasGesture

    data class TransformStart(val pivot: Vec2) : CanvasGesture

    data class Transform(
        val pivot: Vec2,
        val pan: Vec2,
        val scaleFactor: Float,
        val rotationDegrees: Float,
    ) : CanvasGesture

    data object TransformEnd : CanvasGesture

    /** Two-finger tap. The most frequent action in design work should not need a trip to a button. */
    data object Undo : CanvasGesture

    /** Three-finger tap. */
    data object Redo : CanvasGesture
}

/** Thresholds, in screen pixels and milliseconds. Density conversion is the platform's job. */
data class GestureConfig(
    val touchSlop: Float = 12f,
    val tapSlop: Float = 24f,
    val longPressMillis: Long = 420L,
    val doubleTapMillis: Long = 280L,
    /** A multi-finger tap is a tap only if it is quick; longer means a pinch that barely moved. */
    val multiTapMillis: Long = 260L,
)

/**
 * Turns a pointer stream into canvas intents.
 *
 * Written as an explicit state machine over the whole stream rather than as separate detectors,
 * because the interesting failures are all about the transitions between them:
 *
 * - **The centroid jump.** When a second finger lands, the midpoint of the contact set moves
 *   instantly. Feeding that straight through as a pan is what makes a layer leap sideways the moment
 *   a pinch begins. The reference point is reset on every change in pointer count.
 * - **A pinch that starts as a drag.** A drag is already in progress when the second finger arrives,
 *   so it has to be ended rather than left dangling.
 * - **A two-finger tap that is really a pinch.** Both fingers touch and lift with barely any
 *   movement in either case; only the distance travelled and the time held separate them.
 */
class GestureRecognizer(private val config: GestureConfig = GestureConfig()) {

    private class Pointer(val downAt: Long, val downPosition: Vec2, var position: Vec2) {
        var travelled = 0f
    }

    private val pointers = LinkedHashMap<Int, Pointer>()

    private var mode = Mode.IDLE
    private var reference: Contacts? = null
    private var peakPointerCount = 0
    private var longPressFired = false
    private var lastTapAt = 0L
    private var lastTapPosition = Vec2.ZERO

    private enum class Mode { IDLE, PENDING, DRAGGING, TRANSFORMING, DEAD }

    /**
     * Feeds one event and returns whatever it resolved to.
     *
     * A list rather than a single value because one event genuinely can mean two things: the finger
     * that starts a pinch both ends the drag and starts the transform.
     */
    fun onEvent(event: PointerEvent): List<CanvasGesture> = when (event.action) {
        PointerAction.DOWN -> onDown(event)
        PointerAction.MOVE -> onMove(event)
        PointerAction.UP -> onUp(event)
        PointerAction.CANCEL -> onCancel()
    }

    /**
     * Reports a long press if the finger has been still long enough.
     *
     * A press is the absence of events, so it cannot be detected from the stream alone; the host
     * calls this from its frame loop or a timer.
     */
    fun onTick(nowMillis: Long): List<CanvasGesture> {
        if (mode != Mode.PENDING || longPressFired || pointers.size != 1) return emptyList()
        val pointer = pointers.values.first()
        if (pointer.travelled > config.touchSlop) return emptyList()
        if (nowMillis - pointer.downAt < config.longPressMillis) return emptyList()
        longPressFired = true
        return listOf(CanvasGesture.LongPress(pointer.position))
    }

    fun reset() {
        pointers.clear()
        mode = Mode.IDLE
        reference = null
        peakPointerCount = 0
        longPressFired = false
    }

    private fun onDown(event: PointerEvent): List<CanvasGesture> {
        val emitted = ArrayList<CanvasGesture>(2)
        pointers[event.id] = Pointer(event.timeMillis, event.position, event.position)
        peakPointerCount = max(peakPointerCount, pointers.size)

        if (pointers.size >= 2) {
            if (mode == Mode.DRAGGING) emitted += CanvasGesture.DragEnd(reference?.centroid ?: event.position)
            if (mode != Mode.TRANSFORMING) {
                mode = Mode.TRANSFORMING
                emitted += CanvasGesture.TransformStart(contacts().centroid)
            }
        } else {
            mode = Mode.PENDING
            longPressFired = false
        }
        // Reset on every count change: the centroid of one finger and of two are different points,
        // and the difference is not motion the user made.
        reference = contacts()
        return emitted
    }

    private fun onMove(event: PointerEvent): List<CanvasGesture> {
        val pointer = pointers[event.id] ?: return emptyList()
        pointer.travelled += (event.position - pointer.position).length
        pointer.position = event.position
        val now = contacts()
        val previous = reference ?: now.also { reference = it }

        return when (mode) {
            Mode.PENDING -> {
                if ((now.centroid - previous.centroid).length <= config.touchSlop) {
                    emptyList()
                } else {
                    mode = Mode.DRAGGING
                    reference = now
                    listOf(
                        CanvasGesture.DragStart(previous.centroid),
                        CanvasGesture.Drag(now.centroid, now.centroid - previous.centroid),
                    )
                }
            }
            Mode.DRAGGING -> {
                reference = now
                listOf(CanvasGesture.Drag(now.centroid, now.centroid - previous.centroid))
            }
            Mode.TRANSFORMING -> {
                reference = now
                listOf(
                    CanvasGesture.Transform(
                        pivot = now.centroid,
                        pan = now.centroid - previous.centroid,
                        // A degenerate span happens when two fingers land on the same pixel;
                        // dividing by it would send the zoom to infinity.
                        scaleFactor = if (previous.span > MIN_SPAN) now.span / previous.span else 1f,
                        rotationDegrees = if (previous.span > MIN_SPAN) {
                            deltaDegrees(previous.angle, now.angle)
                        } else {
                            0f
                        },
                    ),
                )
            }
            Mode.IDLE, Mode.DEAD -> emptyList()
        }
    }

    private fun onUp(event: PointerEvent): List<CanvasGesture> {
        val pointer = pointers.remove(event.id) ?: return emptyList()
        val emitted = ArrayList<CanvasGesture>(2)

        if (pointers.isNotEmpty()) {
            // Fingers still down. Never emit anything here: lifting one of two fingers mid-pinch is
            // not a tap, and treating the remaining finger's position as motion is the other half of
            // the centroid jump.
            if (pointers.size == 1 && mode == Mode.TRANSFORMING) {
                emitted += CanvasGesture.TransformEnd
                mode = Mode.DEAD
            }
            reference = contacts()
            return emitted
        }

        if (mode == Mode.TRANSFORMING) emitted += CanvasGesture.TransformEnd
        if (mode == Mode.DRAGGING) emitted += CanvasGesture.DragEnd(pointer.position)

        val held = event.timeMillis - pointer.downAt
        val still = pointer.travelled <= config.tapSlop
        when {
            mode == Mode.DRAGGING || mode == Mode.TRANSFORMING -> Unit
            longPressFired -> Unit
            !still -> Unit
            peakPointerCount == 2 && held <= config.multiTapMillis -> emitted += CanvasGesture.Undo
            peakPointerCount >= 3 && held <= config.multiTapMillis -> emitted += CanvasGesture.Redo
            peakPointerCount == 1 -> emitted += tapOrDoubleTap(event)
        }

        mode = Mode.IDLE
        peakPointerCount = 0
        reference = null
        return emitted
    }

    private fun tapOrDoubleTap(event: PointerEvent): CanvasGesture {
        val quick = event.timeMillis - lastTapAt <= config.doubleTapMillis
        val near = (event.position - lastTapPosition).length <= config.tapSlop
        return if (quick && near) {
            // Consumed, so three taps in a row are tap, double tap, tap — not two double taps.
            lastTapAt = 0L
            CanvasGesture.DoubleTap(event.position)
        } else {
            lastTapAt = event.timeMillis
            lastTapPosition = event.position
            CanvasGesture.Tap(event.position)
        }
    }

    private fun onCancel(): List<CanvasGesture> {
        val emitted = when (mode) {
            Mode.DRAGGING -> listOf(CanvasGesture.DragEnd(reference?.centroid ?: Vec2.ZERO))
            Mode.TRANSFORMING -> listOf(CanvasGesture.TransformEnd)
            else -> emptyList()
        }
        reset()
        return emitted
    }

    private fun contacts(): Contacts {
        val positions = pointers.values.map { it.position }
        val centroid = positions.fold(Vec2.ZERO) { acc, p -> acc + p } / positions.size.toFloat()
        // Span and angle come from the first two fingers only. Averaging over three or more is the
        // obvious generalisation and it makes the scale jitter as a third finger drifts.
        val span: Float
        val angle: Float
        if (positions.size >= 2) {
            val axis = positions[1] - positions[0]
            span = axis.length
            angle = axis.angle
        } else {
            span = 0f
            angle = 0f
        }
        return Contacts(centroid, span, angle)
    }

    private data class Contacts(val centroid: Vec2, val span: Float, val angle: Float)

    private companion object {
        const val MIN_SPAN = 1f
    }
}
