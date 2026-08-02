package ir.pixellab.engine.android

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.model.KashidaMode
import ir.pixellab.core.model.TextPath
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.text.BidiAnalyzer
import ir.pixellab.core.text.DigitShaper
import ir.pixellab.core.text.KashidaPlanner
import ir.pixellab.core.text.PhysicalAlign
import ir.pixellab.core.text.TextWarper
import kotlin.math.abs

/** A laid-out line with its resolved content and metrics. */
data class TextLine(
    val text: String,
    val width: Float,
    val ascent: Float,
    val descent: Float,
    /** Left edge in layout space, after alignment. */
    val x: Float,
    /** Baseline position. */
    val baseline: Float,
)

/** The outcome of laying out and outlining a text layer. */
data class RasterizedText(
    val lines: List<TextLine>,
    /** Outline of the whole shaped run, in layout coordinates. */
    val outline: Path,
    val bounds: RectF,
    /** Which elongation mechanism was actually used. */
    val kashida: KashidaMode,
    /** True when the user's string was left byte-for-byte intact. */
    val textPreserved: Boolean,
)

/**
 * Turns a [TextSpec] into a shaped outline.
 *
 * Shaping itself is Skia's job — it runs HarfBuzz internally and knows Arabic letter joining better
 * than anything we would write. What matters here is *what we hand it*: the whole run at once.
 *
 * `Paint.getTextPath` returns the outline of the entire shaped string, so joining is already
 * resolved inside the geometry. Every effect downstream — extrusion, 3D tessellation, bevel — then
 * operates on a path in which Persian letters are already connected. Positioning glyphs
 * individually would be the natural-looking alternative and it is exactly the mistake that breaks
 * Persian: the letters detach.
 */
class TextRasterizer(private val loader: TypefaceLoader = TypefaceLoader()) {

    /**
     * @param font the resolved file backing [spec]'s font reference
     */
    fun rasterize(spec: TextSpec, font: FontFile): RasterizedText {
        val capabilities = font.capabilities
        val plan = KashidaPlanner.plan(spec.text, spec.font, spec.paragraph, capabilities)

        val shaped = if (spec.paragraph.persianDigits && font.hasPersianDigits) {
            DigitShaper.toPersian(plan.text)
        } else {
            plan.text
        }

        val variations = loader.resolveVariations(font, plan.variations)
        val typeface = loader.load(font, variations) ?: Typeface.DEFAULT
        val paint = paintFor(spec, typeface)

        val baseRtl = BidiAnalyzer.resolveBaseDirection(shaped, spec.paragraph.direction)
        val align = BidiAnalyzer.physicalAlign(spec.paragraph.align, baseRtl)

        val metrics = paint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent) * spec.paragraph.lineHeight
        // Area type wraps inside its frame; point type grows with the string. The distinction is not
        // cosmetic — it decides whether typing makes the layer wider or makes it taller, and a
        // paragraph in a cover design always wants the second.
        val rawLines = if (spec.boxMode == ir.pixellab.core.model.TextBoxMode.AREA && spec.boxSize != null) {
            wrap(paint, shaped, spec.boxSize!!.x)
        } else {
            shaped.split('\n')
        }
        val widths = rawLines.map { paint.measureText(it) }
        val blockWidth = spec.boxSize?.x ?: (widths.maxOrNull() ?: 0f)

        val lines = ArrayList<TextLine>(rawLines.size)
        var baseline = -metrics.ascent
        for (i in rawLines.indices) {
            val w = widths[i]
            val x = when (align) {
                PhysicalAlign.LEFT, PhysicalAlign.JUSTIFY -> 0f
                PhysicalAlign.CENTER -> (blockWidth - w) / 2f
                PhysicalAlign.RIGHT -> blockWidth - w
            }
            lines += TextLine(rawLines[i], w, metrics.ascent, metrics.descent, x, baseline)
            baseline += lineHeight
        }

        val straight = buildOutline(paint, lines, spec.path)
        // Warp is applied to the shaped *outline*, not to the baseline before it. Bending the
        // baseline leaves each letter upright and gives the row-of-flags result every naive
        // implementation produces; bending the outline bends the letters, which is what the panel
        // actually does.
        val outline = if (spec.warp.isActive) warp(straight, spec.warp) else straight

        val bounds = RectF()
        // The single-argument overload only exists from API 34; minSdk is 26.
        @Suppress("DEPRECATION")
        outline.computeBounds(bounds, true)

        return RasterizedText(
            lines = lines,
            outline = outline,
            bounds = bounds,
            kashida = plan.mode,
            textPreserved = plan.preservesText,
        )
    }

    /** Configures a paint from the spec, including OpenType features. */
    fun paintFor(spec: TextSpec, typeface: Typeface): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = spec.size
        // Em units in the model, pixels in Paint.
        letterSpacing = spec.paragraph.letterSpacing
        isSubpixelText = true
        if (spec.font.features.isNotEmpty()) {
            // Stylistic sets carrying alternate Persian letterforms reach the shaper through here.
            fontFeatureSettings = TypefaceLoader.formatFeatures(spec.font.features)
        }
    }

    /**
     * Wraps a string to a width.
     *
     * Broken at spaces, and a word longer than the frame is left to overflow rather than being cut
     * mid-word. Splitting inside a word is what turns a Persian compound into two fragments that
     * each shape wrong — the letters at the break would join to nothing.
     */
    private fun wrap(paint: Paint, text: String, width: Float): List<String> {
        if (width <= 0f) return text.split('\n')
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) {
                out += ""
                continue
            }
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= width || line.isEmpty()) {
                    line = StringBuilder(candidate)
                } else {
                    out += line.toString()
                    line = StringBuilder(word)
                }
            }
            out += line.toString()
        }
        return out
    }

    /**
     * Bends a finished outline.
     *
     * Every point of the path is mapped, which needs the path flattened first — a warp of a Bézier's
     * control points is not the warp of its curve, and the difference shows as the curve pulling
     * away from where the letters should be at exactly the tightest bends.
     */
    private fun warp(outline: Path, warp: ir.pixellab.core.model.TextWarp): Path {
        val box = RectF()
        @Suppress("DEPRECATION")
        outline.computeBounds(box, true)
        if (box.width() <= 0f || box.height() <= 0f) return outline

        val bounds = ir.pixellab.core.model.Rect(box.left, box.top, box.right, box.bottom)
        val flat = Path()
        val measure = android.graphics.PathMeasure(outline, false)
        val position = FloatArray(2)

        do {
            val length = measure.length
            if (length <= 0f) continue
            val steps = (length / WARP_STEP).toInt().coerceIn(2, MAX_WARP_SAMPLES)
            for (i in 0..steps) {
                measure.getPosTan(length * i / steps, position, null)
                val mapped = TextWarper.map(
                    ir.pixellab.core.model.Vec2(position[0], position[1]),
                    bounds,
                    warp,
                )
                if (i == 0) flat.moveTo(mapped.x, mapped.y) else flat.lineTo(mapped.x, mapped.y)
            }
            if (measure.isClosed) flat.close()
        } while (measure.nextContour())

        return flat
    }

    /** Outlines every line, bending onto a guide path when the layer asks for one. */
    private fun buildOutline(paint: Paint, lines: List<TextLine>, path: TextPath?): Path {
        val out = Path()
        for (line in lines) {
            if (line.text.isEmpty()) continue
            val straight = Path()
            paint.getTextPath(line.text, 0, line.text.length, line.x, line.baseline, straight)
            out.addPath(
                when (path) {
                    null -> straight
                    is TextPath.Arc -> warp(straight, arcPath(path, line.width), line)
                    is TextPath.Custom -> warp(straight, customPath(path), line)
                },
            )
        }
        return out
    }

    /**
     * Bends an already-shaped outline onto a guide.
     *
     * The warp is applied to the *outline*, not to glyph positions: each point of the shaped path
     * is moved to the guide position at its own horizontal distance, displaced perpendicular by its
     * height above the baseline. Because the letters were joined before this runs, the connections
     * bend with them. Repositioning glyphs individually would be the obvious alternative and is
     * exactly what detaches Persian letters in editors that do it.
     *
     * The outline is flattened first via [Path.approximate]; at half a pixel of error the
     * difference from the true curve is not visible, and Android exposes no way to transform
     * control points individually.
     */
    private fun warp(outline: Path, guide: Path, line: TextLine): Path {
        val measure = android.graphics.PathMeasure(guide, false)
        val guideLength = measure.length
        if (guideLength <= 0f || line.width <= 0f) return outline

        val flattened = outline.approximate(FLATTEN_ERROR)
        if (flattened.size < 6) return outline

        val warped = Path()
        val position = FloatArray(2)
        val tangent = FloatArray(2)
        var started = false
        var previousFraction = -1f

        var i = 0
        while (i < flattened.size) {
            val fraction = flattened[i]
            val x = flattened[i + 1]
            val y = flattened[i + 2]

            // approximate() emits contours in order; a fraction that goes backwards starts a new one.
            if (fraction < previousFraction) started = false
            previousFraction = fraction

            val along = ((x - line.x) / line.width * guideLength).coerceIn(0f, guideLength)
            if (measure.getPosTan(along, position, tangent)) {
                val len = kotlin.math.hypot(tangent[0], tangent[1]).takeIf { it > 0f } ?: 1f
                val nx = -tangent[1] / len
                val ny = tangent[0] / len
                // Height above the baseline becomes displacement along the guide's normal.
                val offset = y - line.baseline
                val px = position[0] + nx * offset
                val py = position[1] + ny * offset
                if (started) warped.lineTo(px, py) else { warped.moveTo(px, py); started = true }
            }
            i += 3
        }
        return if (warped.isEmpty) outline else warped
    }

    private fun arcPath(arc: TextPath.Arc, width: Float): Path {
        val radius = abs(arc.radius).coerceAtLeast(1f)
        val sweep = Math.toDegrees((width / radius).toDouble()).toFloat()
        val start = arc.startAngle
        return Path().apply {
            addArc(
                RectF(-radius, -radius, radius, radius),
                if (arc.flip) start + sweep else start,
                if (arc.flip) -sweep else sweep,
            )
        }
    }

    private companion object {
        /** A sample every half unit keeps a warped letter smooth at the tightest bends. */
        const val WARP_STEP = 0.5f

        /** A guard against a pathological outline with an enormous perimeter. */
        const val MAX_WARP_SAMPLES = 2048

        /** Flattening tolerance in pixels; below one pixel the curve difference is invisible. */
        const val FLATTEN_ERROR = 0.5f
    }

    private fun customPath(custom: TextPath.Custom): Path = Path().apply {
        custom.points.forEachIndexed { i, p ->
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        if (custom.closed) close()
    }
}