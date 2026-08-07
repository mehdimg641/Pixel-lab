package ir.pixellab.core.canvas

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

class GestureTest {

    private val recognizer = GestureRecognizer()
    private var clock = 1_000L

    private fun down(id: Int, x: Float, y: Float, at: Long = clock) =
        recognizer.onEvent(PointerEvent(id, Vec2(x, y), PointerAction.DOWN, at))

    private fun move(id: Int, x: Float, y: Float, at: Long = clock) =
        recognizer.onEvent(PointerEvent(id, Vec2(x, y), PointerAction.MOVE, at))

    private fun up(id: Int, x: Float, y: Float, at: Long = clock) =
        recognizer.onEvent(PointerEvent(id, Vec2(x, y), PointerAction.UP, at))

    @Test
    fun `a touch that does not move is a tap`() {
        down(0, 100f, 100f, at = 1000)
        up(0, 102f, 101f, at = 1080).single() shouldBe CanvasGesture.Tap(Vec2(102f, 101f))
    }

    @Test
    fun `two quick taps in the same place are a double tap`() {
        down(0, 100f, 100f, at = 1000)
        up(0, 100f, 100f, at = 1060)
        down(0, 103f, 101f, at = 1150)
        up(0, 103f, 101f, at = 1200).single() shouldBe CanvasGesture.DoubleTap(Vec2(103f, 101f))
    }

    @Test
    fun `a third tap starts over rather than making a second double tap`() {
        down(0, 100f, 100f, at = 1000); up(0, 100f, 100f, at = 1050)
        down(0, 100f, 100f, at = 1100); up(0, 100f, 100f, at = 1150)
        down(0, 100f, 100f, at = 1200)
        up(0, 100f, 100f, at = 1250).single() shouldBe CanvasGesture.Tap(Vec2(100f, 100f))
    }

    @Test
    fun `taps far apart in time are two separate taps`() {
        down(0, 100f, 100f, at = 1000); up(0, 100f, 100f, at = 1050)
        down(0, 100f, 100f, at = 3000)
        up(0, 100f, 100f, at = 3050).single() shouldBe CanvasGesture.Tap(Vec2(100f, 100f))
    }

    @Test
    fun `a held finger reports a long press once`() {
        down(0, 100f, 100f, at = 1000)
        recognizer.onTick(1200) shouldBe emptyList()
        recognizer.onTick(1500).single() shouldBe CanvasGesture.LongPress(Vec2(100f, 100f))
        // Firing every frame after the threshold would open the hidden-layer list repeatedly.
        recognizer.onTick(1600) shouldBe emptyList()
    }

    @Test
    fun `a long press that moves is not a press and the lift is not a tap`() {
        down(0, 100f, 100f, at = 1000)
        move(0, 140f, 100f, at = 1100)
        recognizer.onTick(1600) shouldBe emptyList()
    }

    @Test
    fun `a lift after a long press is not also a tap`() {
        down(0, 100f, 100f, at = 1000)
        recognizer.onTick(1500)
        up(0, 100f, 100f, at = 1600) shouldBe emptyList()
    }

    @Test
    fun `movement past the slop becomes a drag`() {
        down(0, 100f, 100f, at = 1000)
        move(0, 104f, 100f, at = 1020) shouldBe emptyList()
        val started = move(0, 130f, 100f, at = 1040)
        started[0] shouldBe CanvasGesture.DragStart(Vec2(100f, 100f))
        (started[1] as CanvasGesture.Drag).delta shouldBe Vec2(30f, 0f)
        (move(0, 150f, 100f, at = 1060).single() as CanvasGesture.Drag).delta shouldBe Vec2(20f, 0f)
        up(0, 150f, 100f, at = 1080).single() shouldBe CanvasGesture.DragEnd(Vec2(150f, 100f))
    }

    @Test
    fun `a second finger does not jolt the layer by the centroid jump`() {
        down(0, 100f, 100f, at = 1000)
        move(0, 200f, 100f, at = 1020)
        // The midpoint of one finger and of two are different points; feeding that difference
        // through as motion is what makes a layer leap the moment a pinch starts.
        val landing = down(1, 600f, 100f, at = 1040)
        landing[0] shouldBe CanvasGesture.DragEnd(Vec2(200f, 100f))
        landing[1] shouldBe CanvasGesture.TransformStart(Vec2(400f, 100f))

        val first = move(1, 610f, 100f, at = 1060).single() as CanvasGesture.Transform
        first.pan.x shouldBe (5f plusOrMinus 0.01f)
    }

    @Test
    fun `spreading two fingers scales without panning`() {
        down(0, 400f, 500f, at = 1000)
        down(1, 600f, 500f, at = 1010)
        val spread = move(1, 800f, 500f, at = 1030).single() as CanvasGesture.Transform
        spread.scaleFactor shouldBe (2f plusOrMinus 0.001f)
        // The centroid moved 100px because only one finger moved; that is real pan, not a jump.
        spread.pan.x shouldBe (100f plusOrMinus 0.01f)
        spread.rotationDegrees shouldBe (0f plusOrMinus 0.001f)
    }

    @Test
    fun `turning two fingers rotates`() {
        down(0, 500f, 500f, at = 1000)
        down(1, 600f, 500f, at = 1010)
        val turned = move(1, 500f, 600f, at = 1030).single() as CanvasGesture.Transform
        turned.rotationDegrees shouldBe (90f plusOrMinus 0.001f)
    }

    @Test
    fun `two fingers landing on the same pixel do not send the zoom to infinity`() {
        down(0, 500f, 500f, at = 1000)
        down(1, 500f, 500f, at = 1010)
        (move(1, 560f, 500f, at = 1030).single() as CanvasGesture.Transform)
            .scaleFactor shouldBe (1f plusOrMinus 0.001f)
    }

    @Test
    fun `a quick two finger tap is undo`() {
        down(0, 300f, 800f, at = 1000)
        down(1, 400f, 800f, at = 1020)
        up(0, 300f, 800f, at = 1120)
        up(1, 400f, 800f, at = 1130).contains(CanvasGesture.Undo) shouldBe true
    }

    @Test
    fun `a quick three finger tap is redo`() {
        down(0, 300f, 800f, at = 1000)
        down(1, 400f, 800f, at = 1010)
        down(2, 500f, 800f, at = 1020)
        up(0, 300f, 800f, at = 1100)
        up(1, 400f, 800f, at = 1110)
        up(2, 500f, 800f, at = 1120).contains(CanvasGesture.Redo) shouldBe true
    }

    @Test
    fun `a pinch is not mistaken for a two finger tap`() {
        down(0, 300f, 800f, at = 1000)
        down(1, 400f, 800f, at = 1010)
        move(1, 700f, 800f, at = 1050)
        up(0, 300f, 800f, at = 1100)
        val lifted = up(1, 700f, 800f, at = 1110)
        lifted.contains(CanvasGesture.Undo) shouldBe false
    }

    @Test
    fun `two fingers held too long are not a tap either`() {
        // Barely-moved but slow means the user was pinching carefully, not tapping.
        down(0, 300f, 800f, at = 1000)
        down(1, 400f, 800f, at = 1010)
        up(0, 300f, 800f, at = 1900)
        up(1, 400f, 800f, at = 1950).contains(CanvasGesture.Undo) shouldBe false
    }

    @Test
    fun `lifting one of two fingers ends the transform and does not resume dragging`() {
        down(0, 300f, 800f, at = 1000)
        down(1, 500f, 800f, at = 1010)
        move(1, 600f, 800f, at = 1030)
        up(1, 600f, 800f, at = 1050).single() shouldBe CanvasGesture.TransformEnd
        // The remaining finger must not start moving the layer; the user is unwinding a pinch.
        move(0, 400f, 800f, at = 1070) shouldBe emptyList()
        up(0, 400f, 800f, at = 1090) shouldBe emptyList()
    }

    @Test
    fun `a cancelled drag is closed out`() {
        down(0, 100f, 100f, at = 1000)
        move(0, 200f, 100f, at = 1020)
        recognizer.onEvent(PointerEvent(0, Vec2(200f, 100f), PointerAction.CANCEL, 1040))
            .single() shouldBe CanvasGesture.DragEnd(Vec2(200f, 100f))
        // And the recogniser is usable again straight away.
        down(0, 100f, 100f, at = 2000)
        up(0, 100f, 100f, at = 2050).single() shouldBe CanvasGesture.Tap(Vec2(100f, 100f))
    }

    @Test
    fun `an event for an unknown pointer is ignored`() {
        move(7, 100f, 100f, at = 1000) shouldBe emptyList()
        up(7, 100f, 100f, at = 1010) shouldBe emptyList()
    }

    /** Every degree the canvas was told to turn during a two-finger gesture. */
    private fun turnOf(events: List<CanvasGesture>) =
        events.filterIsInstance<CanvasGesture.Transform>().sumOf { it.rotationDegrees.toDouble() }

    @Test
    fun `an ordinary pinch does not rotate the canvas`() {
        // The bug this exists for: two fingers never pinch along a perfectly fixed line, so an
        // unguarded angle delta turns the canvas a fraction of a degree on every zoom. The
        // fractions accumulate, and after a minute of ordinary work the artboard sits visibly
        // crooked with nothing the user did having asked for that.
        down(0, 100f, 500f, at = 1000)
        down(1, 300f, 500f, at = 1010)

        var turn = 0.0
        for (step in 1..20) {
            val wobble = if (step % 2 == 0) 3f else -2f
            turn += turnOf(move(0, 100f - step * 4f, 500f + wobble, at = 1010L + step * 10))
            turn += turnOf(move(1, 300f + step * 4f, 500f - wobble, at = 1012L + step * 10))
        }
        turn.toFloat() shouldBe (0f plusOrMinus 0.001f)
    }

    @Test
    fun `a deliberate turn rotates, and keeps every degree of it`() {
        // A per-frame threshold would swallow this, because a slow deliberate turn is a sequence of
        // small deltas too. The *total* since the gesture began is what crosses, and once it has the
        // latch stays open — so the guard costs no precision after the first moment.
        down(0, 200f, 400f, at = 1000)
        down(1, 200f, 600f, at = 1010)

        var turn = 0.0
        for (step in 1..40) {
            val radians = Math.toRadians(step.toDouble())
            val dx = (100.0 * kotlin.math.sin(radians)).toFloat()
            val dy = (100.0 * kotlin.math.cos(radians)).toFloat()
            turn += turnOf(move(0, 200f - dx, 500f - dy, at = 1010L + step * 10))
            turn += turnOf(move(1, 200f + dx, 500f + dy, at = 1012L + step * 10))
        }
        // The whole forty degrees arrives, including the eight spent reaching the threshold.
        kotlin.math.abs(turn).toFloat() shouldBe (40f plusOrMinus 2f)
    }

    @Test
    fun `lifting the fingers closes the latch again`() {
        // Otherwise the second pinch of a session inherits the first one's permission to rotate and
        // the guard protects only the very first gesture anybody makes.
        down(0, 200f, 400f, at = 1000)
        down(1, 200f, 600f, at = 1010)
        for (step in 1..40) {
            val radians = Math.toRadians(step.toDouble())
            val dx = (100.0 * kotlin.math.sin(radians)).toFloat()
            val dy = (100.0 * kotlin.math.cos(radians)).toFloat()
            move(0, 200f - dx, 500f - dy, at = 1010L + step * 10)
            move(1, 200f + dx, 500f + dy, at = 1012L + step * 10)
        }
        up(0, 200f, 400f, at = 2000)
        up(1, 200f, 600f, at = 2010)

        down(0, 100f, 500f, at = 3000)
        down(1, 300f, 500f, at = 3010)
        var turn = 0.0
        for (step in 1..20) {
            val wobble = if (step % 2 == 0) 3f else -2f
            turn += turnOf(move(0, 100f - step * 4f, 500f + wobble, at = 3010L + step * 10))
            turn += turnOf(move(1, 300f + step * 4f, 500f - wobble, at = 3012L + step * 10))
        }
        turn.toFloat() shouldBe (0f plusOrMinus 0.001f)
    }
}
