package ir.pixellab.engine.android

import android.view.MotionEvent
import ir.pixellab.core.canvas.CanvasGesture
import ir.pixellab.core.canvas.GestureConfig
import ir.pixellab.core.canvas.GestureRecognizer
import ir.pixellab.core.canvas.PointerAction
import ir.pixellab.core.canvas.PointerEvent
import ir.pixellab.core.model.Vec2

/**
 * Translates Android's touch stream into the recogniser's.
 *
 * `MotionEvent` packs several things into one object and each is a real trap:
 *
 * - **`ACTION_MOVE` carries every pointer at once**, so one event has to become one
 *   [PointerEvent] per finger. Reading only `actionIndex` — which is 0 for a move — silently drops
 *   the second finger's motion, and a pinch then behaves as a one-finger drag.
 * - **`ACTION_POINTER_DOWN`/`UP` carry the index, not the id.** Indices shift as fingers lift; ids
 *   do not. Keying the recogniser on the index means a three-finger gesture mixes up which finger
 *   went where.
 * - **Historical samples.** The system batches motion between frames. Dropping them loses most of a
 *   fast stroke, which shows up as a corner cut off a quick drag.
 */
class TouchBridge(config: GestureConfig = GestureConfig()) {

    private val recognizer = GestureRecognizer(config)

    fun onTouchEvent(event: MotionEvent): List<CanvasGesture> {
        val out = ArrayList<CanvasGesture>()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                out += recognizer.onEvent(
                    PointerEvent(
                        id = event.getPointerId(index),
                        position = Vec2(event.getX(index), event.getY(index)),
                        action = PointerAction.DOWN,
                        timeMillis = event.eventTime,
                    ),
                )
            }

            MotionEvent.ACTION_MOVE -> {
                // Historical samples first, oldest to newest, then the current position.
                for (h in 0 until event.historySize) {
                    for (p in 0 until event.pointerCount) {
                        out += recognizer.onEvent(
                            PointerEvent(
                                id = event.getPointerId(p),
                                position = Vec2(event.getHistoricalX(p, h), event.getHistoricalY(p, h)),
                                action = PointerAction.MOVE,
                                timeMillis = event.getHistoricalEventTime(h),
                            ),
                        )
                    }
                }
                for (p in 0 until event.pointerCount) {
                    out += recognizer.onEvent(
                        PointerEvent(
                            id = event.getPointerId(p),
                            position = Vec2(event.getX(p), event.getY(p)),
                            action = PointerAction.MOVE,
                            timeMillis = event.eventTime,
                        ),
                    )
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                out += recognizer.onEvent(
                    PointerEvent(
                        id = event.getPointerId(index),
                        position = Vec2(event.getX(index), event.getY(index)),
                        action = PointerAction.UP,
                        timeMillis = event.eventTime,
                    ),
                )
            }

            MotionEvent.ACTION_CANCEL -> {
                out += recognizer.onEvent(
                    PointerEvent(
                        id = if (event.pointerCount > 0) event.getPointerId(0) else 0,
                        position = Vec2(event.x, event.y),
                        action = PointerAction.CANCEL,
                        timeMillis = event.eventTime,
                    ),
                )
            }
        }
        return out
    }

    /** Call from the frame loop: a long press is the absence of events and cannot be seen otherwise. */
    fun onFrame(nowMillis: Long): List<CanvasGesture> = recognizer.onTick(nowMillis)

    fun reset() = recognizer.reset()
}
