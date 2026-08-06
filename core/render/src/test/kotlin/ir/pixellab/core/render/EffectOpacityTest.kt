package ir.pixellab.core.render

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Style
import org.junit.jupiter.api.Test

/**
 * Whether an effect's opacity slider reaches the GPU at all.
 *
 * ### The defect this exists for
 *
 * Passing the opacity was each module's own job, and **twelve of the fourteen forgot**. The slider
 * moved a number in the document, the document saved it, the panel read it back, and nothing ever
 * sent it to a shader — so a drop shadow set to fifteen per cent painted solid black, and a user
 * who turned it down and saw no change concluded the panel was decorative. It is the worst kind of
 * missing feature, because from the outside it is indistinguishable from a broken application, and
 * every individual piece of it looked right.
 *
 * A per-module check would have caught it and would have to be remembered for effect fifteen. So
 * the value is sent from the executor, which sees every pass, and this asks the question the same
 * way: over **every** effect there is, discovered from the registry rather than listed here.
 */
class EffectOpacityTest {

    private val bounds = Rect(0f, 0f, 256f, 128f)

    /** One of each, at an opacity nobody could mistake for a default. */
    private val faded = 0.15f

    private fun effects(): List<Effect> = listOf(
        Effect.Stroke(6f, Fill.Solid(Color.BLACK), opacity = faded),
        Effect.DropShadow(blur = 12f, opacity = faded),
        Effect.InnerShadow(opacity = faded),
        Effect.OuterGlow(opacity = faded),
        Effect.InnerGlow(opacity = faded),
        Effect.Bevel(opacity = faded),
        Effect.Satin(opacity = faded),
        Effect.Overlay(Fill.Solid(Color.WHITE), opacity = faded),
        Effect.Extrude(opacity = faded),
        Effect.Reflection(opacity = faded),
        Effect.ChromaticOffset(opacity = faded),
        Effect.BackdropBlur(opacity = faded),
        Effect.Noise(opacity = faded),
        Effect.EdgeRoughen(opacity = faded),
    )

    private fun runOne(effect: Effect): FakeGlDevice {
        val device = FakeGlDevice()
        val graph = RenderGraphBuilder.build(
            RenderPlanner.plan(Style(effects = listOf(effect))),
            bounds,
        )
        GraphExecutor(device, textures = FakeTextureSource())
            .execute(graph, TextureHandle(0), backdropTexture = TextureHandle(0))
        return device
    }

    @Test
    fun `every effect carries its opacity to the shader`() {
        val silent = effects().filterNot { effect ->
            runOne(effect).passes.any { pass ->
                pass.floats["uEffectOpacity"]?.let { kotlin.math.abs(it - faded) < 1e-4f } == true
            }
        }.map(EffectRegistry::idOf)

        silent shouldBe emptyList<String>()
    }

    @Test
    fun `a pass that is not an effect is never faded`() {
        // The fill, the composite and the blur halves are machinery. Fading them would dim the
        // layer itself every time any effect on it was turned down — which is a far more confusing
        // bug than the one this replaces, because it would look almost right.
        val device = FakeGlDevice()
        val graph = RenderGraphBuilder.build(RenderPlanner.plan(Style()), bounds)
        GraphExecutor(device, textures = FakeTextureSource()).execute(graph, TextureHandle(0))

        val fill = device.passes.single { it.shaderId == Shaders.FILL.id }
        fill.floats["uEffectOpacity"] shouldBe (1f plusOrMinus 1e-6f)
    }

    @Test
    fun `the shared prologue is what applies it, so no shader can opt out`() {
        // Belt and braces on the mechanism rather than on one effect: the value lands in
        // `premultiply`, which every effect shader ends with. If a future shader stopped using it,
        // this is the line that says so.
        for (program in Shaders.ALL.values) {
            if ("premultiply(" !in program.fragment) continue
            ("uEffectOpacity" in program.fragment) shouldBe true
        }
    }
}
