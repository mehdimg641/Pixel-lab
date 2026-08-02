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

        val rawLines = shaped.split('\n')
        val metrics = paint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent) * spec.paragraph.lineHeight
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

        val outline = buildOutline(paint, lines, spec.path)
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
