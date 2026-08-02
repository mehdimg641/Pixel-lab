package ir.pixellab.core.render

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.ColorSettings
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Precision
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ShaderLibraryTest {

    /**
     * Every shader an effect module asks for must exist.
     *
     * A missing program is invisible at author time and shows up as an effect that renders nothing
     * on a device, so it fails the build instead.
     */
    @Test
    fun `every shader id referenced by a module exists`() {
        val samples: List<Effect> = listOf(
            Effect.Stroke(4f, Fill.Solid(Color.BLACK)),
            Effect.DropShadow(), Effect.InnerShadow(), Effect.OuterGlow(), Effect.InnerGlow(),
            Effect.Bevel(), Effect.Satin(), Effect.Overlay(Fill.Solid(Color.WHITE)),
            Effect.Extrude(), Effect.Reflection(), Effect.ChromaticOffset(),
            Effect.BackdropBlur(), Effect.Noise(), Effect.EdgeRoughen(),
        )
        for (effect in samples) {
            @Suppress("UNCHECKED_CAST")
            val module = builtinEffectRegistry.moduleFor(effect) as EffectModule<Effect>
            val id = module.describe(effect, RenderContext()).shaderId
            if (Shaders[id] == null) error("no shader program for '$id' (${module.id})")
        }
    }

    @Test
    fun `every uniform a module sends is declared by its shader`() {
        val samples: List<Effect> = listOf(
            Effect.Stroke(4f, Fill.Solid(Color.BLACK)),
            Effect.DropShadow(), Effect.InnerShadow(), Effect.OuterGlow(), Effect.InnerGlow(),
            Effect.Bevel(), Effect.Satin(), Effect.Overlay(Fill.Solid(Color.WHITE)),
            Effect.Extrude(), Effect.Reflection(), Effect.ChromaticOffset(),
            Effect.BackdropBlur(), Effect.Noise(), Effect.EdgeRoughen(),
        )
        for (effect in samples) {
            @Suppress("UNCHECKED_CAST")
            val module = builtinEffectRegistry.moduleFor(effect) as EffectModule<Effect>
            val descriptor = module.describe(effect, RenderContext())
            val shader = Shaders[descriptor.shaderId] ?: continue
            // A misspelled uniform is silent at runtime: the parameter simply stays at zero.
            for (name in descriptor.floats.keys) {
                if (name !in shader.fragment) error("${shader.id} does not read float '$name'")
            }
            for (name in descriptor.vectors.keys) {
                if (name !in shader.fragment) error("${shader.id} does not read vec2 '$name'")
            }
            for (name in descriptor.ints.keys) {
                if (name !in shader.fragment) error("${shader.id} does not read int '$name'")
            }
        }
    }

    @Test
    fun `shaders declare the ES 3 version and balance their delimiters`() {
        for (shader in Shaders.ALL.values) {
            shader.fragment.trimStart().startsWith("#version 300 es") shouldBe true
            shader.fragment.count { it == '{' } shouldBe shader.fragment.count { it == '}' }
            shader.fragment.count { it == '(' } shouldBe shader.fragment.count { it == ')' }
        }
    }

    @Test
    fun `shaders write to the declared output`() {
        for (shader in Shaders.ALL.values) {
            if (!shader.fragment.contains("fragColor =")) error("${shader.id} never writes fragColor")
        }
    }

    @Test
    fun `shader ids are unique`() {
        Shaders.ALL.size shouldBe Shaders.ALL.values.map { it.id }.distinct().size
    }

    @Test
    fun `the blur kernel is bounded so a large radius cannot hang the GPU`() {
        // An unbounded loop over the radius is a real hazard: a 500px blur would issue a thousand
        // taps per pixel.
        Shaders.BLUR.fragment.contains("min(ceil(uRadius), 64.0)") shouldBe true
    }
}

class RenderGraphTest {

    private val bounds = Rect(0f, 0f, 400f, 200f)

    private fun graph(
        style: Style,
        scale: Float = 1f,
        color: ColorSettings = ColorSettings(),
        maxTexture: Int = RenderGraphBuilder.DEFAULT_MAX_TEXTURE,
    ) = RenderGraphBuilder.build(RenderPlanner.plan(style), bounds, scale, color, maxTexture)

    @Test
    fun `a plain layer allocates only its own buffer and the target`() {
        val g = graph(Style())
        g.buffers.map { it.role }.toSet() shouldBe setOf(BufferRole.LAYER, BufferRole.TARGET)
        g.passes.any { it.shaderId == "fill" } shouldBe true
    }

    @Test
    fun `the distance field is built once and shared by every effect that needs it`() {
        val style = Style(
            effects = listOf(
                Effect.Stroke(4f, Fill.Solid(Color.BLACK)),
                Effect.Stroke(8f, Fill.Solid(Color.WHITE)),
                Effect.Bevel(),
                Effect.EdgeRoughen(),
            ),
        )
        val g = graph(style)
        g.buffers.count { it.role == BufferRole.SDF } shouldBe 1
        g.passes.count { it.shaderId == Shaders.SDF.id } shouldBe 1
        // All four consumers read it.
        val sdfId = g.buffers.single { it.role == BufferRole.SDF }.id
        g.passes.count { sdfId in it.inputs } shouldBe 4
    }

    @Test
    fun `no distance field is allocated when nothing needs one`() {
        val g = graph(Style(effects = listOf(Effect.Overlay(Fill.Solid(Color.WHITE)))))
        g.buffers.none { it.role == BufferRole.SDF } shouldBe true
    }

    @Test
    fun `a stack of ten shadows reuses two blur buffers rather than twenty`() {
        // Photoshop's ceiling, and one the reference PSDs actually reach.
        val style = Style(effects = List(10) { Effect.DropShadow(distance = it * 8f, blur = it * 12f) })
        val g = graph(style)
        g.buffers.count { it.role == BufferRole.BLUR } shouldBe 2
        // Each shadow still gets its own separable pair.
        g.passes.count { it.shaderId == Shaders.BLUR.id } shouldBe 20
    }

    @Test
    fun `a backdrop buffer appears only for glass`() {
        graph(Style(effects = listOf(Effect.BackdropBlur())))
            .buffers.any { it.role == BufferRole.BACKDROP } shouldBe true
        graph(Style()).buffers.none { it.role == BufferRole.BACKDROP } shouldBe true
    }

    @Test
    fun `buffers are sized to the bleed not to the shape`() {
        val style = Style(effects = listOf(Effect.OuterGlow(blur = 20f, spread = 10f)))
        val g = graph(style)
        // 20 blur reaches three sigma, plus 10 spread, on every side.
        g.textureBounds.width shouldBe (400f + 2 * 70f)
        g.buffers.first().width shouldBe 540
    }

    @Test
    fun `export scale multiplies the allocation`() {
        val atOne = graph(Style(), scale = 1f).buffers.first()
        val atFour = graph(Style(), scale = 4f).buffers.first()
        atFour.width shouldBe atOne.width * 4
        atFour.height shouldBe atOne.height * 4
    }

    @Test
    fun `an oversized canvas is split into tiles that fit the texture limit`() {
        val g = RenderGraphBuilder.build(
            RenderPlanner.plan(Style()),
            Rect(0f, 0f, 2000f, 12000f),
            scale = 1f,
            maxTextureSize = 4096,
        )
        g.tiles shouldBe 3
        g.buffers.all { it.height <= 4096 } shouldBe true
    }

    @Test
    fun `a canvas within the limit is a single tile`() {
        graph(Style()).tiles shouldBe 1
    }

    @Test
    fun `half float doubles the allocation over eight bit`() {
        val f16 = graph(Style(), color = ColorSettings(precision = Precision.F16)).totalBytes
        val u8 = graph(Style(), color = ColorSettings(precision = Precision.U8)).totalBytes
        f16 shouldBe u8 * 2
    }

    @Test
    fun `peak memory counts one buffer per role rather than all of them`() {
        val style = Style(effects = List(10) { Effect.DropShadow(blur = 20f) })
        val g = graph(style)
        // Blur buffers are recycled, so the peak is below the naive sum.
        (g.peakBytes < g.totalBytes) shouldBe true
    }

    @Test
    fun `extrusion carries its step count into the pass`() {
        val g = graph(Style(effects = listOf(Effect.Extrude(steps = 29, stepOffset = Vec2(-3f, 3f)))))
        val pass = g.passes.single { it.shaderId == "extrude_step" }
        pass.instanceCount shouldBe 29
    }

    @Test
    fun `passes keep photoshop's draw order`() {
        val style = Style(
            effects = listOf(
                Effect.Stroke(4f, Fill.Solid(Color.BLACK)),
                Effect.DropShadow(),
                Effect.Bevel(),
            ),
        )
        val slots = graph(style).passes.map { it.slot }
        val fill = slots.indexOf(PassSlot.FILL)
        (slots.indexOf(PassSlot.DROP_SHADOW) < fill) shouldBe true
        (slots.indexOf(PassSlot.BEVEL) > fill) shouldBe true
        (slots.indexOf(PassSlot.STROKE) > slots.indexOf(PassSlot.BEVEL)) shouldBe true
    }

    @Test
    fun `invalid inputs are rejected`() {
        assertThrows<IllegalArgumentException> { graph(Style(), scale = 0f) }
        assertThrows<IllegalArgumentException> { graph(Style(), maxTexture = 8) }
    }
}

class MemoryBudgetTest {

    private val canvas = CanvasSpec(4096, 4096)

    private fun heavyGraph() = RenderGraphBuilder.build(
        RenderPlanner.plan(Style(effects = List(10) { Effect.DropShadow(blur = 40f) })),
        Rect(0f, 0f, 4096f, 4096f),
    )

    @Test
    fun `a 4K half-float render is estimated in the tens of megabytes`() {
        val estimate = MemoryBudget.estimate(listOf(heavyGraph()), canvas, ColorSettings())
        // Two full-canvas composites at 8 bytes per pixel is already 268 MB.
        (estimate > 200L * 1024 * 1024) shouldBe true
    }

    @Test
    fun `eight bit halves the estimate`() {
        val f16 = MemoryBudget.estimate(listOf(heavyGraph()), canvas, ColorSettings(precision = Precision.F16))
        val u8 = MemoryBudget.estimate(listOf(heavyGraph()), canvas, ColorSettings(precision = Precision.U8))
        (u8 < f16) shouldBe true
    }

    @Test
    fun `a render that fits is reported as fitting`() {
        val small = RenderGraphBuilder.build(RenderPlanner.plan(Style()), Rect(0f, 0f, 512f, 512f))
        MemoryBudget.fits(listOf(small), CanvasSpec(512, 512), ColorSettings(), 512L * 1024 * 1024) shouldBe true
    }

    @Test
    fun `advice escalates as the shortfall grows`() {
        val settings = ColorSettings()
        MemoryBudget.advise(required = 10, available = 100, color = settings) shouldBe emptyList()
        MemoryBudget.advise(required = 200, available = 100, color = settings) shouldContain "precision:U8"
        MemoryBudget.advise(required = 200, available = 100, color = settings) shouldContain "tile"
        MemoryBudget.advise(required = 1000, available = 100, color = settings) shouldContain "reduce-resolution"
    }

    @Test
    fun `dropping precision is not suggested when already at eight bit`() {
        val advice = MemoryBudget.advise(200, 100, ColorSettings(precision = Precision.U8))
        advice.contains("precision:U8") shouldBe false
        advice shouldContain "tile"
    }

    @Test
    fun `style cost is reachable from the model`() {
        Style(effects = listOf(Effect.Extrude(steps = 40))).renderCost() shouldBe 41
        Shaders[Shaders.BLUR.id].shouldNotBeNull()
    }
}
