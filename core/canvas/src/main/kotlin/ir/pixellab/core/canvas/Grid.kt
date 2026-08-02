package ir.pixellab.core.canvas

import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The grid.
 *
 * A view preference rather than part of the document: how closely someone likes to work is about
 * them, not about the artwork, and a grid saved into a file would follow it onto someone else's
 * screen where it means nothing.
 *
 * [subdivisions] draws lighter lines between the main ones — Photoshop's "gridline every / subdivisions"
 * pair. It matters more than it sounds: a grid dense enough to be useful for placing is too dense to
 * read, and splitting it into two weights is what makes both possible at once.
 */
data class GridSpec(
    val spacing: Float = 100f,
    val subdivisions: Int = 4,
    val visible: Boolean = false,
    val snap: Boolean = true,
) {
    init {
        require(spacing > 0f) { "grid spacing must be positive, got $spacing" }
        require(subdivisions >= 1) { "a grid has at least one division, got $subdivisions" }
    }

    /** Distance between the light lines. Equal to [spacing] when there are no subdivisions. */
    val step: Float get() = spacing / subdivisions

    /**
     * Every line across [extent], as coordinates paired with whether it is a major one.
     *
     * Generated rather than drawn by stepping in the renderer so the count can be bounded in one
     * place: a 4-unit grid on a 6000-pixel canvas is fifteen hundred lines, which is slower to draw
     * than the artwork and reads as solid grey anyway.
     */
    fun lines(extent: Float): List<Pair<Float, Boolean>> {
        if (extent <= 0f) return emptyList()
        val count = (extent / step).toInt()
        if (count > MAX_LINES) return majorOnly(extent)
        return (0..count).map { index ->
            val at = index * step
            at to (index % subdivisions == 0)
        }
    }

    private fun majorOnly(extent: Float): List<Pair<Float, Boolean>> {
        val count = (extent / spacing).toInt()
        if (count > MAX_LINES) return emptyList()
        return (0..count).map { it * spacing to true }
    }

    /** The nearest grid line to [value], which is all snapping needs. */
    fun nearest(value: Float): Float = (value / step).roundToInt() * step

    private companion object {
        /**
         * Past this the grid is denser than the screen resolves and costs more to draw than the
         * artwork. Falling back to the major lines keeps something useful on screen instead of
         * either a grey wash or nothing at all.
         */
        const val MAX_LINES = 400
    }
}

/**
 * The region of a design that survives a platform's own cropping.
 *
 * Every social platform crops or overlays something: Instagram covers the bottom of a story with
 * its own controls, YouTube puts a duration badge over the corner of a thumbnail. A title placed
 * where the platform will cover it is the single most common way a finished design is wasted, and
 * it is invisible until it is published.
 */
data class SafeZone(
    val name: String,
    /** Insets as fractions of the canvas, so one preset works at any resolution. */
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun rectFor(canvas: Vec2) = Rect(
        canvas.x * left,
        canvas.y * top,
        canvas.x * (1f - right),
        canvas.y * (1f - bottom),
    )

    companion object {
        /**
         * The three that matter for the work this app is for.
         *
         * Measured from where each platform's own interface actually sits, not guessed: a story's
         * bottom fifth is under the reply bar, its top tenth under the profile row; a thumbnail's
         * bottom-right corner carries the duration badge.
         */
        val STORY = SafeZone("استوری", left = 0.06f, top = 0.14f, right = 0.06f, bottom = 0.20f)
        val POST = SafeZone("پست", left = 0.04f, top = 0.04f, right = 0.04f, bottom = 0.04f)
        val THUMBNAIL = SafeZone("کاور ویدیو", left = 0.03f, top = 0.03f, right = 0.03f, bottom = 0.12f)

        val ALL = listOf(STORY, POST, THUMBNAIL)
    }
}

/**
 * Ruler tick marks.
 *
 * The spacing has to be chosen from the zoom rather than fixed, or the ruler is either unreadably
 * dense when zoomed out or shows two numbers when zoomed in. The steps below are the 1-2-5 sequence
 * every ruler uses, for the same reason: they divide evenly into the numbers people think in.
 */
object Ruler {

    /** Canvas-unit spacing whose on-screen distance is closest to [targetScreen] pixels. */
    fun stepFor(zoom: Float, targetScreen: Float = TARGET_SCREEN_SPACING): Float {
        if (zoom <= 0f) return STEPS.last()
        val ideal = targetScreen / zoom
        // Walk the 1-2-5 sequence across decades until one is wide enough on screen.
        var decade = DECADE_START
        while (decade < DECADE_LIMIT) {
            for (multiple in STEPS) {
                val candidate = multiple * decade
                if (candidate >= ideal) return candidate
            }
            decade *= DECADE
        }
        return STEPS.last() * DECADE_LIMIT
    }

    /** Tick positions across [extent] at [step], including the origin. */
    fun ticks(extent: Float, step: Float): List<Float> {
        if (step <= 0f || extent <= 0f) return emptyList()
        val count = floor(extent / step).toInt()
        if (count > MAX_TICKS) return emptyList()
        return (0..count).map { it * step }
    }

    private val STEPS = listOf(1f, 2f, 5f)
    private const val DECADE = 10f
    private const val DECADE_START = 1f
    private const val DECADE_LIMIT = 100_000f

    /** Roughly a thumb's width: close enough to read, far enough apart not to crowd. */
    private const val TARGET_SCREEN_SPACING = 80f

    private const val MAX_TICKS = 200
}
