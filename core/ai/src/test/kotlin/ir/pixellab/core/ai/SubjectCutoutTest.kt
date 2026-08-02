package ir.pixellab.core.ai

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The seam between the classical cut-out and a neural one.
 *
 * What is being tested is not a model — there is none in the repository and there never will be
 * one committed. It is the *seam*: that the app works with no model, that it uses one when there is
 * one, that a model which fails does not take the feature down with it, and that the upsample from
 * a model's small output does not throw away the sub-pixel information the model produced.
 */
class SubjectCutoutTest {

    /** A red square on a blue field: something both paths can find. */
    private fun subjectOnBackground(size: Int = 64): IntArray {
        val pixels = IntArray(size * size) { 0xFF2040A0.toInt() }
        for (y in size / 4 until size * 3 / 4) {
            for (x in size / 4 until size * 3 / 4) pixels[y * size + x] = 0xFFC03020.toInt()
        }
        return pixels
    }

    private class FakeModel(
        override val name: String = "fake",
        override val inputSize: Int = 8,
        private val answer: ((IntArray, Int, Int) -> SegmentationModel.Mask?)? = null,
    ) : SegmentationModel {
        var calls = 0
            private set

        override fun infer(pixels: IntArray, width: Int, height: Int): SegmentationModel.Mask? {
            calls++
            return answer?.invoke(pixels, width, height)
        }
    }

    /** A mask that says "the middle four of eight, in both axes". */
    private fun centreMask(size: Int = 8): SegmentationModel.Mask {
        val confidence = ByteArray(size * size)
        for (y in size / 4 until size * 3 / 4) {
            for (x in size / 4 until size * 3 / 4) confidence[y * size + x] = 255.toByte()
        }
        return SegmentationModel.Mask(size, size, confidence)
    }

    @Test
    fun `with no model at all the classical path still cuts out a subject`() {
        // The whole point of the design: the app works on the first day, with no download.
        val cutout = SubjectCutout()
        cutout.usingModel shouldBe false

        val selection = cutout.select(subjectOnBackground(), 64, 64)
        selection.isEmpty shouldBe false
        selection[32, 32] shouldBe 255
        selection[2, 2] shouldBe 0
    }

    @Test
    fun `a model is used when one is present`() {
        val model = FakeModel(answer = { _, _, _ -> centreMask() })
        val cutout = SubjectCutout(model)
        cutout.usingModel shouldBe true

        val selection = cutout.select(subjectOnBackground(), 64, 64)
        model.calls shouldBe 1
        selection[32, 32] shouldBe 255
        selection[2, 2] shouldBe 0
    }

    @Test
    fun `a model that declines falls back rather than returning nothing`() {
        // A network can legitimately find no subject, and "no subject" from the model must not mean
        // "no cut-out" for the user — the classical path may well find one.
        val model = FakeModel(answer = { _, _, _ -> null })
        val selection = SubjectCutout(model).select(subjectOnBackground(), 64, 64)
        selection.isEmpty shouldBe false
    }

    @Test
    fun `a model that throws does not take the feature down with it`() {
        // The realistic failure: a file that is corrupt, compiled for another ABI, or out of memory.
        // It happens on the user's device and never on the developer's, so the fallback is the only
        // thing standing between it and a feature that is simply broken for them.
        val model = FakeModel(answer = { _, _, _ -> error("delegate failed to load") })
        val selection = SubjectCutout(model).select(subjectOnBackground(), 64, 64)
        selection.isEmpty shouldBe false
    }

    @Test
    fun `the upsample keeps a soft edge rather than a stepped one`() {
        // Thresholding at the model's resolution turns the mask into 8-pixel blocks and then
        // smooths the blocks — a visibly stepped edge that no refinement recovers. Scaling the
        // confidence first is what keeps the boundary continuous.
        val cutout = SubjectCutout(FakeModel())
        val selection = cutout.upsample(centreMask(), 64, 64, sensitivity = 0.5f)

        var partial = 0
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val v = selection[x, y]
                if (v in 1..254) partial++
            }
        }
        (partial > 0) shouldBe true
    }

    @Test
    fun `sensitivity moves how much is taken, on the model path too`() {
        // The same control has to do the same thing whichever path answered, or a user who nudges
        // it after installing a model finds it behaving backwards.
        val cutout = SubjectCutout(FakeModel())
        val timid = cutout.upsample(centreMask(), 64, 64, sensitivity = 0.1f)
        val eager = cutout.upsample(centreMask(), 64, 64, sensitivity = 0.9f)
        (eager.selectedArea() >= timid.selectedArea()) shouldBe true
    }

    @Test
    fun `the description says which path answered`() {
        // Shown in settings. A user comparing two cut-outs needs to know whether the difference is
        // the model or the picture.
        (SubjectCutout().describe.contains("کلاسیک")) shouldBe true
        SubjectCutout(FakeModel(name = "U2Net")).describe shouldBe "U2Net"
    }

    @Test
    fun `a mismatched pixel count is refused rather than read past the end`() {
        val cutout = SubjectCutout()
        runCatching { cutout.select(IntArray(10), 64, 64) }.isFailure shouldBe true
    }
}
