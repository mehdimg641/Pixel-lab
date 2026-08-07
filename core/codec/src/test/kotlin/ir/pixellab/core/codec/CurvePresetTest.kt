package ir.pixellab.core.codec

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Adjustment
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

class CurvePresetTest {

    private fun bytes(vararg shorts: Int) = ByteArray(shorts.size * 2) { i ->
        val value = shorts[i / 2]
        if (i % 2 == 0) ((value shr 8) and 0xFF).toByte() else (value and 0xFF).toByte()
    }

    @Test
    fun `a two-point identity curve reads back as the identity`() {
        val file = bytes(CurvePreset.VERSION, 1, 2, 0, 0, 255, 255)
        val curves = CurvePreset.read(file)!!
        curves.rgb.evaluate(0.5f) shouldBe 0.5f.plusOrMinus(0.005f)
    }

    @Test
    fun `the pair order is output then input`() {
        // The one thing every implementation of this format gets wrong, and it fails silently: a
        // curve read the wrong way round is still a valid curve, just the inverse of the drawn one.
        // Here the author lifted the midpoint — input 128 comes out at 192.
        val file = bytes(CurvePreset.VERSION, 1, 3, 0, 0, 192, 128, 255, 255)
        val curves = CurvePreset.read(file)!!
        curves.rgb.evaluate(128f / 255f) shouldBe (192f / 255f).plusOrMinus(0.005f)
    }

    @Test
    fun `all four curves land in the right channels`() {
        val file = bytes(
            CurvePreset.VERSION, 4,
            2, 0, 0, 255, 255,
            3, 0, 0, 200, 128, 255, 255,
            3, 0, 0, 100, 128, 255, 255,
            2, 0, 0, 255, 255,
        )
        val curves = CurvePreset.read(file)!!
        curves.red.evaluate(128f / 255f) shouldBe (200f / 255f).plusOrMinus(0.005f)
        curves.green.evaluate(128f / 255f) shouldBe (100f / 255f).plusOrMinus(0.005f)
        curves.blue.evaluate(0.5f) shouldBe 0.5f.plusOrMinus(0.005f)
    }

    @Test
    fun `a channel Photoshop left alone becomes the identity`() {
        // Written as zero points, and the model needs two — so "untouched" has to be manufactured
        // rather than passed through, or constructing the Curve throws on a perfectly good file.
        val file = bytes(CurvePreset.VERSION, 2, 0, 2, 0, 0, 255, 255)
        val curves = CurvePreset.read(file)!!
        curves.rgb.evaluate(0.3f) shouldBe 0.3f.plusOrMinus(0.005f)
    }

    @Test
    fun `points out of order are sorted`() {
        // The model walks the points in order; a file that lists a dragged endpoint before the
        // point it was dragged past would otherwise evaluate to the last point everywhere.
        val file = bytes(CurvePreset.VERSION, 1, 3, 255, 255, 0, 0, 192, 128)
        val curves = CurvePreset.read(file)!!
        curves.rgb.evaluate(128f / 255f) shouldBe (192f / 255f).plusOrMinus(0.005f)
    }

    @Test
    fun `a file of the wrong version is refused rather than guessed at`() {
        CurvePreset.read(bytes(1, 1, 2, 0, 0, 255, 255)) shouldBe null
    }

    @Test
    fun `a truncated file is refused`() {
        // A preset pack routinely contains a stray file; the caller wants the other 199 applied.
        CurvePreset.read(bytes(CurvePreset.VERSION, 1, 4, 0, 0)) shouldBe null
        CurvePreset.read(ByteArray(2)) shouldBe null
        CurvePreset.read(ByteArray(0)) shouldBe null
    }

    @Test
    fun `an implausible curve count is refused`() {
        // The surest sign the cursor has drifted, and reading on from a drifted cursor produces a
        // curve that looks deliberate and is noise.
        CurvePreset.read(bytes(CurvePreset.VERSION, 40)) shouldBe null
    }

    @Test
    fun `a look written here can be read back`() {
        val original = Adjustment.Curves(
            rgb = Curve(listOf(Vec2(0f, 0f), Vec2(0.25f, 0.15f), Vec2(0.75f, 0.85f), Vec2(1f, 1f))),
            red = Curve(listOf(Vec2(0f, 0.05f), Vec2(1f, 1f))),
        )
        val round = CurvePreset.read(CurvePreset.write(original))!!

        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            round.rgb.evaluate(t) shouldBe original.rgb.evaluate(t).plusOrMinus(0.005f)
            round.red.evaluate(t) shouldBe original.red.evaluate(t).plusOrMinus(0.005f)
        }
    }
}
