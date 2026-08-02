package ir.pixellab.engine.android

import android.view.MotionEvent
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.canvas.CanvasGesture
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the `MotionEvent` unpacking against real event objects.
 *
 * Robolectric builds genuine `MotionEvent`s, including the multi-pointer and historical forms, so
 * the pointer-index versus pointer-id confusion this class exists to prevent is actually reachable
 * here rather than assumed away by a stub.
 */
@RunWith(RobolectricTestRunner::class)
class TouchBridgeTest {

    private val bridge = TouchBridge()
    private val recycle = ArrayList<MotionEvent>()

    @After
    fun tearDown() = recycle.forEach { it.recycle() }

    private fun properties(vararg pointers: Triple<Int, Float, Float>) =
        pointers.map { (id, _, _) ->
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()

    private fun coords(vararg pointers: Triple<Int, Float, Float>) =
        pointers.map { (_, x, y) ->
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()

    private fun send(
        action: Int,
        actionIndex: Int = 0,
        time: Long,
        vararg pointers: Triple<Int, Float, Float>,
    ): List<CanvasGesture> {
        val masked = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val event = MotionEvent.obtain(
            1000L, time, masked, pointers.size,
            properties(*pointers), coords(*pointers),
            0, 0, 1f, 1f, 0, 0, 0, 0,
        )
        recycle += event
        return bridge.onTouchEvent(event)
    }

    @Test
    fun `a still touch is a tap`() {
        send(MotionEvent.ACTION_DOWN, time = 1000, pointers = arrayOf(Triple(0, 100f, 200f)))
        val lifted = send(MotionEvent.ACTION_UP, time = 1080, pointers = arrayOf(Triple(0, 100f, 200f)))
        lifted.single() shouldBe CanvasGesture.Tap(ir.pixellab.core.model.Vec2(100f, 200f))
    }

    @Test
    fun `a move carries every pointer, not just the one at index zero`() {
        send(MotionEvent.ACTION_DOWN, time = 1000, pointers = arrayOf(Triple(0, 400f, 500f)))
        send(
            MotionEvent.ACTION_POINTER_DOWN, actionIndex = 1, time = 1010,
            pointers = arrayOf(Triple(0, 400f, 500f), Triple(1, 600f, 500f)),
        )
        // Only the second finger moves. Reading actionIndex — which is 0 for a move — would see
        // nothing and a pinch would behave as a one-finger drag.
        val moved = send(
            MotionEvent.ACTION_MOVE, time = 1030,
            pointers = arrayOf(Triple(0, 400f, 500f), Triple(1, 800f, 500f)),
        )
        val transform = moved.filterIsInstance<CanvasGesture.Transform>().last()
        transform.scaleFactor shouldBe (2f plusOrMinus 0.001f)
    }

    @Test
    fun `a pointer that lifts is identified by its id and not its index`() {
        send(MotionEvent.ACTION_DOWN, time = 1000, pointers = arrayOf(Triple(0, 300f, 800f)))
        send(
            MotionEvent.ACTION_POINTER_DOWN, actionIndex = 1, time = 1010,
            pointers = arrayOf(Triple(0, 300f, 800f), Triple(1, 500f, 800f)),
        )
        send(
            MotionEvent.ACTION_MOVE, time = 1050,
            pointers = arrayOf(Triple(0, 300f, 800f), Triple(1, 700f, 800f)),
        )
        // The first finger lifts. Its id stays 0, but the surviving finger — id 1 — shifts down to
        // index 0 in the event that follows.
        send(
            MotionEvent.ACTION_POINTER_UP, actionIndex = 0, time = 1100,
            pointers = arrayOf(Triple(0, 300f, 800f), Triple(1, 700f, 800f)),
        ).single() shouldBe CanvasGesture.TransformEnd
        send(MotionEvent.ACTION_UP, time = 1120, pointers = arrayOf(Triple(1, 700f, 800f))) shouldBe emptyList()

        // Keyed by index, that final lift would have looked up id 0 and left finger 1 in the map
        // forever — so this next touch would begin as a two-finger transform instead of a tap.
        send(MotionEvent.ACTION_DOWN, time = 2000, pointers = arrayOf(Triple(0, 200f, 200f)))
        send(MotionEvent.ACTION_UP, time = 2060, pointers = arrayOf(Triple(0, 200f, 200f)))
            .single() shouldBe CanvasGesture.Tap(ir.pixellab.core.model.Vec2(200f, 200f))
    }

    @Test
    fun `a two finger tap survives the trip through MotionEvent`() {
        send(MotionEvent.ACTION_DOWN, time = 1000, pointers = arrayOf(Triple(0, 300f, 800f)))
        send(
            MotionEvent.ACTION_POINTER_DOWN, actionIndex = 1, time = 1020,
            pointers = arrayOf(Triple(0, 300f, 800f), Triple(1, 400f, 800f)),
        )
        send(
            MotionEvent.ACTION_POINTER_UP, actionIndex = 1, time = 1100,
            pointers = arrayOf(Triple(0, 300f, 800f), Triple(1, 400f, 800f)),
        )
        val done = send(MotionEvent.ACTION_UP, time = 1120, pointers = arrayOf(Triple(0, 300f, 800f)))
        done.contains(CanvasGesture.Undo) shouldBe true
    }

    @Test
    fun `a cancelled stream ends the drag`() {
        send(MotionEvent.ACTION_DOWN, time = 1000, pointers = arrayOf(Triple(0, 100f, 100f)))
        send(MotionEvent.ACTION_MOVE, time = 1020, pointers = arrayOf(Triple(0, 300f, 100f)))
        send(MotionEvent.ACTION_CANCEL, time = 1040, pointers = arrayOf(Triple(0, 300f, 100f)))
            .single().let { (it is CanvasGesture.DragEnd) shouldBe true }
    }

    @Test
    fun `a long press is reported from the frame loop`() {
        send(MotionEvent.ACTION_DOWN, time = 1000, pointers = arrayOf(Triple(0, 100f, 100f)))
        bridge.onFrame(1200) shouldBe emptyList()
        (bridge.onFrame(1500).single() is CanvasGesture.LongPress) shouldBe true
    }
}
