package ir.pixellab.engine.android

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30

/**
 * An OpenGL ES 3.0 context.
 *
 * Two surfaces matter and they need different setups: the canvas draws onto a `SurfaceView`, while
 * export renders with nothing on screen at all. Both are created here so the rest of the engine
 * never touches EGL — and so export cannot silently depend on a visible window, which is what makes
 * a "save" that only works while the editor is open.
 *
 * Not thread safe by design: an EGL context is current on one thread, and every call into
 * [AndroidGlDevice] has to happen on that thread. The render thread owns one of these.
 */
class GlContext private constructor(
    private val display: EGLDisplay,
    private val context: EGLContext,
    private var surface: EGLSurface,
    private val config: EGLConfig,
) {
    /**
     * Whether the driver can render into half-float textures.
     *
     * Colour banding in a gradient is the visible cost of the fallback, so it is worth knowing
     * rather than assuming: `EXT_color_buffer_half_float` is near universal on ES 3.0 but not
     * guaranteed by the specification.
     */
    val supportsHalfFloatTargets: Boolean by lazy {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        "EXT_color_buffer_half_float" in extensions || "EXT_color_buffer_float" in extensions
    }

    val maxTextureSize: Int by lazy {
        val out = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, out, 0)
        out[0]
    }

    val renderer: String get() = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()

    /**
     * How large the window is, in pixels.
     *
     * Asked of EGL rather than passed in from the view, because the two disagree at exactly the
     * moment it matters: `surfaceChanged` reports the *view's* size, while the drawing surface is
     * whatever EGL bound, and on a device with a display cutout or a rounded-corner inset those are
     * not the same number. Drawing at the wrong one leaves a strip of the window never written.
     */
    val width: Int get() = query(EGL14.EGL_WIDTH)

    val height: Int get() = query(EGL14.EGL_HEIGHT)

    private fun query(attribute: Int): Int {
        val out = IntArray(1)
        return if (EGL14.eglQuerySurface(display, surface, attribute, out, 0)) out[0] else 0
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}"
        }
    }

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, surface)

    /**
     * Re-points this context at a new window, keeping every compiled program and cached texture.
     *
     * Android destroys and recreates a surface on rotation and on returning from the background.
     * Rebuilding the whole context there means recompiling twenty programs while the user watches,
     * which is exactly the stutter that reads as a slow app.
     */
    fun rebindWindow(window: Any) {
        val replacement = EGL14.eglCreateWindowSurface(display, config, window, WINDOW_ATTRIBUTES, 0)
        check(replacement != EGL14.EGL_NO_SURFACE) {
            "eglCreateWindowSurface failed: 0x${EGL14.eglGetError().toString(16)}"
        }
        val previous = surface
        surface = replacement
        makeCurrent()
        if (previous != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previous)
    }

    fun release() {
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
        )
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    companion object {

        private val WINDOW_ATTRIBUTES = intArrayOf(EGL14.EGL_NONE)

        /** For the canvas: [window] is a `Surface` or `SurfaceTexture`. */
        fun forWindow(window: Any): GlContext = create { display, config ->
            EGL14.eglCreateWindowSurface(display, config, window, WINDOW_ATTRIBUTES, 0)
        }

        /** For export and for background work: renders with no window attached. */
        fun offscreen(width: Int = 1, height: Int = 1): GlContext = create { display, config ->
            val attributes = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE,
            )
            EGL14.eglCreatePbufferSurface(display, config, attributes, 0)
        }

        private fun create(surfaceFactory: (EGLDisplay, EGLConfig) -> EGLSurface): GlContext {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }

            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

            // 8 bits per channel with an alpha channel; the pipeline's precision lives in its
            // offscreen textures, not in the window, so asking for more here buys nothing.
            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val found = IntArray(1)
            check(
                EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, found, 0) && found[0] > 0,
            ) { "no EGL config with an 8888 surface and ES 3 support" }
            val config = configs[0]!!

            val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            val context =
                EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
            check(context != EGL14.EGL_NO_CONTEXT) {
                "eglCreateContext failed: 0x${EGL14.eglGetError().toString(16)}"
            }

            val surface = surfaceFactory(display, config)
            check(surface != EGL14.EGL_NO_SURFACE) {
                "EGL surface creation failed: 0x${EGL14.eglGetError().toString(16)}"
            }

            return GlContext(display, context, surface, config).apply { makeCurrent() }
        }

        /** `EGL14` exposes the ES 2 bit but not the ES 3 one. */
        private const val EGL_OPENGL_ES3_BIT = 0x0040
    }
}
