package ir.pixellab.engine.android

import android.opengl.GLES30
import android.util.Log
import ir.pixellab.core.render.GlDevice
import ir.pixellab.core.render.ShaderProgram
import ir.pixellab.core.render.Shaders
import ir.pixellab.core.render.TextureFilter
import ir.pixellab.core.render.TextureHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The OpenGL ES 3.0 implementation of [GlDevice].
 *
 * Everything about *what* to draw is decided in `core:render`, which is why that logic is testable
 * without a GPU. What is left here is the part that genuinely needs a driver: compiling programs,
 * allocating textures, attaching framebuffers and pushing uniforms.
 *
 * Three things are deliberate.
 *
 * **There is no vertex buffer.** Every pass covers the whole target, so the vertex stage builds a
 * single oversized triangle from `gl_VertexID`. A quad from a buffer is the obvious implementation
 * and it costs a buffer, an attribute setup and a seam of double-shaded pixels down the diagonal.
 *
 * **Uniform locations are cached per program.** `glGetUniformLocation` is a string lookup into the
 * driver, and a ten-shadow style issues a few hundred of them per frame.
 *
 * **A failed compile is reported, not thrown.** One broken effect must leave the rest of the layer
 * rendering, because a style loaded from a project file may reference an effect this build cannot
 * compile on this driver.
 */
class AndroidGlDevice(private val context: GlContext) : GlDevice {

    override val maxTextureSize: Int get() = context.maxTextureSize

    private class Program(val id: Int) {
        val uniforms = HashMap<String, Int>()
        var nextTextureUnit = 0
    }

    private val programs = HashMap<String, Program?>()
    private val textureSizes = HashMap<Int, Pair<Int, Int>>()
    private var vertexArray = 0
    private var framebuffer = 0
    private var current: Program? = null
    private var blending = false

    init {
        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        vertexArray = arrays[0]
        val buffers = IntArray(1)
        GLES30.glGenFramebuffers(1, buffers, 0)
        framebuffer = buffers[0]
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
        )
    }

    override fun createTexture(
        width: Int,
        height: Int,
        bytesPerPixel: Int,
        filter: TextureFilter,
    ): TextureHandle {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat(bytesPerPixel), width, height)
        // Clamped always: every pass samples with an offset, and repeating would wrap a shadow
        // around to the opposite edge. Filtering is the caller's, because a distance field has to
        // be read at exact texels — see `BufferSpec.filter`.
        val mode = if (filter == TextureFilter.NEAREST) GLES30.GL_NEAREST else GLES30.GL_LINEAR
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, mode)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, mode)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        textureSizes[id] = width to height
        return TextureHandle(id)
    }

    /**
     * Picks a sized internal format, degrading when the driver cannot render into it.
     *
     * Half float is what keeps a wide gradient from banding and what lets the distance field hold
     * offsets larger than 255.
     *
     * **Eight bits is not a graceful degradation for a distance field, and the ladder now tries
     * full float before reaching it.** The field stores a signed distance and a raw pixel offset
     * to the nearest edge, with `8192` standing for "no seed found"; on `RGBA8` every one of those
     * clamps to 1.0, so the field collapses to a binary mask. Fed that, the stroke shader's
     * coverage comes out `1.0` at every pixel outside the letters — an opaque rectangle painted
     * across the whole layer, which is what a user saw behind their text. `RGBA32F` costs twice
     * the memory and is renderable wherever `EXT_color_buffer_float` is, which is most places that
     * lack the half-float target.
     *
     * If neither is available the warning below is the only warning there is, and the fix it points
     * at — normalising the field into 0..1 so eight bits holds a coarse but usable version — is not
     * written yet. Saying so here is better than a fallback that silently ruins the picture.
     */
    private fun internalFormat(bytesPerPixel: Int): Int = when {
        bytesPerPixel >= 16 -> GLES30.GL_RGBA32F
        bytesPerPixel >= 8 && context.supportsHalfFloatTargets -> GLES30.GL_RGBA16F
        bytesPerPixel >= 8 && context.supportsFloatTargets -> {
            Log.w(TAG, "no renderable half float on ${context.renderer}; using 32 bit float")
            GLES30.GL_RGBA32F
        }
        bytesPerPixel >= 8 -> {
            Log.w(
                TAG,
                "no renderable float target on ${context.renderer}; distance fields will be " +
                    "wrong and outline effects will draw as filled rectangles",
            )
            GLES30.GL_RGBA8
        }
        else -> GLES30.GL_RGBA8
    }

    /**
     * Uploads ARGB pixels, converting to the RGBA byte order GL expects.
     *
     * Android packs a pixel as ARGB in an `Int`; `GL_RGBA` with `GL_UNSIGNED_BYTE` reads four
     * consecutive bytes as R, G, B, A. On a little-endian device those are B, G, R, A — so handing
     * the array over untouched swaps red and blue, which is the classic "everything is teal" bug.
     */
    override fun uploadArgb(handle: TextureHandle, width: Int, height: Int, pixels: IntArray) {
        val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            bytes.put(((pixel shr 16) and 0xFF).toByte())
            bytes.put(((pixel shr 8) and 0xFF).toByte())
            bytes.put((pixel and 0xFF).toByte())
            bytes.put(((pixel ushr 24) and 0xFF).toByte())
        }
        bytes.rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle.id)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes,
        )
    }

    override fun uploadFloats(
        handle: TextureHandle,
        width: Int,
        height: Int,
        channels: Int,
        values: FloatArray,
    ) {
        val buffer = ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(values)
        buffer.rewind()
        val format = when (channels) {
            1 -> GLES30.GL_RED
            2 -> GLES30.GL_RG
            3 -> GLES30.GL_RGB
            else -> GLES30.GL_RGBA
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle.id)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height, format, GLES30.GL_FLOAT, buffer,
        )
    }

    override fun deleteTexture(handle: TextureHandle) {
        textureSizes.remove(handle.id)
        GLES30.glDeleteTextures(1, intArrayOf(handle.id), 0)
    }

    /**
     * Binds a texture to draw into, or the window when null.
     *
     * **The window case has to restore the viewport, and for a long time it did not.** Every pass
     * into a texture sets the viewport to that texture's size, and the viewport is global state:
     * the final present to the window inherited whatever the last offscreen pass had left. On a
     * 1080×1920 document that meant the whole editor was drawn into a 1080×1920 rectangle anchored
     * at the bottom-left corner of the screen, with the rest of the window never written at all —
     * so the artwork sat low and off-centre and the untouched strip stayed black.
     *
     * It is the kind of bug that only shows on a device: every offscreen test passes, because a
     * test never presents to a window.
     */
    override fun bindTarget(handle: TextureHandle?) {
        if (handle == null) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            val width = context.width
            val height = context.height
            if (width > 0 && height > 0) GLES30.glViewport(0, 0, width, height)
            return
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, handle.id, 0,
        )
        textureSizes[handle.id]?.let { (w, h) -> GLES30.glViewport(0, 0, w, h) }
    }

    override fun clearTarget() {
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    override fun setBlend(enabled: Boolean) {
        if (enabled == blending) return
        if (enabled) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
        blending = enabled
    }

    override fun useProgram(shaderId: String): Boolean {
        // Cached including the failures, so a shader that cannot compile is reported once rather
        // than retried every frame.
        val program = programs.getOrPut(shaderId) {
            Shaders[shaderId]?.let { compile(it) }
        } ?: return false
        current = program
        program.nextTextureUnit = 0
        GLES30.glUseProgram(program.id)
        GLES30.glBindVertexArray(vertexArray)
        return true
    }

    override fun bindInput(samplerName: String, handle: TextureHandle) {
        val program = current ?: return
        val location = locate(program, samplerName)
        if (location < 0) return
        val unit = program.nextTextureUnit++
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle.id)
        GLES30.glUniform1i(location, unit)
    }

    override fun setFloat(name: String, value: Float) {
        current?.let { GLES30.glUniform1f(locate(it, name), value) }
    }

    override fun setVec2(name: String, x: Float, y: Float) {
        current?.let { GLES30.glUniform2f(locate(it, name), x, y) }
    }

    override fun setInt(name: String, value: Int) {
        current?.let { GLES30.glUniform1i(locate(it, name), value) }
    }

    override fun setVec4(name: String, x: Float, y: Float, z: Float, w: Float) {
        current?.let { GLES30.glUniform4f(locate(it, name), x, y, z, w) }
    }

    override fun setMat3(name: String, values: FloatArray) {
        current?.let { GLES30.glUniformMatrix3fv(locate(it, name), 1, false, values, 0) }
    }

    override fun draw(instances: Int) {
        // Three vertices, no buffer: the vertex shader derives the triangle from gl_VertexID.
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 3, instances.coerceAtLeast(1))
    }

    /** Reads a target back to CPU memory; used by export and by the pixel-accuracy tests. */
    fun readPixels(handle: TextureHandle, width: Int, height: Int): ByteBuffer {
        bindTarget(handle)
        val out = ByteBuffer.allocateDirect(width * height * 4)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out)
        out.rewind()
        return out
    }

    fun dispose() {
        programs.values.filterNotNull().forEach { GLES30.glDeleteProgram(it.id) }
        programs.clear()
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
        framebuffer = 0
        vertexArray = 0
    }

    private fun locate(program: Program, name: String): Int =
        program.uniforms.getOrPut(name) { GLES30.glGetUniformLocation(program.id, name) }

    private fun compile(shader: ShaderProgram): Program? {
        val vertex = compileStage(GLES30.GL_VERTEX_SHADER, VERTEX_SOURCE, "vertex") ?: return null
        val fragment = compileStage(GLES30.GL_FRAGMENT_SHADER, shader.fragment, shader.id)
        if (fragment == null) {
            GLES30.glDeleteShader(vertex)
            return null
        }
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vertex)
        GLES30.glAttachShader(id, fragment)
        GLES30.glLinkProgram(id)
        // The shaders are attached to the program, which keeps them alive until it links.
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)

        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "link failed for '${shader.id}': ${GLES30.glGetProgramInfoLog(id)}")
            GLES30.glDeleteProgram(id)
            return null
        }
        return Program(id)
    }

    private fun compileStage(type: Int, source: String, label: String): Int? {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, source)
        GLES30.glCompileShader(id)
        val status = IntArray(1)
        GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "compile failed for '$label': ${GLES30.glGetShaderInfoLog(id)}")
            GLES30.glDeleteShader(id)
            return null
        }
        return id
    }

    companion object {
        private const val TAG = "PixelLabGL"

        /**
         * One oversized triangle covering the target, built from the vertex index.
         *
         * Shared by every program in the library, which is what lets the fragment shaders in
         * `core:render` be written as plain image functions with no geometry of their own.
         */
        const val VERTEX_SOURCE = """#version 300 es
precision highp float;

out vec2 vUv;
flat out float vInstance;

void main() {
    // ids 0,1,2 map to (0,0) (2,0) (0,2): a triangle that covers the clip cube with no diagonal
    // seam, unlike the two-triangle quad it replaces.
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    vInstance = float(gl_InstanceID);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""
    }
}
