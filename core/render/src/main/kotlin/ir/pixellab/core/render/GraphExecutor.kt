package ir.pixellab.core.render

import ir.pixellab.core.model.Affine
import ir.pixellab.core.model.BlendSpace
import ir.pixellab.core.model.ColorSettings
import ir.pixellab.core.model.Effect

/** An opaque handle to a device texture. */
@JvmInline
value class TextureHandle(val id: Int)

/**
 * The graphics operations the executor needs.
 *
 * Kept as an interface so the executor — where the ordering, binding and recycling logic lives —
 * can be tested against a recording fake. Those are exactly the mistakes that are invisible on a
 * screen full of correct-looking pixels: a pass reading last frame's texture, or a buffer leaked
 * every frame until the app dies.
 */
interface GlDevice {
    val maxTextureSize: Int

    fun createTexture(
        width: Int,
        height: Int,
        bytesPerPixel: Int,
        filter: TextureFilter = TextureFilter.LINEAR,
    ): TextureHandle

    /**
     * Replaces a texture's contents with straight (non-premultiplied) ARGB pixels.
     *
     * Straight, not premultiplied: the shaders premultiply where they need to, and a value that
     * arrives already multiplied cannot be un-multiplied once alpha has quantised to eight bits —
     * the error shows up as dark fringing on every soft edge.
     */
    fun uploadArgb(handle: TextureHandle, width: Int, height: Int, pixels: IntArray)

    /** Uploads float samples, [channels] per pixel. Used for curve lookup tables. */
    fun uploadFloats(handle: TextureHandle, width: Int, height: Int, channels: Int, values: FloatArray)

    fun deleteTexture(handle: TextureHandle)

    /** Directs subsequent draws at [handle], or at the screen when null. */
    fun bindTarget(handle: TextureHandle?)

    fun clearTarget()

    /** Selects the program for [shaderId]; returns false when it failed to compile. */
    fun useProgram(shaderId: String): Boolean

    /**
     * Turns premultiplied source-over blending on or off.
     *
     * Passes that build an intermediate — the distance field, a blur half — replace what is there.
     * Passes that draw into the composite have to blend with it. Leaving blending on for the
     * intermediates is the mistake that makes a second render of the same layer look different from
     * the first, because the buffer came back from the pool with the previous layer still in it.
     */
    fun setBlend(enabled: Boolean)

    fun bindInput(samplerName: String, handle: TextureHandle)

    fun setFloat(name: String, value: Float)

    fun setVec2(name: String, x: Float, y: Float)

    fun setVec4(name: String, x: Float, y: Float, z: Float, w: Float)

    fun setInt(name: String, value: Int)

    /** Nine values, column-major, as GLSL stores a `mat3`. */
    fun setMat3(name: String, values: FloatArray)

    fun draw(instances: Int)
}

/**
 * Recycles textures between passes.
 *
 * Allocating per pass is the obvious implementation and the one that kills a phone: a ten-shadow
 * stack would churn twenty full-canvas textures every frame. Buffers are keyed by dimensions and
 * format, so a released one is picked straight back up by the next pass of the same shape.
 */
class TexturePool(private val device: GlDevice) {

    private data class Key(
        val width: Int,
        val height: Int,
        val bytesPerPixel: Int,
        val filter: TextureFilter,
    )

    private val free = HashMap<Key, ArrayDeque<TextureHandle>>()
    private val live = HashMap<TextureHandle, Key>()

    var allocations = 0
        private set

    var reuses = 0
        private set

    val liveCount: Int get() = live.size

    val freeCount: Int get() = free.values.sumOf { it.size }

    fun acquire(spec: BufferSpec): TextureHandle {
        // Filtering is part of the key. Recycling a linear buffer as a distance field is how a
        // pool quietly reintroduces the very defect the filter exists to prevent.
        val key = Key(spec.width, spec.height, spec.bytesPerPixel, spec.filter)
        val recycled = free[key]?.removeLastOrNull()
        if (recycled != null) {
            reuses++
            live[recycled] = key
            return recycled
        }
        val handle = device.createTexture(spec.width, spec.height, spec.bytesPerPixel, spec.filter)
        allocations++
        live[handle] = key
        return handle
    }

    fun release(handle: TextureHandle) {
        val key = live.remove(handle) ?: return
        free.getOrPut(key) { ArrayDeque() }.addLast(handle)
    }

    /** Frees everything; call when the surface is lost or the document closes. */
    fun dispose() {
        (live.keys + free.values.flatten()).forEach(device::deleteTexture)
        live.clear()
        free.clear()
    }
}

/**
 * Supplies the textures an effect owns rather than the graph.
 *
 * A stroke's fill, a bevel's profile curve, an extrusion's near and far paints: none of these are
 * intermediate buffers, so the graph never allocates them, and the shaders would otherwise sample
 * texture zero — which is black, and reads as an effect that simply did not work.
 */
fun interface TextureSource {
    fun textureFor(effect: Effect?, sampler: String): TextureHandle?

    companion object {
        /** Supplies nothing; every effect-owned sampler is then reported as missing. */
        val NONE = TextureSource { _, _ -> null }
    }
}

/** Reports a pass that could not run, so a failure surfaces instead of rendering nothing. */
data class ExecutionError(val shaderId: String, val reason: String)

data class ExecutionResult(
    val output: TextureHandle?,
    val passesRun: Int,
    val errors: List<ExecutionError>,
) {
    val succeeded: Boolean get() = errors.isEmpty()
}

/**
 * Runs a [LayerGraph] against a [GlDevice].
 *
 * The executor owns three things the graph deliberately leaves abstract: which physical texture
 * backs each logical buffer, when a texture can go back to the pool, and how a pass's uniforms are
 * derived from its effect. Keeping them here means the graph stays a plain description that can be
 * inspected and diffed.
 */
class GraphExecutor(
    private val device: GlDevice,
    private val registry: EffectRegistry = builtinEffectRegistry,
    private val textures: TextureSource = TextureSource.NONE,
) {
    private val pool = TexturePool(device)

    val poolAllocations: Int get() = pool.allocations
    val poolReuses: Int get() = pool.reuses

    /**
     * @param layerTexture the layer's own rasterised content, owned by the caller
     * @param backdropTexture what is already composited beneath this layer; required only when
     *   [LayerGraph.readsBackdrop], and also owned by the caller
     */
    /**
     * Maps layer-texture UV to fill UV, set by the caller before each layer.
     *
     * Identity unless the layer's paint is its own pixels, which is the case that needs it: a
     * photograph occupies the layer's box, and the texture around it is bleed for the effects.
     */
    var fillMap: FloatArray = Affine.IDENTITY.values

    fun execute(
        graph: LayerGraph,
        layerTexture: TextureHandle,
        backdropTexture: TextureHandle? = null,
        scale: Float = 1f,
        globalLightAngle: Float = 90f,
        color: ColorSettings = ColorSettings(),
    ): ExecutionResult {
        val errors = ArrayList<ExecutionError>()
        val buffers = HashMap<String, TextureHandle>()

        // The layer's content and the backdrop are supplied; everything else comes from the pool.
        val supplied = HashSet<TextureHandle>()
        for (spec in graph.buffers) {
            val handle = when {
                spec.role == BufferRole.LAYER -> layerTexture
                spec.role == BufferRole.BACKDROP && backdropTexture != null -> backdropTexture
                else -> pool.acquire(spec)
            }
            buffers[spec.id] = handle
            if (handle == layerTexture || handle == backdropTexture) supplied += handle
        }
        if (graph.readsBackdrop && backdropTexture == null) {
            errors += ExecutionError(TARGET_BUFFER, "graph reads the backdrop but none was supplied")
        }

        val context = RenderContext(
            scale = scale,
            globalLightAngle = globalLightAngle,
            linearBlending = color.blendSpace == BlendSpace.LINEAR,
        )

        val roles = graph.buffers.associate { it.id to it.role }
        val sizes = graph.buffers.associateBy { it.id }

        // The composite comes out of the pool with the previous layer still in it. Every other
        // buffer is fully overwritten by its own pass, so only this one needs clearing.
        buffers[TARGET_BUFFER]?.let {
            device.bindTarget(it)
            device.clearTarget()
        }

        var run = 0
        for (pass in graph.passes) {
            val target = buffers[pass.output]
            if (target == null) {
                errors += ExecutionError(pass.shaderId, "unknown output buffer '${pass.output}'")
                continue
            }
            if (!device.useProgram(pass.shaderId)) {
                errors += ExecutionError(pass.shaderId, "program unavailable")
                continue
            }

            device.bindTarget(target)
            // Drawing into the composite blends; building an intermediate replaces.
            device.setBlend(roles[pass.output] == BufferRole.TARGET)

            val shader = Shaders[pass.shaderId]
            val bound = HashSet<String>()
            for (id in pass.inputs) {
                val handle = buffers[id]
                val role = roles[id]
                val name = if (role == null) null else samplerFor(role, shader)
                if (handle == null || name == null) {
                    errors += ExecutionError(pass.shaderId, "cannot bind input '$id'")
                } else {
                    device.bindInput(name, handle)
                    bound += name
                }
            }

            // Whatever the graph did not supply belongs to the effect: its fill, its curve tables,
            // its pattern. An unbound sampler reads black, which looks like an effect that ran and
            // did nothing rather than one that could not run.
            for (sampler in shader?.samplers.orEmpty()) {
                if (sampler in bound) continue
                val supplied = textures.textureFor(pass.effect, sampler)
                if (supplied == null) {
                    errors += ExecutionError(pass.shaderId, "no texture for sampler '$sampler'")
                } else {
                    device.bindInput(sampler, supplied)
                }
            }

            // Every shader in the library derives its neighbour taps from this, so it is set for
            // all of them rather than declared per effect.
            sizes[pass.output]?.let { device.setVec2("uTexelSize", 1f / it.width, 1f / it.height) }
            // Where a layer's paint sits inside its texture. Set for every pass rather than
            // declared per effect, for the same reason as the texel size: the shaders that use it
            // cannot know they were expanded into several passes.
            device.setMat3("uFillMap", fillMap)

            applyUniforms(pass, context)
            device.draw(pass.instanceCount)
            run++
        }

        // Everything except the final target goes back for the next layer to reuse. Textures the
        // caller supplied are never pooled — handing one out as scratch would overwrite live content.
        val output = buffers[TARGET_BUFFER]
        for ((id, handle) in buffers) {
            if (id != TARGET_BUFFER && handle !in supplied) pool.release(handle)
        }

        return ExecutionResult(output, run, errors)
    }

    /**
     * Returns a texture this executor handed out — the composited target — once it has been blended
     * into the document. Without it the pool grows by one full-canvas buffer per layer.
     */
    fun recycle(handle: TextureHandle) = pool.release(handle)

    /** Releases the pool. The caller owns the layer texture and the final target. */
    fun dispose() = pool.dispose()

    /**
     * Picks the sampler a buffer binds to from what it holds, not from its position in the list.
     *
     * Binding by index is the obvious implementation and it is wrong the moment a shader declares
     * its samplers in a different order from the one the graph happens to list its inputs in — a
     * mistake that produces a plausible-looking image rather than an error, because a shadow reading
     * the distance field instead of the blurred alpha still renders something.
     */
    private fun samplerFor(role: BufferRole, shader: ShaderProgram?): String? {
        val preferred = when (role) {
            BufferRole.SDF -> "uSdf"
            BufferRole.SDF_FLOOD -> "uSeed"
            BufferRole.BLUR -> "uBlurred"
            BufferRole.BACKDROP -> "uBackdrop"
            BufferRole.LAYER, BufferRole.TARGET -> "uSource"
        }
        if (shader == null || preferred in shader.samplers) return preferred
        // A pass may read a buffer in its generic role — the second half of a separable blur reads
        // the first half's output as plain source.
        return "uSource".takeIf { shader.samplers.isEmpty() || it in shader.samplers }
    }

    private fun applyUniforms(pass: GraphPass, context: RenderContext) {
        // Uniforms the graph decided — blur axis and radius, flood stride, fill opacity.
        pass.floats.forEach { (name, value) -> device.setFloat(name, value) }
        pass.vectors.forEach { (name, value) ->
            device.setVec2(name, value.getOrElse(0) { 0f }, value.getOrElse(1) { 0f })
        }

        // **Every effect's own strength, sent once, here.**
        //
        // It used to be each module's job to pass its opacity to its shader, and twelve of the
        // fourteen forgot: the slider moved a number in the document that the GPU never read, so
        // every one of those controls silently did nothing. Sending it from the one place that
        // knows about *all* passes means the next effect cannot forget, and the shared
        // `premultiply` in the shader prologue is where it lands.
        //
        // One for a pass with no effect behind it — the fill, the composite, the blur halves the
        // graph synthesises — because those are not effects and must not be faded.
        device.setFloat("uEffectOpacity", pass.effect?.opacity ?: 1f)

        val effect = pass.effect ?: return
        @Suppress("UNCHECKED_CAST")
        val module = registry.moduleFor(effect) as? EffectModule<Effect> ?: return
        val descriptor = module.describe(effect, context)
        // A pass the graph synthesised from this effect runs a different program, so the module's
        // uniforms do not belong to it: sending a shadow's uOffset to the blur would be silently
        // ignored on a good driver and a warning flood on a bad one.
        if (descriptor.shaderId != pass.shaderId) return

        descriptor.floats.forEach { (name, value) -> device.setFloat(name, value) }
        descriptor.vectors.forEach { (name, value) ->
            device.setVec2(name, value.getOrElse(0) { 0f }, value.getOrElse(1) { 0f })
        }
        descriptor.ints.forEach { (name, value) -> device.setInt(name, value) }
    }

    private companion object {
        const val TARGET_BUFFER = "target"
    }
}
