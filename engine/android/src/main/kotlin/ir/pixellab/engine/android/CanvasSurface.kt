package ir.pixellab.engine.android

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import ir.pixellab.core.canvas.Viewport
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.LayerId
import java.util.concurrent.ConcurrentLinkedQueue
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

    /**
     * Where text layers get their fonts.
     *
     * Settable after construction because the library is scanned asynchronously at launch: the
     * canvas has to be able to draw before the scan finishes, and to redraw with real type once it
     * does.
     *
     * Assigning the same resolver again is ignored. The setter is reached from Compose's `update`,
     * which runs on every recomposition, and taking the change path there would drop every cached
     * silhouette each time a slider moved.
     */
    @Volatile
    var fonts: FontResolver = FontResolver.NONE
        set(value) {
            if (value === field) return
            field = value
            fontsChanged.set(true)
            synchronized(this) { (this as Object).notifyAll() }
        }

    private val fontsChanged = AtomicBoolean(false)

    /**
     * Where masks and patterns come from.
     *
     * Separate from the fonts for the same reason: assets are decoded off the main thread and
     * arrive after the first frame, so the canvas has to be able to draw without them and redraw
     * once they land.
     */
    @Volatile
    var assets: AssetSource = AssetSource.NONE
        set(value) {
            if (value === field) return
            field = value
            assetsChanged.set(true)
            synchronized(this) { (this as Object).notifyAll() }
        }

    private val assetsChanged = AtomicBoolean(false)

    /**
     * Bumped when an asset's pixels have been repainted.
     *
     * A painted layer keeps the same asset id from the first stroke to the last, so nothing else
     * about it tells the renderer's texture cache that the pixels moved.
     */
    @Volatile
    var assetGeneration: Int = 0

    /**
     * Renders the document to a file, on the thread that owns the GL context.
     *
     * An export needs the same programs, the same texture pool and the same context the canvas is
     * already using, and a GL context belongs to one thread. Building a second one just for saving
     * would compile every shader again and double the memory at the exact moment a large export is
     * about to ask for a lot of it.
     *
     * [onResult] is called on the GL thread. The caller has to hop back to its own.
     */
    fun export(
        document: Document,
        format: ir.pixellab.core.codec.Format,
        scale: Float = 1f,
        availableBytes: Long = Long.MAX_VALUE,
        onResult: (ExportResult) -> Unit,
    ) {
        exports.add(ExportRequest(document, format, scale, availableBytes, onResult))
        synchronized(this) { (this as Object).notifyAll() }
    }

    private class ExportRequest(
        val document: Document,
        val format: ir.pixellab.core.codec.Format,
        val scale: Float,
        val availableBytes: Long,
        val onResult: (ExportResult) -> Unit,
    )

    private val exports = ConcurrentLinkedQueue<ExportRequest>()

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
        val exporter = Exporter(context, device, renderer)

        // **A new renderer knows nothing, so it is told everything before its first frame.**
        //
        // This is not belt-and-braces, it is the whole defect. The surface is destroyed and rebuilt
        // for ordinary reasons — the soft keyboard opening over a numeric field, a rotation, going
        // multi-window — and each time this loop starts again with a brand-new `DocumentRenderer`
        // whose font resolver is `FontResolver.NONE`. The "fonts changed" flag was consumed by the
        // *previous* thread, so nothing ever set them again: from that moment every text layer in
        // the document rendered completely blank, permanently, with correctly-sized selection
        // handles around the empty space — because the handles measure through `LayerMeasure`,
        // which is owned by the view model and still had its fonts.
        //
        // Unconditionally, and the flags cleared, so a change that arrives before the first frame
        // is not applied twice.
        renderer.setFonts(fonts)
        renderer.assets = assets
        fontsChanged.set(false)
        assetsChanged.set(false)

        try {
            while (running.get()) {
                if (fontsChanged.getAndSet(false)) renderer.setFonts(fonts)
                if (assetsChanged.getAndSet(false)) renderer.assets = assets
                renderer.assetGeneration = assetGeneration
                drainExports(exporter)

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
            // Anything still queued has no thread left to run on. Answering it is not optional: a
            // caller suspended on an export that never replies waits for the rest of the session.
            while (true) {
                val abandoned = exports.poll() ?: break
                abandoned.onResult(ExportResult.Failed("بوم بسته شد"))
            }
            renderer.dispose()
            device.dispose()
            context.release()
        }
    }

    /**
     * Runs whatever exports are waiting, before the next frame rather than after.
     *
     * Before, because an export resizes the composite buffers to the export size and the next frame
     * puts them back — doing it in the other order would leave the *screen* rendered at export
     * scale for one frame, which reads as the canvas jumping every time the user saves.
     */
    private fun drainExports(exporter: Exporter) {
        while (true) {
            val request = exports.poll() ?: return
            val result = runCatching {
                exporter.export(request.document, request.format, request.scale, request.availableBytes)
            }.getOrElse { ExportResult.Failed(it.message ?: it::class.java.simpleName) }
            request.onResult(result)
        }
    }

    private companion object {
        const val TAG = "PixelLabCanvas"

        /** Long enough that an idle editor costs nothing, short enough to notice a shutdown. */
        const val IDLE_WAIT_MILLIS = 250L

        const val THREAD_JOIN_MILLIS = 1000L
    }
}
