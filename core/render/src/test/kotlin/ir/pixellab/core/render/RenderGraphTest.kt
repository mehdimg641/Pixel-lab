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
    fun `every uniform the graph sends is declared by its shader`() {
        // The graph synthesises passes no module owns — the blur halves, the flood steps — so their
        // uniforms escape the module check above and need their own.
        val style = Style(
            effects = listOf(
                Effect.DropShadow(blur = 30f), Effect.Stroke(6f, Fill.Solid(Color.BLACK)), Effect.Bevel(),
            ),
        )
        val g = RenderGraphBuilder.build(RenderPlanner.plan(style), Rect(0f, 0f, 400f, 200f))
        for (pass in g.passes) {
            val shader = Shaders[pass.shaderId] ?: error("no shader program for '${pass.shaderId}'")
            for (name in pass.floats.keys) {
                if (name !in shader.fragment) error("${shader.id} does not read float '$name'")
            }
            for (name in pass.vectors.keys) {
                if (name !in shader.fragment) error("${shader.id} does not read vec2 '$name'")
            }
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
        g.passes.count { it.shaderId == Shaders.SDF_SEED.id } shouldBe 1
        g.passes.count { it.shaderId == Shaders.SDF_RESOLVE.id } shouldBe 1
        // All four consumers read it.
        val sdfId = g.buffers.single { it.role == BufferRole.SDF }.id
        g.passes.count { sdfId in it.inputs } shouldBe 4
    }

    @Test
    fun `the jump flood halves its stride down to a single pixel`() {
        val g = graph(Style(effects = listOf(Effect.Stroke(64f, Fill.Solid(Color.BLACK)))))
        val strides = g.passes.filter { it.shaderId == Shaders.SDF_FLOOD.id }.map { it.floats.getValue("uStep") }
        // 64 rounds to 64, then halves: a gap in this sequence leaves holes in the field.
        strides shouldBe listOf(64f, 32f, 16f, 8f, 4f, 2f, 1f)
    }

    @Test
    fun `the flood is sized to what the effects reach not to the canvas`() {
        // A 4 px stroke on a 400x200 canvas needs a handful of steps, not the eight the full
        // texture would take. This is the difference between an interactive slider and a stutter.
        val narrow = graph(Style(effects = listOf(Effect.Stroke(4f, Fill.Solid(Color.BLACK)))))
        val wide = graph(Style(effects = listOf(Effect.Stroke(300f, Fill.Solid(Color.BLACK)))))
        val steps = { g: LayerGraph -> g.passes.count { it.shaderId == Shaders.SDF_FLOOD.id } }
        (steps(narrow) < steps(wide)) shouldBe true
    }

    @Test
    fun `the flood ping-pongs between two buffers and resolves out of the last one written`() {
        val g = graph(Style(effects = listOf(Effect.Stroke(4f, Fill.Solid(Color.BLACK)))))
        g.buffers.count { it.role == BufferRole.SDF_FLOOD } shouldBe 2
        val floods = g.passes.filter { it.shaderId == Shaders.SDF_FLOOD.id }
        // Each step reads what the previous one wrote; reading a stale buffer is invisible on
        // screen and leaves the field one iteration short.
        floods.zipWithNext { a, b -> b.inputs.single() shouldBe a.output }
        val resolve = g.passes.single { it.shaderId == Shaders.SDF_RESOLVE.id }
        resolve.inputs.last() shouldBe floods.last().output
    }

    @Test
    fun `the distance field needs no float target, and says so`() {
        // **This test used to assert the opposite** — that the flood buffers stay wider than eight
        // bits — and that requirement is what broke the application on a real phone. A driver that
        // cannot render into a half-float target got eight bits anyway, every value clamped into
        // 0..1, and the stroke shader read full coverage across the whole texture: a solid black
        // rectangle where an outline belonged, on the very first document a user opens.
        //
        // The field is now packed into sixteen bits across a channel pair, which is exact on an
        // eight-bit target, so it asks for nothing a driver can refuse.
        val g = graph(
            Style(effects = listOf(Effect.Stroke(4f, Fill.Solid(Color.BLACK)))),
            color = ColorSettings(precision = Precision.U8),
        )
        g.buffers.filter { it.role == BufferRole.SDF_FLOOD || it.role == BufferRole.SDF }
            .all { it.bytesPerPixel == 4 } shouldBe true
    }

    @Test
    fun `the packed field is sampled without filtering`() {
        // The packing is only exact because neighbouring texels are never blended. Interpolating a
        // high byte against its neighbour's produces a number that means nothing — an outline made
        // of noise — so this is a hard requirement of the encoding rather than an optimisation.
        val g = graph(Style(effects = listOf(Effect.Stroke(4f, Fill.Solid(Color.BLACK)))))
        g.buffers.filter { it.role == BufferRole.SDF_FLOOD || it.role == BufferRole.SDF }
            .all { it.filter == TextureFilter.NEAREST } shouldBe true
    }

    @Test
    fun `every pass that touches the field agrees on the scale it is stored against`() {
        // The writer encodes against a range and the reader decodes against one. If they ever
        // disagreed nothing would fail — every outline would simply come out the wrong width, which
        // is the kind of defect that gets explained away as a bad default.
        val g = graph(
            Style(effects = listOf(Effect.Stroke(9f, Fill.Solid(Color.BLACK)), Effect.Bevel())),
        )
        val ranges = g.passes.mapNotNull { it.floats["uSdfRange"] }.distinct()
        ranges.size shouldBe 1
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
    fun `peak memory does not grow with the number of shadows`() {
        val one = graph(Style(effects = listOf(Effect.DropShadow(blur = 20f))))
        val ten = graph(Style(effects = List(10) { Effect.DropShadow(blur = 20f) }))
        // Each shadow adds passes, not buffers — the whole point of the shared ping-pong pair.
        ten.buffers.size shouldBe one.buffers.size
        ten.peakBytes shouldBe one.peakBytes
    }

    @Test
    fun `peak memory is the sum of the buffers because they are all live at once`() {
        // Under-reporting here is the comfortable answer and the one that crashes a 4K export.
        val g = graph(Style(effects = listOf(Effect.DropShadow(blur = 20f), Effect.Bevel())))
        g.peakBytes shouldBe g.buffers.sumOf { it.bytes }
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

/**
 * How the distance field is sampled.
 *
 * A whole class of defect lived in a texture parameter nobody had reason to look at. Jump flooding
 * stores the *offset to the nearest seed* in each texel and reads it back at integer strides;
 * interpolating between two of those produces a vector pointing at no seed at all. And filtering a
 * half-float texture needs `OES_texture_half_float_linear`, a different extension from the one this
 * engine checks for — a driver with one and not the other returns undefined values, which collapses
 * the field to a constant. With a constant field the stroke shader's coverage is `1.0` everywhere
 * outside the letters: an opaque rectangle over the artwork, which is what shipped.
 *
 * None of that is visible from a plan, a pass list, or any picture a build machine can render. It is
 * visible from one enum, so that is what this pins.
 */
class DistanceFieldSamplingTest {

    private fun graphWithOutlines() = RenderGraphBuilder.build(
        RenderPlanner.plan(
            Style(
                effects = listOf(
                    Effect.Stroke(6f, Fill.Solid(Color.BLACK)),
                    Effect.DropShadow(blur = 20f),
                    Effect.Bevel(),
                ),
            ),
        ),
        Rect(0f, 0f, 400f, 200f),
    )

    @Test
    fun `a distance field is read at exact texels`() {
        val fields = graphWithOutlines().buffers.filter {
            it.role == BufferRole.SDF || it.role == BufferRole.SDF_FLOOD
        }
        fields.isNotEmpty() shouldBe true
        fields.forEach { it.filter shouldBe TextureFilter.NEAREST }
    }

    @Test
    fun `everything else still interpolates`() {
        // Not a blanket switch to nearest: a blur and the layer itself are sampled at fractional
        // positions on purpose, and reading those at the nearest texel is a visible staircase.
        graphWithOutlines().buffers
            .filter { it.role != BufferRole.SDF && it.role != BufferRole.SDF_FLOOD }
            .forEach { it.filter shouldBe TextureFilter.LINEAR }
    }

    @Test
    fun `the buffer pool cannot hand a filtered texture back as a field`() {
        // The pool recycles by shape. Before the filter was part of its key, a blur buffer of the
        // same size and depth could be handed straight back as the distance field — which
        // reintroduces the defect at the one place nobody would look for it.
        val device = FakeGlDevice()
        val pool = TexturePool(device)

        pool.release(pool.acquire(BufferSpec("blur", BufferRole.BLUR, 64, 64, 8)))
        pool.acquire(BufferSpec("sdf", BufferRole.SDF, 64, 64, 8))

        device.filters shouldBe listOf(TextureFilter.LINEAR, TextureFilter.NEAREST)
    }
}
