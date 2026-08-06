package ir.pixellab.core.editor

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.render.ParameterSpec
import org.junit.jupiter.api.Test

/**
 * How a slider answers a finger.
 *
 * Untested until now, and it held two defects that a user meets within a second of touching one.
 * Neither is visible from reading a single call: they are both about what happens across a *stroke*,
 * which is why this feeds whole strokes rather than checking one position at a time.
 *
 * The third defect — the gesture being torn down and restarted on every emitted value — lives in the
 * Compose layer and cannot be reached from here. It is `PrecisionSlider`'s `pointerInput` key list.
 */
class SliderDragTest {

    private val spec = ParameterSpec.Slider(
        key = "blur",
        label = "محو",
        range = 0f..100f,
        default = 50f,
    )

    private fun drag(from: Float = 50f, at: Pair<Float, Float> = 0f to 0f) = SliderDrag(
        spec = spec,
        startValue = from,
        startScreen = at,
        trackWidth = 300f,
        trackY = 0f,
        deadZone = 24f,
        falloff = 120f,
    )

    /** A stroke: successive finger positions, returning the value after each. */
    private fun SliderDrag.stroke(vararg points: Pair<Float, Float>) =
        points.map { (x, y) -> advance(x, y) }

    @Test
    fun `a straight drag moves the value the whole way`() {
        // 300px of travel across a 300px track is the entire range.
        val values = drag(from = 0f).stroke(75f to 0f, 150f to 0f, 225f to 0f, 300f to 0f)
        values.last() shouldBe (100f plusOrMinus 0.01f)
        // And monotonically: a slider that overshoots and settles is one you cannot aim with.
        values.zipWithNext { a, b -> (b >= a) shouldBe true }
    }

    @Test
    fun `straying from the track slows the drag but never reverses it`() {
        // **The defect this test exists for.** Gain used to multiply the finger's *total*
        // displacement rather than each step, so moving down mid-drag re-scaled the whole journey
        // retroactively and the value slid backwards while the finger was still going forwards.
        // Somebody reaching away from the track for precision watched their value run away from
        // them, which is the opposite of what the feature is for.
        val values = drag(from = 0f).stroke(
            60f to 0f,
            120f to 0f,
            // The finger now leaves the track, hard.
            180f to 400f,
            240f to 400f,
        )
        values.zipWithNext { a, b -> (b >= a) shouldBe true }
        // Slowed, though: the last two steps are the same distance as the first two and must have
        // bought far less of the range.
        val nearTrack = values[1] - values[0]
        val farFromTrack = values[3] - values[2]
        (farFromTrack < nearTrack / 2f) shouldBe true
    }

    @Test
    fun `dragging past the end does not bank up travel that has to be paid back`() {
        // Push well beyond the end, then come back. Without a clamp on the accumulated fraction the
        // slider sits pinned at 100 for the entire return journey — the finger moves and nothing
        // happens, which reads as the control having stuck.
        val drag = drag(from = 50f)
        drag.stroke(600f to 0f, 900f to 0f)
        val comingBack = drag.stroke(880f to 0f, 850f to 0f)
        (comingBack[0] < 100f) shouldBe true
        (comingBack[1] < comingBack[0]) shouldBe true
    }

    @Test
    fun `the gain reported is the one that was applied`() {
        // The readout tints itself when precision is engaged, and it reads `gain` after the step.
        // If that were the gain of the *next* position the tint would lag a frame behind the
        // behaviour, which is the kind of thing that makes a control feel unresponsive.
        val d = drag()
        d.advance(10f, 0f)
        d.gain shouldBe 1f
        d.advance(20f, 400f)
        (d.gain < 0.5f) shouldBe true
    }

    @Test
    fun `the dead zone is wide enough that an ordinary drag stays at full speed`() {
        val d = drag()
        // A finger wanders vertically by a few points over a stroke; that must not engage precision.
        d.gainAt(0f) shouldBe 1f
        d.gainAt(20f) shouldBe 1f
        // And it does engage once the user genuinely reaches away.
        (d.gainAt(200f) < 0.5f) shouldBe true
    }

    @Test
    fun `a zero-width track cannot move the value`() {
        // Laid out but not yet measured. Returning a value derived from a division by zero here
        // would put a NaN into the document, and a NaN in a parameter is not recoverable by undo.
        val d = SliderDrag(spec, 40f, 0f to 0f, trackWidth = 0f, trackY = 0f, deadZone = 24f, falloff = 120f)
        d.advance(100f, 0f) shouldBe 40f
    }
}
