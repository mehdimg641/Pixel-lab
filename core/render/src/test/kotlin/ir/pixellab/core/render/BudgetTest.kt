package ir.pixellab.core.render

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The frame and memory policies.
 *
 * Both are pure arithmetic on purpose: an adaptive renderer's *policy* is where the oscillation and
 * the out-of-memory kills come from, and neither is reproducible on a device. Here they are.
 */
class BudgetTest {

    private fun budget(window: Int = 16) = FrameBudget(window = window)

    private fun FrameBudget.fill(nanos: Long) = repeat(window) { record(nanos) }

    private val comfortable = FrameBudget.SIXTY_HZ / 2
    private val overBudget = FrameBudget.SIXTY_HZ * 2

    @Test
    fun `a percentile follows the worst frames rather than the average`() {
        val b = budget()
        // Fifteen good frames and one terrible one. The average says everything is fine; the
        // ninety-fifth percentile says what the user actually felt.
        repeat(15) { b.record(comfortable) }
        b.record(FrameBudget.SIXTY_HZ * 4)

        (b.mean() < FrameBudget.SIXTY_HZ) shouldBe true
        (b.percentile(0.95f) > FrameBudget.SIXTY_HZ) shouldBe true
    }

    @Test
    fun `steady frames inside the budget climb back to full quality`() {
        val b = budget()
        b.fill(comfortable)
        b.recommend(Quality.INTERACTIVE) shouldBe Quality.HIGH
    }

    @Test
    fun `frames well over budget drop the quality`() {
        val b = budget()
        b.fill(overBudget)
        b.recommend(Quality.FULL) shouldBe Quality.HIGH
        b.recommend(Quality.HIGH) shouldBe Quality.INTERACTIVE
    }

    @Test
    fun `the thresholds are asymmetric, so the quality does not oscillate`() {
        // The property that keeps an adaptive renderer from pulsing. A frame time that is *just*
        // inside the budget must not trigger a climb, or the next frame at that quality is over it
        // again and the picture visibly throbs while the user does nothing.
        val b = budget()
        b.fill((FrameBudget.SIXTY_HZ * 0.9f).toLong())
        b.recommend(Quality.INTERACTIVE) shouldBe Quality.INTERACTIVE
    }

    @Test
    fun `too few samples changes nothing`() {
        val b = budget()
        // Reacting to three frames means reacting to the app starting up, which is always slow and
        // never representative.
        repeat(3) { b.record(overBudget) }
        b.recommend(Quality.FULL) shouldBe Quality.FULL
    }

    @Test
    fun `jitter separates steady slowness from stutter`() {
        // Steady slowness is far less noticeable than the same mean delivered unevenly, which is
        // why this is measured separately rather than folded into the average.
        val steady = budget()
        steady.fill(FrameBudget.SIXTY_HZ)

        val uneven = budget()
        repeat(8) { uneven.record(1L) }
        repeat(8) { uneven.record(FrameBudget.SIXTY_HZ * 2) }

        (uneven.jitter() > steady.jitter()) shouldBe true
    }

    @Test
    fun `missed frames are counted, not averaged away`() {
        val b = budget()
        repeat(12) { b.record(comfortable) }
        repeat(4) { b.record(overBudget) }
        b.missed() shouldBe 4
    }

    @Test
    fun `the window is a ring, so old frames stop counting`() {
        val b = budget(window = 8)
        repeat(8) { b.record(overBudget) }
        repeat(8) { b.record(comfortable) }
        // A renderer that never forgot a bad frame would stay at minimum quality for the rest of
        // the session after one slow moment.
        b.missed() shouldBe 0
    }

    @Test
    fun `resetting clears the history`() {
        val b = budget()
        b.fill(overBudget)
        b.reset()
        b.recorded shouldBe 0
        b.recommend(Quality.FULL) shouldBe Quality.FULL
    }

    @Test
    fun `quality tiers scale the target`() {
        Quality.FULL.scale shouldBe 1f
        Quality.INTERACTIVE.scale shouldBe 0.5f
        Quality.MINIMAL.scale shouldBe 0.25f
    }

    // ---- memory ----------------------------------------------------------------------------

    @Test
    fun `a modest canvas fits and a huge one does not`() {
        val budget = RasterBudget()
        budget.fits(4000, 3000) shouldBe true
        // A 48-megapixel photograph at 32-bit float is 768 MB before anything is drawn.
        budget.fits(8000, 6000, RasterBudget.RGBA_F32) shouldBe false
    }

    @Test
    fun `the scale to fit is the square root, because area is squared`() {
        // The mistake worth guarding against: halving the *linear* size to fix a two-times overrun
        // quarters the memory and throws away three-quarters of the resolution for nothing.
        val budget = RasterBudget(limitBytes = 4L * 1024 * 1024)
        val scale = budget.scaleToFit(2048, 2048)
        val fitted = (2048 * scale).toInt()
        (budget.fits(fitted, fitted)) shouldBe true
        (scale > 0.4f && scale < 0.6f) shouldBe true
    }

    @Test
    fun `a request that already fits is left alone`() {
        // So the caller can apply the result unconditionally. A branch skipped in the common case
        // is a branch nobody tests.
        RasterBudget().scaleToFit(512, 512) shouldBe 1f
        RasterBudget().halvingsToFit(512, 512) shouldBe 0
    }

    @Test
    fun `halving stops as soon as it fits`() {
        val budget = RasterBudget(limitBytes = 1024L * 1024)
        val steps = budget.halvingsToFit(2048, 2048)
        val size = 2048 shr steps
        budget.fits(size, size) shouldBe true
        budget.fits(size * 2, size * 2) shouldBe false
    }

    @Test
    fun `several buffers are counted`() {
        // The composite needs a source and a destination at the very least, and a filter needs a
        // third. Budgeting for one is how an app that "fits" is killed on the first blur.
        val budget = RasterBudget(limitBytes = 16L * 1024 * 1024)
        budget.fits(2048, 2048, buffers = 1) shouldBe true
        budget.fits(2048, 2048, buffers = 3) shouldBe false
    }
}
