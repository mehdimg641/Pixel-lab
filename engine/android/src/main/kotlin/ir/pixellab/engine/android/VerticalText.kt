package ir.pixellab.engine.android

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import ir.pixellab.core.model.WritingMode
import java.text.BreakIterator

/**
 * Turning a stack of horizontal lines into a row of vertical columns.
 *
 * ### Why this is a separate file rather than a branch inside the rasteriser
 *
 * [TextRasterizer] has one guarantee it protects above the others: the outline of a horizontal
 * layer comes from one code path and one only, so that adding a feature provably cannot change the
 * shape of a letter in a document that does not use it. Threading a writing mode through
 * `buildOutline`, `bandPieces`, `laidOutPieces` and `buildBackground` would put four new branches
 * inside that path.
 *
 * So nothing here is reachable unless the paragraph asks for it. A horizontal layer never calls
 * into this file at all.
 *
 * ### The column order
 *
 * Columns advance **right to left** — the first line of the paragraph is the rightmost column.
 * That is the convention for Persian and Arabic and it is also the CJK one, so there is no
 * direction to resolve and no argument to have. It is the opposite of what a naive implementation
 * produces, which is the reason it is written down.
 *
 * ### Two modes, two shapes of answer
 *
 * [WritingMode.VERTICAL_ROTATED] is a rigid motion: the line is shaped exactly as a horizontal line
 * is, then turned. So it hands back a *matrix per line*, and anything else the rasteriser built
 * from that same layout — a styled stretch, the panel behind the words — goes through the identical
 * matrix and lands in the right place by construction.
 *
 * [WritingMode.VERTICAL_STACKED] is not a motion at all; every cluster is re-placed. There is no
 * matrix that expresses it, so pretending otherwise would put styled ranges and backgrounds
 * silently in the wrong place. It hands back a *placement per cluster* instead, and the rasteriser
 * builds the whole outline and any styled stretch of it from that one list — the same guarantee the
 * horizontal path has, kept inside this mode rather than borrowed from it.
 */
internal object VerticalText {

    /** Where one grapheme cluster ended up, and which part of the string it is. */
    data class Placement(
        /** Range in the shaped string, so a style run can be clipped to it. */
        val start: Int,
        val end: Int,
        val text: String,
        /** Left edge of the cluster's ink, in the base paint. */
        val x: Float,
        /** Its advance in the base paint, kept so a restyled copy can be re-centred on the axis. */
        val width: Float,
        val baseline: Float,
    )

    /** A vertical layout: the outline, and enough to rebuild any part of it in another paint. */
    data class Vertical(
        val outline: Path,
        /** One per input line, for [WritingMode.VERTICAL_ROTATED]; empty for the stacked mode. */
        val perLine: List<Matrix>,
        /** One per cluster, for [WritingMode.VERTICAL_STACKED]; empty for the rotated mode. */
        val placements: List<Placement>,
        /** One box per column, in input order — the panel behind a column is built from these. */
        val columns: List<RectF>,
    )

    /**
     * Lays the lines out as columns.
     *
     * @param lines the horizontal layout the rasteriser already produced, unmodified
     * @param advance the distance from one column to the next, and from one stacked cluster to the
     *   next: the em box scaled by the paragraph's line height, so that turning a layer vertical and
     *   back leaves its spacing where it was
     */
    fun layout(paint: Paint, lines: List<TextLine>, mode: WritingMode, advance: Float): Vertical =
        when (mode) {
            WritingMode.VERTICAL_ROTATED -> rotated(paint, lines, advance)
            WritingMode.VERTICAL_STACKED -> stacked(paint, lines, advance)
            WritingMode.HORIZONTAL -> error("VerticalText.layout called on a horizontal layer")
        }

    /**
     * Shaped horizontally, then turned a quarter turn clockwise.
     *
     * **Joining survives**, and that is the whole reason this mode exists: the letters were
     * connected in the geometry before the matrix touched them, so the connections turn with them.
     *
     * Android rotates about the origin and maps `(x, y)` to `(-y, x)`, so the line's leftmost letter
     * lands at the top — reading downward — and the baseline, which ran along `y = baseline`,
     * becomes the vertical line `x = -baseline`. The translation puts the *em box* centre on the
     * column axis rather than the baseline: hanging a column off its baseline leaves the ascenders
     * crowding one side of it, which reads as a column drawn off-centre.
     */
    private fun rotated(paint: Paint, lines: List<TextLine>, advance: Float): Vertical {
        val out = Path()
        val matrices = ArrayList<Matrix>(lines.size)
        val boxes = ArrayList<RectF>(lines.size)
        val metrics = paint.fontMetrics
        val blockWidth = advance * lines.size

        for ((index, line) in lines.withIndex()) {
            val axis = blockWidth - (index + 0.5f) * advance
            val matrix = Matrix()
            matrix.setRotate(ROTATION)
            matrix.postTranslate(axis + line.baseline + (metrics.ascent + metrics.descent) / 2f, 0f)
            matrices += matrix

            if (line.text.isNotEmpty()) {
                val straight = Path()
                paint.getTextPath(line.text, 0, line.text.length, line.x, line.baseline, straight)
                straight.transform(matrix)
                out.addPath(straight)
            }
            // The column is as long as the line was wide, offset by the same alignment the
            // horizontal layout resolved — which after the turn is alignment along the column.
            boxes += RectF(axis - advance / 2f, line.x, axis + advance / 2f, line.x + line.width)
        }
        return Vertical(out, matrices, emptyList(), boxes)
    }

    /**
     * Each grapheme cluster upright, below the one before it.
     *
     * **Joining cannot survive this** and the letters come out isolated. That is the look this mode
     * is for; see [WritingMode.VERTICAL_STACKED].
     *
     * Split on grapheme clusters and not on code points, because `text.map { it.toString() }`
     * separates a surrogate pair and tears a combining mark off the letter it belongs to — both of
     * which appear immediately in vowel-marked Persian.
     *
     * Clusters are centred on the column axis. Hung from a common left edge instead, a stack of
     * letters of differing widths reads as a ragged margin rather than as a column, which is the
     * only thing anyone picks this mode to get.
     */
    private fun stacked(paint: Paint, lines: List<TextLine>, advance: Float): Vertical {
        val out = Path()
        val placements = ArrayList<Placement>()
        val boxes = ArrayList<RectF>(lines.size)
        val metrics = paint.fontMetrics
        val blockWidth = advance * lines.size

        for ((index, line) in lines.withIndex()) {
            val axis = blockWidth - (index + 0.5f) * advance
            var baseline = -metrics.ascent
            var cursor = line.start

            for (cluster in clusters(line.text)) {
                val end = cursor + cluster.length
                if (cluster.isBlank()) {
                    // Whitespace still advances. A column with its gaps squeezed out is a different
                    // string from the one that was typed.
                    baseline += advance
                    cursor = end
                    continue
                }
                val width = paint.measureText(cluster)
                val x = axis - width / 2f
                placements += Placement(cursor, end, cluster, x, width, baseline)
                out.addPath(outlineOf(paint, cluster, x, baseline))
                baseline += advance
                cursor = end
            }
            boxes += RectF(axis - advance / 2f, 0f, axis + advance / 2f, baseline - metrics.ascent)
        }
        return Vertical(out, emptyList(), placements, boxes)
    }

    /**
     * The outline of the clusters overlapping a range, in whatever paint the caller hands in.
     *
     * This is how a styled stretch is built in the stacked mode, and it reads the *same* placement
     * list the full outline was built from — so a range cannot land anywhere except on top of the
     * letters it names, whatever the style does to their width.
     */
    fun outlineOfRange(
        paint: Paint,
        placements: List<Placement>,
        start: Int,
        end: Int,
        shift: Float = 0f,
    ): Path {
        val out = Path()
        for (placement in placements) {
            if (placement.end <= start || placement.start >= end) continue
            // Re-centred in the caller's paint. A range set larger is a wider cluster, and drawing
            // it at the base paint's left edge would hang it off the column axis by half the
            // difference — visible the moment anyone sets one letter of a column bigger.
            val axis = placement.x + placement.width / 2f
            val x = axis - paint.measureText(placement.text) / 2f
            out.addPath(outlineOf(paint, placement.text, x, placement.baseline + shift))
        }
        return out
    }

    private fun outlineOf(paint: Paint, text: String, x: Float, baseline: Float): Path =
        Path().apply { paint.getTextPath(text, 0, text.length, x, baseline, this) }

    private fun clusters(text: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val out = ArrayList<String>(text.length)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            out += text.substring(start, end)
            start = end
            end = iterator.next()
        }
        return out
    }

    /**
     * The distance from one column to the next, and one stacked cluster to the next.
     *
     * The em box rather than the ink, scaled by the same line-height multiplier a horizontal
     * paragraph uses. Sizing to the ink instead makes a column of letters ripple, for the same
     * reason it makes a stack of background panels ripple.
     */
    fun advance(paint: Paint, lineHeight: Float): Float {
        val metrics = paint.fontMetrics
        return (metrics.descent - metrics.ascent) * lineHeight
    }

    private const val ROTATION = 90f
}
