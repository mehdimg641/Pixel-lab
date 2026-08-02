package ir.pixellab.core.render

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Adjustment
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.Vec3
import org.junit.jupiter.api.Test

/**
 * How an adjustment reaches the shader.
 *
 * Packing is exactly the kind of code that fails silently: a parameter written into the wrong slot
 * produces a plausible-looking picture rather than an error, and the only way anyone finds out is by
 * noticing months later that Exposure's offset does nothing. Every adjustment is checked here.
 */
class AdjustmentsTest {

    @Test
    fun `every adjustment maps to a mode`() {
        val all = listOf<Adjustment>(
            Adjustment.BrightnessContrast(),
            Adjustment.Levels(),
            Adjustment.Curves(),
            Adjustment.HueSaturation(),
            Adjustment.Exposure(),
            Adjustment.Vibrance(),
            Adjustment.ColorBalance(),
            Adjustment.BlackWhite(),
            Adjustment.GradientMap(gradient()),
            Adjustment.PhotoFilter(Color.WHITE),
            Adjustment.Invert,
            Adjustment.Posterize(),
            Adjustment.Threshold(),
            Adjustment.ColorLookup(ir.pixellab.core.model.AssetId("lut")),
            Adjustment.SelectiveColor(),
            Adjustment.ChannelMixer(),
        )
        // Every one of them, and each to a distinct mode: two adjustments sharing a mode is one of
        // them silently doing the other's job.
        all.map { AdjustmentMode.of(it) }.distinct().size shouldBe all.size
        all.size shouldBe AdjustmentMode.entries.size
    }

    @Test
    fun `brightness and contrast reach their own slots`() {
        val packed = AdjustmentUniforms.of(Adjustment.BrightnessContrast(brightness = 0.3f, contrast = -0.2f))
        packed.p0[0] shouldBe 0.3f
        packed.p0[1] shouldBe -0.2f
    }

    @Test
    fun `levels carries all five of its numbers`() {
        val packed = AdjustmentUniforms.of(
            Adjustment.Levels(
                inputBlack = 0.1f, inputWhite = 0.9f, gamma = 1.4f,
                outputBlack = 0.05f, outputWhite = 0.95f,
            ),
        )
        packed.p0.toList() shouldBe listOf(0.1f, 0.9f, 1.4f, 0.05f)
        packed.p1[0] shouldBe 0.95f
        // No per-channel levels, so no table is needed and none is built.
        packed.needsCurves shouldBe false
    }

    @Test
    fun `per-channel levels ask for a table rather than more uniforms`() {
        val packed = AdjustmentUniforms.of(
            Adjustment.Levels(perChannel = listOf(Adjustment.Levels(gamma = 2f))),
        )
        // Three more sets of five would be fifteen floats; a table is one texture read.
        packed.needsCurves shouldBe true
        packed.p1[1] shouldBe 1f
    }

    @Test
    fun `curves always asks for its table`() {
        AdjustmentUniforms.of(Adjustment.Curves(rgb = Curve(listOf(Vec2(0f, 0.2f), Vec2(1f, 1f))))).needsCurves shouldBe true
    }

    @Test
    fun `colorize is a flag rather than a separate adjustment`() {
        val plain = AdjustmentUniforms.of(Adjustment.HueSaturation(hue = 0.25f, saturation = 0.5f))
        val colorized = AdjustmentUniforms.of(Adjustment.HueSaturation(hue = 0.25f, colorize = true))
        plain.p0[3] shouldBe 0f
        // The checkbox changes what the hue *means* — replacing rather than rotating — which is why
        // it has to reach the shader rather than being folded into the value.
        colorized.p0[3] shouldBe 1f
    }

    @Test
    fun `colour balance keeps its three ranges apart`() {
        val packed = AdjustmentUniforms.of(
            Adjustment.ColorBalance(
                shadows = Vec3(0.1f, 0f, -0.1f),
                midtones = Vec3(0f, 0.2f, 0f),
                highlights = Vec3(-0.3f, 0f, 0.3f),
            ),
        )
        packed.p0.take(3) shouldBe listOf(0.1f, 0f, -0.1f)
        packed.p1.take(3) shouldBe listOf(0f, 0.2f, 0f)
        packed.p2.take(3) shouldBe listOf(-0.3f, 0f, 0.3f)
        // Preserve luminosity defaults on, and rides in the spare slot of the first vector.
        packed.p0[3] shouldBe 1f
    }

    @Test
    fun `black and white carries all six weights`() {
        val packed = AdjustmentUniforms.of(
            Adjustment.BlackWhite(listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)),
        )
        // Six rather than three is what lets a red jumper and a green hedge of the same luminance be
        // told apart in monochrome, which three channels cannot do at all.
        packed.p0.toList() shouldBe listOf(0.1f, 0.2f, 0.3f, 0.4f)
        packed.p1.take(2) shouldBe listOf(0.5f, 0.6f)
    }

    @Test
    fun `black and white falls back to photoshop's defaults when given too few weights`() {
        val packed = AdjustmentUniforms.of(Adjustment.BlackWhite(listOf(0.1f)))
        packed.p0[0] shouldBe 0.1f
        packed.p0[1] shouldBe 0.6f
    }

    @Test
    fun `a gradient map asks for a ramp`() {
        val packed = AdjustmentUniforms.of(Adjustment.GradientMap(gradient(), dither = true))
        packed.needsRamp shouldBe true
        packed.p0[0] shouldBe 1f
    }

    @Test
    fun `a photo filter carries its colour and its density`() {
        val packed = AdjustmentUniforms.of(
            Adjustment.PhotoFilter(Color(0.9f, 0.5f, 0.1f), density = 0.4f, preserveLuminosity = false),
        )
        packed.p0.toList() shouldBe listOf(0.9f, 0.5f, 0.1f, 0.4f)
        packed.p1[0] shouldBe 0f
    }

    @Test
    fun `posterize never asks for fewer than two levels`() {
        // One level is a division by zero in the shader and a black frame on screen.
        AdjustmentUniforms.of(Adjustment.Posterize(levels = 1)).p0[0] shouldBe 2f
        AdjustmentUniforms.of(Adjustment.Posterize(levels = 8)).p0[0] shouldBe 8f
    }

    @Test
    fun `a lookup carries its amount and asks for its strip`() {
        val packed = AdjustmentUniforms.of(
            Adjustment.ColorLookup(ir.pixellab.core.model.AssetId("teal-orange"), amount = 0.6f),
        )
        packed.needsLut shouldBe true
        packed.p0[0] shouldBe 0.6f
    }

    @Test
    fun `selective colour rides in the table rather than in uniforms`() {
        val packed = AdjustmentUniforms.of(
            Adjustment.SelectiveColor(absolute = true),
        )
        // Nine families of four inks is thirty-six numbers. Packed as uniforms that is nine vec4s
        // for something one texture read answers.
        packed.needsCurves shouldBe true
        packed.p0[0] shouldBe 1f
    }

    @Test
    fun `each family lands in its own texel, with the inks in the shader's own order`() {
        val table = AdjustmentUniforms.selectiveColorTable(
            Adjustment.SelectiveColor(
                ranges = listOf(
                    ir.pixellab.core.model.ColorRange(
                        ir.pixellab.core.model.ColorFamily.YELLOWS,
                        cyan = 1f, magenta = -1f, yellow = 0f, black = 0.5f,
                    ),
                ),
            ),
            size = 256,
        )

        // Yellows is the second family, so the second texel. The shader reads r as cyan, g as
        // magenta, b as yellow and a as black — a swap here is a correction applied to the wrong
        // ink, which looks like a colour cast rather than like a bug.
        val yellows = table[1]
        ((yellows shr 16) and 0xFF) shouldBe 255
        ((yellows shr 8) and 0xFF) shouldBe 0
        (yellows and 0xFF) shouldBe 128
        ((yellows ushr 24) and 0xFF) shouldBe 191
    }

    @Test
    fun `a family the user never touched shifts nothing`() {
        val table = AdjustmentUniforms.selectiveColorTable(Adjustment.SelectiveColor(), size = 256)
        // Biased storage means "no change" is 128, not 0 — and a texel left at zero would push a
        // full negative correction into every range the user never opened.
        for (texel in table) {
            for (shift in intArrayOf(24, 16, 8, 0)) ((texel shr shift) and 0xFF) shouldBe 128
        }
    }

    @Test
    fun `the channel mixer packs one recipe per row`() {
        val packed = AdjustmentUniforms.of(
            Adjustment.ChannelMixer(
                red = ir.pixellab.core.model.ChannelRecipe(red = 1f, green = 0.2f, constant = 0.05f),
                green = ir.pixellab.core.model.ChannelRecipe(green = 1f),
                blue = ir.pixellab.core.model.ChannelRecipe(blue = 1f),
            ),
        )
        packed.p0[0] shouldBe 1f
        packed.p0[1] shouldBe 0.2f
        packed.p0[3] shouldBe 0.05f
        packed.p1[1] shouldBe 1f
        packed.p2[2] shouldBe 1f
    }

    @Test
    fun `monochrome is the same recipe in all three rows`() {
        val packed = AdjustmentUniforms.of(
            Adjustment.ChannelMixer(
                monochrome = true,
                gray = ir.pixellab.core.model.ChannelRecipe(red = 0.8f, green = 0.1f, blue = 0.1f),
            ),
        )
        // Not a shader flag: a monochrome mix *is* three identical rows, so writing it that way
        // leaves the branch with one path and makes the preview literally the exported picture.
        for (row in listOf(packed.p0, packed.p1, packed.p2)) {
            row[0] shouldBe 0.8f
            row[1] shouldBe 0.1f
            row[2] shouldBe 0.1f
        }
    }

    @Test
    fun `an adjustment that needs no table says so`() {
        val packed = AdjustmentUniforms.of(Adjustment.Invert)
        // The renderer binds a white stand-in for every sampler a branch does not read; asking for a
        // table that will not be sampled would build one per frame for nothing.
        packed.needsCurves shouldBe false
        packed.needsRamp shouldBe false
        packed.needsLut shouldBe false
    }

    @Test
    fun `the shader declares every uniform the adjustment pass sets`() {
        val source = Shaders.ADJUST.fragment
        for (name in listOf("uMode", "uP0", "uP1", "uP2", "uCurves", "uRamp", "uLut")) {
            // A misspelled uniform is silent: the effect renders with a zeroed parameter and looks
            // merely wrong rather than broken.
            (name in source) shouldBe true
        }
    }

    @Test
    fun `every mode has a branch in the shader`() {
        val source = Shaders.ADJUST.fragment
        for (mode in AdjustmentMode.entries) {
            // Adding an adjustment without a branch would leave it rendering as a plain copy, which
            // reads as the layer having no effect rather than as a missing feature.
            ("uMode == ${mode.ordinal}" in source) shouldBe true
        }
    }

    private fun gradient() = Fill.Gradient(
        stops = listOf(GradientStop(0f, Color.BLACK), GradientStop(1f, Color.WHITE)),
    )
}
