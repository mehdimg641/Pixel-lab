package ir.pixellab.core.render

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

/**
 * A [GlDevice] that records instead of drawing.
 *
 * The executor's job is entirely about order and wiring — which texture a pass reads, which sampler
 * it lands on, when a buffer may go back to the pool. Every mistake in that list produces a picture
 * rather than an error: a shadow sampling the distance field still renders, it just renders wrong.
 * Recording the calls is what makes those mistakes assertable without a GPU.
 */
private class FakeGlDevice(override val maxTextureSize: Int = 4096) : GlDevice {

    class Pass(val shaderId: String) {
        var target: TextureHandle? = null
        var instances = 0
        var blended = false
        val inputs = LinkedHashMap<String, TextureHandle>()
        val floats = LinkedHashMap<String, Float>()
        val vectors = LinkedHashMap<String, Pair<Float, Float>>()
        val ints = LinkedHashMap<String, Int>()
        val matrices = LinkedHashMap<String, FloatArray>()
    }

    val passes = ArrayList<Pass>()
    var created = 0
        private set
    var deleted = 0
        private set

    /** Handles this device has handed out and not yet seen deleted. */
    val issued = LinkedHashSet<TextureHandle>()

    val uploads = ArrayList<TextureHandle>()

    /** Programs that fail to compile, so the failure path can be exercised. */
    val brokenPrograms = HashSet<String>()

    private var nextId = 1
    private var current: Pass? = null

    override fun createTexture(width: Int, height: Int, bytesPerPixel: Int): TextureHandle {
        created++
        return TextureHandle(nextId++).also { issued += it }
    }

    override fun uploadArgb(handle: TextureHandle, width: Int, height: Int, pixels: IntArray) {
        uploads += handle
    }

    override fun uploadFloats(
        handle: TextureHandle,
        width: Int,
        height: Int,
        channels: Int,
        values: FloatArray,
    ) {
        uploads += handle
    }

    override fun deleteTexture(handle: TextureHandle) {
        deleted++
        issued -= handle
    }

    /** Targets that were cleared, in order, including the clear that happens before any program. */
    val cleared = ArrayList<TextureHandle?>()

    private var boundTarget: TextureHandle? = null

    override fun bindTarget(handle: TextureHandle?) {
        boundTarget = handle
        current?.target = handle
    }

    override fun clearTarget() {
        cleared += boundTarget
    }

    override fun setBlend(enabled: Boolean) {
        current?.blended = enabled
    }

    override fun useProgram(shaderId: String): Boolean {
        if (shaderId in brokenPrograms) return false
        current = Pass(shaderId).also { passes += it }
        return true
    }

    override fun bindInput(samplerName: String, handle: TextureHandle) {
        current?.inputs?.put(samplerName, handle)
    }

    override fun setFloat(name: String, value: Float) {
        current?.floats?.put(name, value)
    }

    override fun setVec2(name: String, x: Float, y: Float) {
        current?.vectors?.put(name, x to y)
    }

    override fun setVec4(name: String, x: Float, y: Float, z: Float, w: Float) {
        current?.vectors?.put(name, x to y)
    }

    override fun setInt(name: String, value: Int) {
        current?.ints?.put(name, value)
    }

    override fun setMat3(name: String, values: FloatArray) {
        current?.matrices?.put(name, values.copyOf())
    }

    override fun draw(instances: Int) {
        current?.instances = instances
    }
}

class TexturePoolTest {

    private fun spec(id: String, w: Int = 64, h: Int = 64, bpp: Int = 8) =
        BufferSpec(id, BufferRole.BLUR, w, h, bpp)

    @Test
    fun `a released buffer is handed straight back to a request of the same shape`() {
        val device = FakeGlDevice()
        val pool = TexturePool(device)
        val first = pool.acquire(spec("a"))
        pool.release(first)
        pool.acquire(spec("b")) shouldBe first
        pool.allocations shouldBe 1
        pool.reuses shouldBe 1
    }

    @Test
    fun `a buffer of a different shape is not reused`() {
        val pool = TexturePool(FakeGlDevice())
        pool.release(pool.acquire(spec("a", w = 64)))
        pool.acquire(spec("b", w = 128))
        pool.allocations shouldBe 2
        pool.reuses shouldBe 0
    }

    @Test
    fun `precision is part of the identity so a float buffer is never handed out as eight bit`() {
        // Reusing across formats is the kind of saving that silently truncates a distance field.
        val pool = TexturePool(FakeGlDevice())
        pool.release(pool.acquire(spec("a", bpp = 8)))
        pool.acquire(spec("b", bpp = 4))
        pool.allocations shouldBe 2
    }

    @Test
    fun `releasing a handle the pool never issued is ignored`() {
        val pool = TexturePool(FakeGlDevice())
        pool.release(TextureHandle(999))
        pool.freeCount shouldBe 0
    }

    @Test
    fun `disposing frees both the live and the free lists exactly once`() {
        val device = FakeGlDevice()
        val pool = TexturePool(device)
        val a = pool.acquire(spec("a"))
        pool.acquire(spec("b", w = 128))
        pool.release(a)
        pool.dispose()
        device.deleted shouldBe 2
        pool.liveCount shouldBe 0
        pool.freeCount shouldBe 0
    }
}

/**
 * Hands out one distinct texture per sampler name, standing in for the fills and curve tables the
 * host uploads. Distinct so a test can tell which sampler a pass actually received.
 */
private class FakeTextureSource : TextureSource {
    val issued = LinkedHashMap<String, TextureHandle>()
    val requested = ArrayList<Pair<String, String?>>()

    override fun textureFor(effect: Effect?, sampler: String): TextureHandle {
        requested += sampler to effect?.let(EffectRegistry::idOf)
        return issued.getOrPut(sampler) { TextureHandle(900 + issued.size) }
    }
}

class GraphExecutorTest {

    private val bounds = Rect(0f, 0f, 256f, 128f)

    private fun run(
        style: Style,
        device: FakeGlDevice = FakeGlDevice(),
        source: TextureSource = FakeTextureSource(),
        executor: GraphExecutor = GraphExecutor(device, textures = source),
    ): Triple<FakeGlDevice, GraphExecutor, ExecutionResult> {
        val graph = RenderGraphBuilder.build(RenderPlanner.plan(style), bounds)
        return Triple(device, executor, executor.execute(graph, TextureHandle(0)))
    }

    @Test
    fun `a plain layer runs its fill pass and reports no errors`() {
        val (device, _, result) = run(Style())
        result.succeeded shouldBe true
        result.passesRun shouldBe 1
        device.passes.single().shaderId shouldBe Shaders.FILL.id
        result.output shouldBe device.passes.single().target
    }

    @Test
    fun `fill opacity reaches the shader`() {
        val (device, _, _) = run(Style(fillOpacity = 0f))
        // Zero fill with effects intact is how hollow text is made; a dropped uniform makes it solid.
        device.passes.single().floats["uFillOpacity"] shouldBe 0f
    }

    @Test
    fun `the distance field is finished before anything reads it`() {
        val (device, _, _) = run(Style(effects = listOf(Effect.Stroke(6f, Fill.Solid(Color.BLACK)))))
        val ids = device.passes.map { it.shaderId }
        val resolve = ids.indexOf(Shaders.SDF_RESOLVE.id)
        (ids.indexOf(Shaders.SDF_SEED.id) < resolve) shouldBe true
        (resolve < ids.indexOf(Shaders.STROKE.id)) shouldBe true
    }

    @Test
    fun `the stroke reads the resolved field and not a flood buffer`() {
        val (device, _, _) = run(Style(effects = listOf(Effect.Stroke(6f, Fill.Solid(Color.BLACK)))))
        val resolve = device.passes.single { it.shaderId == Shaders.SDF_RESOLVE.id }
        val stroke = device.passes.single { it.shaderId == Shaders.STROKE.id }
        // Binding by list position rather than by role would land the flood's raw offsets here,
        // which still renders — as a ring at the wrong radius.
        stroke.inputs["uSdf"] shouldBe resolve.target
    }

    @Test
    fun `each flood step reads what the one before it wrote`() {
        val (device, _, _) = run(Style(effects = listOf(Effect.Stroke(6f, Fill.Solid(Color.BLACK)))))
        val floods = device.passes.filter { it.shaderId == Shaders.SDF_FLOOD.id }
        (floods.size >= 2) shouldBe true
        floods.zipWithNext { a, b -> b.inputs.getValue("uSeed") shouldBe a.target }
        // Halving strides; a repeated one means a pass read a stale buffer.
        floods.map { it.floats.getValue("uStep") }.zipWithNext { a, b -> b shouldBe a / 2f }
    }

    @Test
    fun `a shadow reads the blurred alpha it just produced`() {
        val (device, _, _) = run(Style(effects = listOf(Effect.DropShadow(blur = 12f))))
        val blurs = device.passes.filter { it.shaderId == Shaders.BLUR.id }
        blurs.size shouldBe 2
        val shadow = device.passes.single { it.shaderId == Shaders.SHADOW.id }
        shadow.inputs["uBlurred"] shouldBe blurs.last().target
        // The second half of a separable blur reads the first half as a plain source.
        blurs.last().inputs["uSource"] shouldBe blurs.first().target
    }

    @Test
    fun `the two halves of a separable blur run along different axes`() {
        val (device, _, _) = run(Style(effects = listOf(Effect.DropShadow(blur = 12f))))
        val blurs = device.passes.filter { it.shaderId == Shaders.BLUR.id }
        blurs[0].vectors["uDirection"] shouldBe (1f to 0f)
        blurs[1].vectors["uDirection"] shouldBe (0f to 1f)
        // Both halves need the same radius or the kernel is not separable at all.
        blurs[0].floats["uRadius"] shouldBe 12f
        blurs[1].floats["uRadius"] shouldBe 12f
    }

    @Test
    fun `a module's uniforms do not leak into the passes the graph synthesised for it`() {
        val (device, _, _) = run(Style(effects = listOf(Effect.DropShadow(blur = 12f, distance = 20f))))
        val blur = device.passes.first { it.shaderId == Shaders.BLUR.id }
        blur.vectors.containsKey("uOffset") shouldBe false
        device.passes.single { it.shaderId == Shaders.SHADOW.id }.vectors.containsKey("uOffset") shouldBe true
    }

    @Test
    fun `texel size is set from the buffer being written not from the canvas`() {
        val (device, _, _) = run(Style())
        // 256 wide plus no bleed; every neighbour tap in the library depends on this.
        device.passes.single().vectors.getValue("uTexelSize").first shouldBe 1f / 256f
    }

    @Test
    fun `the extrusion step count reaches the draw call`() {
        val (device, _, _) = run(Style(effects = listOf(Effect.Extrude(steps = 29, stepOffset = Vec2(-3f, 3f)))))
        device.passes.single { it.shaderId == Shaders.EXTRUDE_STEP.id }.instances shouldBe 29
    }

    @Test
    fun `a program that failed to compile is reported rather than skipped in silence`() {
        val device = FakeGlDevice()
        device.brokenPrograms += Shaders.STROKE.id
        val (_, _, result) = run(Style(effects = listOf(Effect.Stroke(6f, Fill.Solid(Color.BLACK)))), device)
        result.succeeded shouldBe false
        result.errors.single().shaderId shouldBe Shaders.STROKE.id
        // The rest of the style still renders; one broken effect must not blank the layer.
        device.passes.any { it.shaderId == Shaders.FILL.id } shouldBe true
    }

    @Test
    fun `a ten shadow stack allocates the same buffers as a single shadow`() {
        val (one, _, _) = run(Style(effects = listOf(Effect.DropShadow(blur = 12f))))
        val (ten, _, _) = run(Style(effects = List(10) { Effect.DropShadow(blur = it * 4f) }))
        ten.created shouldBe one.created
    }

    @Test
    fun `rendering many layers reuses buffers instead of allocating per layer`() {
        val device = FakeGlDevice()
        val executor = GraphExecutor(device, textures = FakeTextureSource())
        val graph = RenderGraphBuilder.build(
            RenderPlanner.plan(Style(effects = listOf(Effect.DropShadow(blur = 12f)))),
            bounds,
        )
        repeat(20) { executor.recycle(executor.execute(graph, TextureHandle(0)).output!!) }
        // A document with twenty styled layers must not churn twenty sets of full-canvas textures.
        executor.poolAllocations shouldBe device.created
        (executor.poolReuses > executor.poolAllocations * 10) shouldBe true
    }

    @Test
    fun `the caller's layer texture is never taken into the pool`() {
        val device = FakeGlDevice()
        val executor = GraphExecutor(device, textures = FakeTextureSource())
        val layer = TextureHandle(0)
        val graph = RenderGraphBuilder.build(RenderPlanner.plan(Style()), bounds)
        executor.execute(graph, layer)
        // Only the target is pooled; the layer buffer is supplied, not allocated. Pooling it would
        // hand the caller's own content to a later pass as scratch space.
        executor.poolAllocations shouldBe 1
        executor.execute(graph, layer)
        executor.dispose()
        device.issued.contains(layer) shouldBe false
        device.deleted shouldBe device.created
    }

    @Test
    fun `the composite is cleared before the first pass and the intermediates are not`() {
        val (device, _, result) = run(Style(effects = listOf(Effect.DropShadow(blur = 12f))))
        // It comes out of the pool holding the previous layer; leaving it is how a shadow from one
        // layer ends up ghosted behind another.
        device.cleared shouldBe listOf(result.output)
    }

    @Test
    fun `passes blend into the composite and replace the intermediates`() {
        val (device, _, _) = run(Style(effects = listOf(Effect.DropShadow(blur = 12f))))
        device.passes.filter { it.shaderId == Shaders.BLUR.id }.all { it.blended } shouldBe false
        device.passes.single { it.shaderId == Shaders.SHADOW.id }.blended shouldBe true
        device.passes.single { it.shaderId == Shaders.FILL.id }.blended shouldBe true
    }

    @Test
    fun `a glass layer without a backdrop is reported rather than rendered wrong`() {
        val graph = RenderGraphBuilder.build(RenderPlanner.plan(Style(effects = listOf(Effect.BackdropBlur()))), bounds)
        val missing = GraphExecutor(FakeGlDevice(), textures = FakeTextureSource())
            .execute(graph, TextureHandle(0))
        missing.succeeded shouldBe false

        val device = FakeGlDevice()
        val supplied = GraphExecutor(device, textures = FakeTextureSource())
            .execute(graph, TextureHandle(0), backdropTexture = TextureHandle(77))
        supplied.succeeded shouldBe true
        device.passes.single { it.shaderId == Shaders.BACKDROP_BLUR.id }
            .inputs["uBackdrop"] shouldBe TextureHandle(77)
    }

    @Test
    fun `a supplied backdrop is never taken into the pool`() {
        val device = FakeGlDevice()
        val executor = GraphExecutor(device, textures = FakeTextureSource())
        val graph = RenderGraphBuilder.build(RenderPlanner.plan(Style(effects = listOf(Effect.BackdropBlur()))), bounds)
        val backdrop = TextureHandle(77)
        executor.execute(graph, TextureHandle(0), backdropTexture = backdrop)
        // Only the composite is pooled; recycling the backdrop would scribble over the document
        // beneath this layer.
        executor.poolAllocations shouldBe 1
    }

    @Test
    fun `an effect's own textures are bound alongside the graph's buffers`() {
        val device = FakeGlDevice()
        val source = FakeTextureSource()
        run(Style(effects = listOf(Effect.Stroke(6f, Fill.Solid(Color.BLACK)))), device, source)

        val stroke = device.passes.single { it.shaderId == Shaders.STROKE.id }
        // uSource and uSdf come from the graph; uFill is the stroke's own paint and nothing in the
        // graph knows about it.
        stroke.inputs.keys shouldBe setOf("uSource", "uSdf", "uFill")
        stroke.inputs["uFill"] shouldBe source.issued["uFill"]
        source.requested.contains("uFill" to "stroke") shouldBe true
    }

    @Test
    fun `a sampler with no texture is reported rather than left reading black`() {
        val device = FakeGlDevice()
        val result = GraphExecutor(device, textures = TextureSource.NONE)
            .execute(
                RenderGraphBuilder.build(
                    RenderPlanner.plan(Style(effects = listOf(Effect.Stroke(6f, Fill.Solid(Color.BLACK))))),
                    bounds,
                ),
                TextureHandle(0),
            )
        // Texture zero is black, so an unbound fill renders as an effect that ran and did nothing —
        // indistinguishable from a badly chosen colour unless it is reported.
        result.succeeded shouldBe false
        result.errors.any { it.reason.contains("uFill") } shouldBe true
    }

    @Test
    fun `a bevel receives both of its curve tables`() {
        val device = FakeGlDevice()
        val source = FakeTextureSource()
        run(Style(effects = listOf(Effect.Bevel())), device, source)
        val bevel = device.passes.single { it.shaderId == Shaders.BEVEL.id }
        // The profile shapes the shoulder and the gloss contour is what turns a plain highlight
        // into metal; a bevel missing either is a different effect.
        bevel.inputs.keys shouldBe setOf("uSource", "uSdf", "uProfile", "uGloss")
    }

    @Test
    fun `an extrusion receives its near and far paints`() {
        val device = FakeGlDevice()
        val source = FakeTextureSource()
        run(Style(effects = listOf(Effect.Extrude(steps = 8))), device, source)
        val extrude = device.passes.single { it.shaderId == Shaders.EXTRUDE_STEP.id }
        extrude.inputs.keys shouldBe setOf("uSource", "uNearFill", "uFarFill")
    }

    @Test
    fun `the graph's buffers win over the source for a sampler both could fill`() {
        val device = FakeGlDevice()
        val source = FakeTextureSource()
        run(Style(effects = listOf(Effect.DropShadow(blur = 10f))), device, source)
        val shadow = device.passes.single { it.shaderId == Shaders.SHADOW.id }
        val blurs = device.passes.filter { it.shaderId == Shaders.BLUR.id }
        // uBlurred is a real intermediate; asking the source for it would hand the shadow a static
        // texture and the blur radius would stop mattering.
        shadow.inputs["uBlurred"] shouldBe blurs.last().target
        source.requested.none { it.first == "uBlurred" } shouldBe true
    }

    @Test
    fun `an unknown output buffer is an error rather than a crash`() {
        val device = FakeGlDevice()
        val graph = LayerGraph(
            buffers = listOf(BufferSpec("layer", BufferRole.LAYER, 8, 8, 4)),
            passes = listOf(GraphPass(PassSlot.FILL, Shaders.FILL.id, listOf("layer"), "missing")),
            textureBounds = bounds,
            readsBackdrop = false,
            tiles = 1,
        )
        val result = GraphExecutor(device, textures = FakeTextureSource()).execute(graph, TextureHandle(0))
        result.errors.single().reason.contains("missing") shouldBe true
        result.passesRun shouldBe 0
    }

    @Test
    fun `no pass in a full style binds a sampler its shader does not declare`() {
        val style = Style(
            effects = listOf(
                Effect.DropShadow(blur = 10f), Effect.OuterGlow(blur = 8f), Effect.Bevel(),
                Effect.Stroke(4f, Fill.Solid(Color.BLACK)), Effect.Satin(), Effect.InnerShadow(),
                Effect.EdgeRoughen(),
            ),
        )
        val (device, _, result) = run(style)
        result.succeeded shouldBe true
        for (pass in device.passes) {
            val shader = Shaders.getValue(pass.shaderId)
            for (sampler in pass.inputs.keys) {
                if (sampler !in shader.samplers) error("${shader.id} has no sampler '$sampler'")
            }
        }
    }
}

private fun Shaders.getValue(id: String): ShaderProgram = this[id] ?: error("no shader '$id'")
