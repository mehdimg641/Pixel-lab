package ir.pixellab.core.render

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientStop
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LutsTest {

    private fun ramp(vararg stops: GradientStop, reverse: Boolean = false, curve: Curve = Curve.LINEAR) =
        Fill.Gradient(stops = stops.toList(), reverse = reverse, interpolation = curve)

    private val blackToWhite = ramp(
        GradientStop(0f, Color.BLACK),
        GradientStop(1f, Color.WHITE),
    )

    @Test
    fun `a curve table spans its whole domain`() {
        val table = Luts.curve(Curve.LINEAR, size = 5)
        table.toList() shouldBe listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    }

    @Test
    fun `a shaped curve keeps its ends and bends in between`() {
        val table = Luts.curve(Curve.ROUNDED, size = 256)
        table.first() shouldBe 0f
        table.last() shouldBe 1f
        // A rounded shoulder climbs fast then flattens; that is what makes a bevel puffy rather
        // than conical.
        (table[64] > 0.25f) shouldBe true
    }

    @Test
    fun `a table of fewer than two samples is rejected`() {
        assertThrows<IllegalArgumentException> { Luts.curve(Curve.LINEAR, size = 1) }
        assertThrows<IllegalArgumentException> { Luts.gradient(blackToWhite, size = 1) }
    }

    @Test
    fun `a two stop ramp runs end to end`() {
        val table = Luts.gradient(blackToWhite, size = 3)
        colorFromArgb(table.first()).r shouldBe 0f
        colorFromArgb(table[1]).r shouldBe (0.5f plusOrMinus 0.01f)
        colorFromArgb(table.last()).r shouldBe 1f
    }

    @Test
    fun `the midpoint moves where the halfway blend falls`() {
        val early = Luts.colorAt(
            listOf(GradientStop(0f, Color.BLACK, midpoint = 0.25f), GradientStop(1f, Color.WHITE)),
            0.25f,
        )
        // Photoshop's diamond: at the midpoint the blend is exactly half, wherever it sits.
        early.r shouldBe (0.5f plusOrMinus 0.001f)

        val late = Luts.colorAt(
            listOf(GradientStop(0f, Color.BLACK, midpoint = 0.75f), GradientStop(1f, Color.WHITE)),
            0.75f,
        )
        late.r shouldBe (0.5f plusOrMinus 0.001f)
    }

    @Test
    fun `the default midpoint changes nothing`() {
        // The common case has to cost nothing and, more importantly, has to be exactly linear —
        // a near-miss here would tint every untouched gradient in the library.
        Luts.biased(0.3f, 0.5f) shouldBe 0.3f
        Luts.colorAt(blackToWhite.stops, 0.3f).r shouldBe (0.3f plusOrMinus 0.0001f)
    }

    @Test
    fun `a midpoint at the extreme does not produce infinities`() {
        val value = Luts.colorAt(
            listOf(GradientStop(0f, Color.BLACK, midpoint = 0f), GradientStop(1f, Color.WHITE)),
            0.5f,
        )
        value.r.isFinite() shouldBe true
        value.r shouldBe (value.r.coerceIn(0f, 1f) plusOrMinus 0.0001f)
    }

    @Test
    fun `reversing flips the ramp`() {
        val forward = Luts.gradient(blackToWhite, size = 16)
        val backward = Luts.gradient(blackToWhite.copy(reverse = true), size = 16)
        backward.toList() shouldBe forward.reversed()
    }

    @Test
    fun `stops out of order are sorted rather than trusted`() {
        val jumbled = ramp(
            GradientStop(1f, Color.WHITE),
            GradientStop(0f, Color.BLACK),
        )
        // A PSD does not guarantee the order, and an unsorted list would make the ramp jump.
        val table = Luts.gradient(jumbled, size = 3)
        colorFromArgb(table.first()).r shouldBe 0f
        colorFromArgb(table.last()).r shouldBe 1f
    }

    @Test
    fun `alpha interpolates alongside colour`() {
        val fading = Luts.colorAt(
            listOf(GradientStop(0f, Color.WHITE), GradientStop(1f, Color.WHITE.withAlpha(0f))),
            0.5f,
        )
        fading.a shouldBe (0.5f plusOrMinus 0.001f)
        // Straight alpha: the colour must not have been dragged towards black on the way.
        fading.r shouldBe 1f
    }

    @Test
    fun `the interpolation curve shapes the whole ramp`() {
        val eased = Luts.gradient(blackToWhite.copy(interpolation = Curve.EASE_IN_OUT), size = 256)
        val linear = Luts.gradient(blackToWhite, size = 256)
        // The ends must still be the stops themselves; only the journey changes.
        eased.first() shouldBe linear.first()
        eased.last() shouldBe linear.last()
        (colorFromArgb(eased[64]).r < colorFromArgb(linear[64]).r) shouldBe true
    }

    @Test
    fun `a sample before the first stop or after the last clamps`() {
        val inset = listOf(GradientStop(0.25f, Color.BLACK), GradientStop(0.75f, Color.WHITE))
        Luts.colorAt(inset, 0f).r shouldBe 0f
        Luts.colorAt(inset, 1f).r shouldBe 1f
        Luts.colorAt(inset, 0.5f).r shouldBe (0.5f plusOrMinus 0.001f)
    }

    @Test
    fun `argb packing round trips`() {
        val original = Color(0.2f, 0.4f, 0.6f, 0.8f)
        val back = colorFromArgb(original.toArgb())
        back.r shouldBe (original.r plusOrMinus 0.004f)
        back.g shouldBe (original.g plusOrMinus 0.004f)
        back.b shouldBe (original.b plusOrMinus 0.004f)
        back.a shouldBe (original.a plusOrMinus 0.004f)
    }

    @Test
    fun `packing clamps rather than wrapping`() {
        // An out-of-range value from an HDR import must not wrap round to a dark colour.
        val over = Color(2f, -1f, 0.5f, 3f)
        val back = colorFromArgb(over.toArgb())
        back.r shouldBe 1f
        back.g shouldBe 0f
        back.a shouldBe 1f
    }
}
