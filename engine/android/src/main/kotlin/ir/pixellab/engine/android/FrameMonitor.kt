package ir.pixellab.engine.android

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.Choreographer
import ir.pixellab.core.render.FrameBudget
import ir.pixellab.core.render.Quality
import ir.pixellab.core.render.RasterBudget

/**
 * Measures real frame times and turns them into a quality decision.
 *
 * Through `Choreographer` rather than a timer, because `Choreographer` is the clock the display
 * actually runs on: its callback fires once per vsync with the timestamp the frame was scheduled
 * for, so the gap between two callbacks *is* the frame duration including everything the platform
 * did. A wall-clock timer around the drawing code measures the drawing and misses every stall
 * outside it, which is where jank usually lives.
 *
 * The policy itself is in [FrameBudget] and has no Android in it, so it is testable; this class is
 * only the wiring.
 */
class FrameMonitor(
    private val budget: FrameBudget = FrameBudget(),
    private val onQualityChange: (Quality) -> Unit = {},
) : Choreographer.FrameCallback {

    var quality: Quality = Quality.FULL
        private set

    /** Set from the thermal listener; caps how high the quality may climb. */
    var ceiling: Quality = Quality.FULL

    private var running = false
    private var lastFrameNanos = 0L

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        budget.reset()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos != 0L) {
            val elapsed = frameTimeNanos - lastFrameNanos
            // A gap of a second or more is the app having been backgrounded, not a slow frame.
            // Feeding it in would drop the quality to minimum on every return to the foreground.
            if (elapsed in 1 until PAUSE_THRESHOLD) budget.record(elapsed)
        }
        lastFrameNanos = frameTimeNanos

        val wanted = budget.recommend(quality).atMost(ceiling)
        if (wanted != quality) {
            quality = wanted
            onQualityChange(wanted)
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** The worse of two tiers, since the enum is ordered best-first. */
    private fun Quality.atMost(cap: Quality) = if (ordinal >= cap.ordinal) this else cap

    private companion object {
        const val PAUSE_THRESHOLD = 1_000_000_000L
    }
}

/**
 * What the device's heat and memory allow.
 *
 * Thermal throttling is the failure mode that matters on this hardware. A Snapdragon 8+ Gen 1
 * sustains its peak clocks for about a minute and then steps down, so an editor that ignores
 * thermal state runs beautifully in a review and stutters after two minutes of real work — which
 * is the only case that counts.
 */
object DeviceLimits {

    /**
     * The highest quality this device should currently attempt.
     *
     * A cap rather than a setting: the frame budget still decides within it, so a cool device that
     * is nonetheless struggling still drops, and a hot one cannot climb back up until it recovers.
     */
    fun thermalCeiling(context: Context): Quality {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Quality.FULL
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return Quality.FULL
        return when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> Quality.FULL
            PowerManager.THERMAL_STATUS_MODERATE -> Quality.HIGH
            PowerManager.THERMAL_STATUS_SEVERE -> Quality.INTERACTIVE
            // Critical and above: the system is about to start shutting things down, and a picture
            // that is a quarter of the resolution is worth far more than no app at all.
            else -> Quality.MINIMAL
        }
    }

    /**
     * A raster budget derived from what this device actually gives the process.
     *
     * Measured rather than assumed. The specification's 400 MB is right for a flagship and wrong
     * for a two-gigabyte phone, where the whole heap is smaller than that — and an app sized for a
     * number the device will not honour is an app that is killed rather than slowed.
     */
    fun rasterBudget(context: Context): RasterBudget {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return RasterBudget()
        val heapBytes = activity.largeMemoryClass.toLong() * 1024 * 1024
        // A share of the heap, not all of it: the document, the undo stack, the fonts and Compose
        // itself all live in the same place.
        val share = (heapBytes * HEAP_SHARE).toLong()
        return RasterBudget(share.coerceIn(MIN_BUDGET, RasterBudget.DEFAULT_LIMIT))
    }

    /** Whether the system is already asking for memory back. */
    fun underMemoryPressure(context: Context): Boolean {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val info = ActivityManager.MemoryInfo()
        activity.getMemoryInfo(info)
        return info.lowMemory
    }

    private const val HEAP_SHARE = 0.55f

    /** Below this the editor cannot hold one canvas and its backdrop, so there is no point going lower. */
    private const val MIN_BUDGET = 48L * 1024 * 1024
}
