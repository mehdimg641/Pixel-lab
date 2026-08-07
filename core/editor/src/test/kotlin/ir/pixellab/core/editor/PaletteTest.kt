package ir.pixellab.core.editor

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Color
import org.junit.jupiter.api.Test

/**
 * The foreground/background pair.
 *
 * Small, and worth testing anyway: half of Photoshop's tool behaviour is defined in terms of it, so
 * a swap that lost a colour or a reset that landed somewhere other than black-on-white would show up
 * as a dozen unrelated-looking bugs in the tools that read it.
 */
class PaletteTest {

    private val red = Color(1f, 0f, 0f)
    private val blue = Color(0f, 0f, 1f)

    @Test
    fun `the default is black on white, which is what every mask starts from`() {
        Palette.DEFAULT.foreground shouldBe Color.BLACK
        Palette.DEFAULT.background shouldBe Color.WHITE
    }

    @Test
    fun `swapping exchanges the two and loses neither`() {
        val swapped = Palette(red, blue).swapped()
        swapped.foreground shouldBe blue
        swapped.background shouldBe red
    }

    @Test
    fun `swapping twice is the identity`() {
        val palette = Palette(red, blue)
        palette.swapped().swapped() shouldBe palette
    }

    @Test
    fun `reset returns to the default from anywhere`() {
        Palette(red, blue).reset() shouldBe Palette.DEFAULT
    }

    @Test
    fun `setting one slot leaves the other alone`() {
        // The property every picker depends on: choosing a foreground must not silently move the
        // background, or an eraser would change colour whenever the brush did.
        val palette = Palette(red, blue).withForeground(Color.WHITE)
        palette.foreground shouldBe Color.WHITE
        palette.background shouldBe blue
    }

    @Test
    fun `the slot indirection reads and writes the same place`() {
        // A picker is generic over the slot, so the two have to agree or it would write one colour
        // and display another.
        for (slot in Palette.Slot.entries) {
            Palette.DEFAULT.with(slot, red)[slot] shouldBe red
        }
    }

    @Test
    fun `it is a value, so a caller cannot mutate it out from under a reader`() {
        val original = Palette(red, blue)
        original.withForeground(Color.WHITE)
        original.foreground shouldBe red
    }
}
