package ir.pixellab.engine.android

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import ir.pixellab.core.canvas.Viewport
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.LayerId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The artwork surface.
 *
 * A `SurfaceView` with its own GL thread rather than a Compose canvas: the effect pipeline must not
 * run on the frame the interface is composed on, or opening a parameter sheet would stall on a
 * ten-shadow stack. The selection chrome is drawn separately, above this, in Compose.
 *
 * The thread renders on demand rather than continuously. A design editor is idle most of the time,
 * and a 60 Hz loop over a heavy style would drain the battery showing an unchanging picture.
 */
class CanvasSurface @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
) : SurfaceView(context, attributes), SurfaceHolder.Callback {

    private val pending = AtomicReference<Frame?>(null)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Reported after each frame, so the interface can surface a shader that failed to compile. */
    var onErrors: (List<String>) -> Unit = {}

    private data class Frame(
        val document: Document,
        val viewport: Viewport,
        val effectsBypassed: Boolean,
        val invalidated: Set<LayerId>,
    )

    init {
        holder.addCallback(this)
    }

    /**
     * Queues a frame, replacing any that has not been drawn yet.
     *
     * Replacing rather than queueing is deliberate: during a drag the model changes faster than the
     * GPU can draw, and a queue would render every intermediate state and fall further behind the
     * finger with each one.
     */
    fun submit(
        document: Document,
        viewport: Viewport,
        effectsBypassed: Boolean = false,
        invalidated: Set<LayerId> = emptySet(),
    ) {
        val previous = pending.getAndSet(Frame(document, viewport, effectsBypassed, invalidated))
        // Invalidations must not be lost when a frame is superseded, or a layer edited mid-drag
        // would keep its stale pixels.
        if (previous != null && previous.invalidated.isNotEmpty()) {
            pending.updateAndGet { it?.copy(invalidated = it.invalidated + previous.invalidated) }
        }
        synchronized(this) { (this as Object).notifyAll() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        stop()
        start(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = stop()

    private fun start(holder: SurfaceHolder) {
        running.set(true)
        thread = Thread({ loop(holder) }, "pixellab-gl").apply { start() }
    }

    private fun stop() {
        running.set(false)
        synchronized(this) { (this as Object).notifyAll() }
        thread?.join(THREAD_JOIN_MILLIS)
        thread = null
    }

    private fun loop(holder: SurfaceHolder) {
        val context = runCatching { GlContext.forWindow(holder.surface) }.getOrElse {
            Log.e(TAG, "no GL context: ${it.message}")
            return
        }
        val device = AndroidGlDevice(context)
        val renderer = DocumentRenderer(device)

        try {
            while (running.get()) {
                val frame = pending.getAndSet(null)
                if (frame == null) {
                    synchronized(this) { (this as Object).wait(IDLE_WAIT_MILLIS) }
                    continue
                }
                frame.invalidated.forEach(renderer::invalidate)

                // Straight to the window: the composite is the frame, so an intermediate
                // full-canvas buffer would be copied for nothing.
                renderer.render(
                    document = frame.document,
                    effectsBypassed = frame.effectsBypassed,
                    viewport = frame.viewport,
                )
                context.swapBuffers()

                val errors = renderer.lastErrors
                if (errors.isNotEmpty()) onErrors(errors.map { "${it.shaderId}: ${it.reason}" })
            }
        } finally {
            renderer.dispose()
            device.dispose()
            context.release()
        }
    }

    private companion object {
        const val TAG = "PixelLabCanvas"

        /** Long enough that an idle editor costs nothing, short enough to notice a shutdown. */
        const val IDLE_WAIT_MILLIS = 250L

        const val THREAD_JOIN_MILLIS = 1000L
    }
}
