package ir.pixellab.engine.android

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.model.CharacterStyle
import ir.pixellab.core.model.KashidaMode
import ir.pixellab.core.model.StyleRun
import ir.pixellab.core.model.TextPath
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.text.BidiAnalyzer
import ir.pixellab.core.text.DigitShaper
import ir.pixellab.core.text.KashidaPlanner
import ir.pixellab.core.text.PhysicalAlign
import ir.pixellab.core.text.StyleRuns
import ir.pixellab.core.text.StyleSegment
import ir.pixellab.core.text.TextWarper
import kotlin.math.abs

/** A laid-out line with its resolved content and metrics. */
data class TextLine(
    val text: String,
    /** Where this line starts in the shaped string, so a style range can be clipped to it. */
    val start: Int,
    val width: Float,
    val ascent: Float,
    val descent: Float,
    /** Left edge in layout space, after alignment. */
    val x: Float,
    /** Baseline position. */
    val baseline: Float,
)

/**
 * One stretch of the outline that is painted differently from the rest.
 *
 * The indices are into the *shaped* string rather than the user's, because tatweel elongation can
 * add characters before the outline is built; `StyleRuns.remap` does that translation once, at the
 * top of [TextRasterizer.rasterize].
 */
data class StyledPiece(
    val start: Int,
    val end: Int,
    val style: CharacterStyle,
    val outline: Path,
)

/** The outcome of laying out and outlining a text layer. */
data class RasterizedText(
    val lines: List<TextLine>,
    /** Outline of the whole shaped run, in layout coordinates. */
    val outline: Path,
    /**
     * The outline broken into differently-painted stretches, or empty when the layer has none.
     *
     * Empty is the important case and it is not merely an optimisation: every caller that only
     * wants a silhouette — measuring, converting to a shape, extruding to 3D — reads [outline] and
     * is unaffected by this existing at all. A layer with no styled ranges produces an empty list
     * and travels the code path it travelled before ranges were a thing.
     */
    val pieces: List<StyledPiece>,
    /**
     * The panel behind the words, or null when the layer has none.
     *
     * Kept out of [outline] deliberately. Everything that asks for the *letters* — extruding them
     * to 3D, converting them to a vector shape — reads [outline], and a caption whose background
     * quietly became part of its geometry would extrude as a slab with words on it. What does read
     * both is [bounds], because the layer really is as big as its panel, and the silhouette, because
     * a stroke or a shadow belongs around the panel and not around the words inside it.
     */
    val background: Path?,
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
        val ranges = if (spec.boxMode == ir.pixellab.core.model.TextBoxMode.AREA && spec.boxSize != null) {
            wrap(paint, shaped, spec.boxSize!!.x)
        } else {
            paragraphs(shaped)
        }
        val rawLines = ranges.map { shaped.substring(it.first, it.second) }
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
            lines += TextLine(rawLines[i], ranges[i].first, w, metrics.ascent, metrics.descent, x, baseline)
            baseline += lineHeight
        }

        // Ranges the user styled differently, moved onto the shaped string — tatweel elongation
        // inserts characters, and without the remap the colours would land on the wrong letters the
        // moment the kashida slider is touched.
        val runs = StyleRuns.remap(spec.runs, spec.text, shaped)
        val pieces = if (runs.isEmpty()) {
            emptyList()
        } else {
            buildPieces(paint, typeface, spec, shaped, lines, runs, baseRtl, spec.path)
        }

        // **Where the ranges only change paint, the outline is built exactly as it always was**,
        // and never from the pieces. That is not a shortcut, it is the guarantee: coverage — and
        // therefore every stroke, shadow and glow that reads it — comes from the one path this file
        // has always produced, so tinting a range provably cannot change the shape of a letter.
        //
        // Assembling it from the pieces instead would put it at the mercy of path intersection,
        // whose curve reconstruction differs from the original by fractions of a pixel. A test
        // caught precisely that, and the fix was to stop asking the question.
        //
        // A range that changes *size* is the exception and has to come from the pieces, because the
        // line genuinely is laid out differently — a silhouette drawn at the base size while the
        // paint sits at the new one would show a word wearing the wrong shape.
        val relaidOut = pieces.any { it.style.changesMetrics }
        val straight = if (relaidOut) {
            Path().apply { for (piece in pieces) addPath(piece.outline) }
        } else {
            buildOutline(paint, lines, spec.path)
        }
        // Warp is applied to the shaped *outline*, not to the baseline before it. Bending the
        // baseline leaves each letter upright and gives the row-of-flags result every naive
        // implementation produces; bending the outline bends the letters, which is what the panel
        // actually does.
        //
        // Every piece is bent against the bounds of the *whole* block rather than its own. Warping
        // each piece independently would normalise each one to its own box, so a tinted word would
        // bend on a different curve from the words around it and slide off the line.
        val box = if (spec.warp.isActive) boundsOf(straight) else null
        val outline = if (box != null) warp(straight, spec.warp, box) else straight
        val bent = if (box == null) pieces else pieces.map { it.copy(outline = warp(it.outline, spec.warp, box)) }

        // Built from the *unwarped* line metrics and then bent with everything else, so a panel
        // behind arched text arches with it rather than staying a straight rectangle behind curved
        // words — which is what building it from the final bounds would give.
        val panel = spec.background?.let { background ->
            val straightPanel = buildBackground(background, lines, paint)
            if (box != null) warp(straightPanel, spec.warp, box) else straightPanel
        }

        return RasterizedText(
            lines = lines,
            outline = outline,
            pieces = bent,
            background = panel,
            // The layer is as big as its panel, which is larger than its letters — measure the two
            // together or the background is clipped by the selection box it sits inside.
            bounds = boundsOf(Path().apply { addPath(outline); panel?.let(::addPath) }),
            kashida = plan.mode,
            textPreserved = plan.preservesText,
        )
    }

    /**
     * The rounded panels behind the words.
     *
     * One per line by default, each only as wide as its own line. A single box around the whole
     * paragraph — which is what every editor that offers this does — leaves a wide empty band beside
     * the short lines of a centred title, and that band is the difference between a design and a
     * placeholder.
     *
     * The vertical extent comes from the font's ascent and descent rather than from the ink, so two
     * consecutive lines get panels of the same height whether or not either happens to contain a
     * descender. Sizing to the ink is the obvious alternative and it makes a stack of panels ripple.
     */
    private fun buildBackground(
        background: ir.pixellab.core.model.TextBackground,
        lines: List<TextLine>,
        paint: Paint,
    ): Path {
        val path = Path()
        val drawn = lines.filter { it.text.isNotBlank() }
        if (drawn.isEmpty()) return path

        val radius = background.cornerRadius
        val boxes = if (background.perLine) {
            drawn.map { line ->
                RectF(
                    line.x - background.paddingX,
                    line.baseline + line.ascent - background.paddingY,
                    line.x + line.width + background.paddingX,
                    line.baseline + line.descent + background.paddingY,
                )
            }
        } else {
            listOf(
                RectF(
                    drawn.minOf { it.x } - background.paddingX,
                    drawn.first().let { it.baseline + it.ascent } - background.paddingY,
                    drawn.maxOf { it.x + it.width } + background.paddingX,
                    drawn.last().let { it.baseline + it.descent } + background.paddingY,
                ),
            )
        }
        for (box in boxes) path.addRoundRect(box, radius, radius, Path.Direction.CW)
        // Overlapping panels on tight leading would otherwise cancel each other out under the
        // even-odd rule Skia uses for a path built from several contours.
        path.fillType = Path.FillType.WINDING
        return path
    }

    private fun boundsOf(path: Path): RectF {
        val bounds = RectF()
        // The single-argument overload only exists from API 34; minSdk is 26.
        @Suppress("DEPRECATION")
        path.computeBounds(bounds, true)
        return bounds
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
    private fun wrap(paint: Paint, text: String, width: Float): List<Pair<Int, Int>> {
        if (width <= 0f) return paragraphs(text)
        val out = ArrayList<Pair<Int, Int>>()
        for ((paragraphStart, paragraphEnd) in paragraphs(text)) {
            if (paragraphStart == paragraphEnd) {
                out += paragraphStart to paragraphEnd
                continue
            }
            var lineStart = paragraphStart
            var lineEnd = paragraphStart
            var wordStart = paragraphStart
            var at = paragraphStart
            while (at <= paragraphEnd) {
                if (at < paragraphEnd && text[at] != ' ') {
                    at++
                    continue
                }
                // `at` is a space or the end of the paragraph, so [wordStart, at) is one word.
                val candidateEnd = at
                if (paint.measureText(text, lineStart, candidateEnd) <= width || lineEnd == lineStart) {
                    lineEnd = candidateEnd
                } else {
                    out += lineStart to lineEnd
                    lineStart = wordStart
                    lineEnd = candidateEnd
                }
                at++
                wordStart = at
            }
            out += lineStart to lineEnd
        }
        return out
    }

    /**
     * The hard line breaks, as ranges rather than substrings.
     *
     * Ranges rather than pieces of text because a styled range has to be clipped to the line that
     * contains it, and inferring each line's offset by adding up the lengths in front of it is the
     * kind of arithmetic that is right until somebody types two spaces.
     */
    private fun paragraphs(text: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        for (i in text.indices) {
            if (text[i] != '\n') continue
            out += start to i
            start = i + 1
        }
        out += start to text.length
        return out
    }

    /**
     * Bends a finished outline.
     *
     * Every point of the path is mapped, which needs the path flattened first — a warp of a Bézier's
     * control points is not the warp of its curve, and the difference shows as the curve pulling
     * away from where the letters should be at exactly the tightest bends.
     */
    private fun warp(outline: Path, warp: ir.pixellab.core.model.TextWarp, box: RectF): Path {
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

    /**
     * Splits the outline into the stretches that are painted differently.
     *
     * ### Two paths, and the one that matters is the exact one
     *
     * **Paint-only ranges — "make this word red".** The line is shaped **once, whole**, exactly as
     * it always was, and the finished outline is then cut into vertical bands at the advance
     * positions. Nothing about the shaping changes, so the letters are provably the same letters:
     * the joins survive because they were never asked to be re-made. The colour boundary lands on
     * the glyph advance, which is where Photoshop and InDesign put it too — the connecting stroke
     * belongs to whichever glyph drew it.
     *
     * This is the path almost every document takes, and it is why the promise "tinting a word
     * cannot break Persian" is something the renderer *cannot* violate rather than something it
     * tries to get right.
     *
     * **Ranges that change size or spacing.** One string cannot be shaped at two sizes, so the line
     * has to be laid out run by run, and cutting a joined word into runs is exactly what detaches
     * Persian letters. `StyleRuns.padded` restores the joining context with U+200D at any cut that
     * falls inside a cluster, which makes the shaper choose the medial form it would have chosen
     * anyway. It draws nothing and takes no width.
     *
     * ### Mixed direction
     *
     * A line is cut per directional run, so a Persian sentence containing a Latin brand name splits
     * at the language boundary before it splits at the style boundary — otherwise one logical range
     * would map to two separate places on screen and a single band would cover the wrong glyphs.
     * Runs are placed in visual order, which for a single embedding level is the logical order
     * reversed under an RTL base. Deeper nesting needs explicit embedding controls in the string,
     * which no caption in this application produces.
     */
    private fun buildPieces(
        paint: Paint,
        typeface: Typeface,
        spec: TextSpec,
        shaped: String,
        lines: List<TextLine>,
        runs: List<StyleRun>,
        baseRtl: Boolean,
        path: TextPath?,
    ): List<StyledPiece> {
        val out = ArrayList<StyledPiece>()
        for (line in lines) {
            if (line.text.isEmpty()) continue
            val segments = StyleRuns.segmentsIn(shaped, runs, line.start, line.start + line.text.length)
            if (segments.isEmpty()) continue

            // One segment covering the line is the whole line, so there is nothing to cut and the
            // ordinary outline is used unchanged.
            if (segments.size == 1 && !segments[0].style.changesMetrics) {
                val whole = Path()
                paint.getTextPath(line.text, 0, line.text.length, line.x, line.baseline, whole)
                out += StyledPiece(
                    start = line.start,
                    end = line.start + line.text.length,
                    style = segments[0].style,
                    outline = guided(whole, path, line),
                )
                continue
            }

            val exact = segments.none { it.style.changesMetrics }
            for (run in BidiAnalyzer.runs(line.text, baseRtl).sortedBy { visualOrder(it.start, baseRtl, line.text.length) }) {
                val within = segments.mapNotNull { segment ->
                    val start = maxOf(segment.start, run.start)
                    val end = minOf(segment.end, run.end)
                    if (start >= end) null else StyleSegment(start, end, segment.style)
                }
                if (within.isEmpty()) continue
                if (exact) {
                    bandPieces(paint, line, run.start, run.end, run.rightToLeft, within, path, out)
                } else {
                    laidOutPieces(paint, typeface, spec, line, run.rightToLeft, within, path, out)
                }
            }
        }
        return out
    }

    /**
     * Visual position of a directional run inside its line.
     *
     * A sort key rather than a reordering, so the caller stays a one-liner. Under an RTL base the
     * first logical run sits furthest right, which is the whole of bidi reordering at one embedding
     * level — and one level is all that a caption without explicit override characters can produce.
     */
    private fun visualOrder(start: Int, baseRtl: Boolean, length: Int): Int =
        if (baseRtl) length - start else start

    /**
     * Cuts an already-shaped line into bands, one per styled stretch.
     *
     * The band edges come from [Paint.getRunAdvance], which is the API that exists precisely to ask
     * "how far along this run does offset *n* sit" while shaping the whole run for context. Reading
     * the same number from the sum of individually-measured substrings would drift, because a
     * letter's advance depends on the letters beside it.
     *
     * Bands overlap each other by half a pixel. Two filled paths that share an exact edge leave an
     * antialiased hairline of background between them — a pale seam through the middle of a word,
     * which looks like a rendering fault rather than a colour change. The overlap costs a half
     * pixel of the later colour and removes the seam entirely.
     */
    private fun bandPieces(
        paint: Paint,
        line: TextLine,
        runStart: Int,
        runEnd: Int,
        rightToLeft: Boolean,
        segments: List<StyleSegment>,
        path: TextPath?,
        out: MutableList<StyledPiece>,
    ) {
        val whole = Path()
        paint.getTextPath(line.text, 0, line.text.length, line.x, line.baseline, whole)
        val box = RectF()
        @Suppress("DEPRECATION")
        whole.computeBounds(box, true)
        if (box.isEmpty) return

        val runWidth = advanceTo(paint, line.text, runStart, runEnd, rightToLeft, runEnd)
        val runLeft = line.x + leadingEdge(paint, line.text, runStart, rightToLeft)

        for (segment in segments) {
            val from = advanceTo(paint, line.text, runStart, runEnd, rightToLeft, segment.start)
            val to = advanceTo(paint, line.text, runStart, runEnd, rightToLeft, segment.end)
            val left = runLeft + if (rightToLeft) runWidth - to else from
            val right = runLeft + if (rightToLeft) runWidth - from else to
            if (right <= left) continue

            val band = Path().apply {
                addRect(left - SEAM, box.top - 1f, right + SEAM, box.bottom + 1f, Path.Direction.CW)
            }
            val piece = Path().apply { op(whole, band, Path.Op.INTERSECT) }
            if (piece.isEmpty) continue
            out += StyledPiece(
                start = line.start + segment.start,
                end = line.start + segment.end,
                style = segment.style,
                outline = guided(piece, path, line),
            )
        }
    }

    /**
     * Where a directional run's leading edge sits, measured from the left of the line.
     *
     * Everything before it in visual order, added up. For a line that is one run — which is every
     * Persian headline without a Latin word in it — this is zero and costs nothing.
     */
    private fun leadingEdge(paint: Paint, line: String, runStart: Int, rightToLeft: Boolean): Float =
        if (runStart == 0 && !rightToLeft) 0f else paint.measureText(line, 0, line.length).let { total ->
            // Visual left of a run is the total minus everything drawn to its right. With one
            // embedding level that is the text after it under LTR, and before it under RTL.
            val ahead = if (rightToLeft) paint.measureText(line, runStart, line.length) else paint.measureText(line, 0, runStart)
            if (rightToLeft) total - ahead else ahead
        }

    /** Advance from a run's leading edge to [offset], with the whole line as shaping context. */
    private fun advanceTo(
        paint: Paint,
        line: String,
        runStart: Int,
        runEnd: Int,
        rightToLeft: Boolean,
        offset: Int,
    ): Float = paint.getRunAdvance(line, runStart, runEnd, 0, line.length, rightToLeft, offset.coerceIn(runStart, runEnd))

    /**
     * Lays a run out piece by piece, for the case where the pieces do not share metrics.
     *
     * Each piece is shaped with its joining context restored, and the pen advances by what that
     * piece actually measured. The result is not identical to shaping the line whole — it cannot
     * be, because the line no longer has one set of metrics — but the letterforms are the ones the
     * script requires, which is the part that matters and the part everybody else gets wrong.
     */
    private fun laidOutPieces(
        paint: Paint,
        typeface: Typeface,
        spec: TextSpec,
        line: TextLine,
        rightToLeft: Boolean,
        segments: List<StyleSegment>,
        path: TextPath?,
        out: MutableList<StyledPiece>,
    ) {
        val ordered = if (rightToLeft) segments.sortedByDescending { it.start } else segments.sortedBy { it.start }
        val painted = ordered.map { segment ->
            val style = segment.style
            val own = Paint(paint).apply {
                this.typeface = typeface
                textSize = spec.size * style.sizeScale
                letterSpacing = style.letterSpacing ?: spec.paragraph.letterSpacing
            }
            val padded = StyleRuns.padded(line.text, segment.start, segment.end)
            segment to (own to padded)
        }

        val total = painted.sumOf { (_, paint) -> paint.first.measureText(paint.second.text).toDouble() }.toFloat()
        var pen = line.x + if (rightToLeft) line.width - total else 0f

        for ((segment, painting) in painted) {
            val (own, padded) = painting
            val width = own.measureText(padded.text)
            val piece = Path()
            // Baseline shift is a fraction of the type size and lifts, so it subtracts in screen
            // coordinates where y grows downwards.
            val baseline = line.baseline - segment.style.baselineShift * spec.size
            own.getTextPath(padded.text, 0, padded.text.length, pen, baseline, piece)
            pen += width
            if (piece.isEmpty) continue
            out += StyledPiece(
                start = line.start + segment.start,
                end = line.start + segment.end,
                style = segment.style,
                outline = guided(piece, path, line),
            )
        }
    }

    /** Bends a piece onto the layer's guide path, if it has one. */
    private fun guided(piece: Path, path: TextPath?, line: TextLine): Path = when (path) {
        null -> piece
        is TextPath.Arc -> warp(piece, arcPath(path, line.width), line)
        is TextPath.Custom -> warp(piece, customPath(path), line)
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

        /**
         * How far adjacent colour bands overlap, in pixels.
         *
         * Half a pixel each side. Two antialiased fills meeting on an exact edge each cover about
         * half of the shared pixel column, and half plus half of the *background* is a pale line
         * down the middle of a word — the artefact reads as a rendering fault, not as a colour
         * change. Overlapping costs a hairline of the later colour and nothing else.
         */
        const val SEAM = 0.5f
    }

    private fun customPath(custom: TextPath.Custom): Path = Path().apply {
        custom.points.forEachIndexed { i, p ->
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        if (custom.closed) close()
    }
}