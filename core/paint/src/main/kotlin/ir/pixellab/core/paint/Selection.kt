package ir.pixellab.core.paint

import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a new region combines with the selection already there. */
enum class SelectionMode { REPLACE, ADD, SUBTRACT, INTERSECT }

/**
 * A pixel selection, as coverage rather than as a shape.
 *
 * One byte a pixel, not a bitmask. That is the whole difference between a selection that can be
 * feathered, antialiased and grown from a magic wand and one that can only ever have a staircase
 * edge — and a hard-edged selection is exactly what makes a cut-out look pasted on.
 *
 * [bounds] is tracked so a caller can skip work: a selection is usually a small part of a large
 * canvas, and a paint or a filter that walks the whole buffer to change a few thousand pixels is the
 * difference between a slider that tracks the finger and one that does not.
 */
class PixelSelection(val width: Int, val height: Int, val coverage: ByteArray = ByteArray(width * height)) {

    init {
        require(width > 0 && height > 0) { "a selection needs a positive size, got ${width}x$height" }
        require(coverage.size == width * height) { "coverage does not match ${width}x$height" }
    }

    /** The smallest box holding everything selected, or null when nothing is. */
    var bounds: Rect? = null
        private set

    init {
        recomputeBounds()
    }

    val isEmpty: Boolean get() = bounds == null

    operator fun get(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) 0 else coverage[y * width + x].toInt() and 0xFF

    /** How many pixels are selected, counting partial coverage as a fraction. */
    fun selectedArea(): Double {
        var total = 0.0
        for (byte in coverage) total += (byte.toInt() and 0xFF) / 255.0
        return total
    }

    fun copy(): PixelSelection = PixelSelection(width, height, coverage.copyOf())

    // ---- algebra ---------------------------------------------------------------------------

    /**
     * Combines another region into this one.
     *
     * The four modes are the four buttons every selection tool has, and they are not sugar: building
     * a selection is almost always several passes — a wand for the sky, subtract for the branch that
     * came with it, add for the piece it missed.
     */
    fun combine(other: PixelSelection, mode: SelectionMode): PixelSelection {
        require(other.width == width && other.height == height) { "selections differ in size" }
        val out = when (mode) {
            SelectionMode.REPLACE -> other.coverage.copyOf()
            SelectionMode.ADD -> ByteArray(coverage.size) {
                max(coverage[it].toInt() and 0xFF, other.coverage[it].toInt() and 0xFF).toByte()
            }
            SelectionMode.SUBTRACT -> ByteArray(coverage.size) {
                val keep = (coverage[it].toInt() and 0xFF) * (255 - (other.coverage[it].toInt() and 0xFF)) / 255
                keep.toByte()
            }
            SelectionMode.INTERSECT -> ByteArray(coverage.size) {
                val both = (coverage[it].toInt() and 0xFF) * (other.coverage[it].toInt() and 0xFF) / 255
                both.toByte()
            }
        }
        return PixelSelection(width, height, out)
    }

    fun inverted(): PixelSelection =
        PixelSelection(width, height, ByteArray(coverage.size) { (255 - (coverage[it].toInt() and 0xFF)).toByte() })

    /**
     * Grows or shrinks the edge by [pixels].
     *
     * A chamfer distance transform rather than repeated dilation: repeating a 3×3 dilation n times
     * costs n passes over the region and produces a diamond, not a circle, so expanding a round
     * selection by twenty pixels visibly turns it into an octagon.
     */
    fun grown(pixels: Int): PixelSelection {
        if (pixels == 0) return copy()
        val expanding = pixels > 0
        // Distance to the far side of the edge: expanding asks every unselected pixel how far the
        // selection is, shrinking asks every selected pixel how far the outside is.
        val distance = distanceField(fromSelected = expanding)
        // The chamfer counts a straight step as three, so the radius has to be in the same units.
        val radius = abs(pixels) * STRAIGHT
        val out = ByteArray(coverage.size)
        for (i in coverage.indices) {
            val existing = coverage[i].toInt() and 0xFF
            val selected = existing >= HALF
            out[i] = when {
                expanding && !selected && distance[i] <= radius -> 255.toByte()
                !expanding && selected && distance[i] <= radius -> 0
                else -> existing.toByte()
            }
        }
        return PixelSelection(width, height, out)
    }

    /**
     * Softens the edge over [radius] pixels.
     *
     * A separable box blur run three times, which is a close enough Gaussian for a selection and is
     * O(n) in the radius rather than O(n·r). A true Gaussian at radius 50 on a large document is
     * slow enough that the slider stops tracking the finger.
     */
    fun feathered(radius: Float): PixelSelection {
        if (radius <= 0f) return copy()
        var values = FloatArray(coverage.size) { (coverage[it].toInt() and 0xFF).toFloat() }
        val boxRadius = (radius * BOX_TO_GAUSSIAN).roundToInt().coerceAtLeast(1)
        repeat(BOX_PASSES) {
            values = blurRows(values, boxRadius)
            values = blurColumns(values, boxRadius)
        }
        return PixelSelection(
            width,
            height,
            ByteArray(coverage.size) { values[it].roundToInt().coerceIn(0, 255).toByte() },
        )
    }

    // ---- internals -------------------------------------------------------------------------

    private fun recomputeBounds() {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (coverage[row + x].toInt() == 0) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        bounds = if (maxX < 0) {
            null
        } else {
            Rect(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
        }
    }

    /**
     * Distance from every pixel to the nearest selected (or unselected) one.
     *
     * Two-pass chamfer with 3-4 weights: exact enough that a circle stays a circle, and one pass
     * forwards plus one backwards rather than the log(n) of a jump flood, which is the right trade
     * on a CPU.
     */
    private fun distanceField(fromSelected: Boolean): FloatArray {
        val far = (width + height).toFloat()
        val distance = FloatArray(coverage.size) { i ->
            val selected = (coverage[i].toInt() and 0xFF) >= HALF
            if (selected == fromSelected) 0f else far
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                var d = distance[i]
                if (x > 0) d = min(d, distance[i - 1] + STRAIGHT)
                if (y > 0) d = min(d, distance[i - width] + STRAIGHT)
                if (x > 0 && y > 0) d = min(d, distance[i - width - 1] + DIAGONAL)
                if (x < width - 1 && y > 0) d = min(d, distance[i - width + 1] + DIAGONAL)
                distance[i] = d
            }
        }
        for (y in height - 1 downTo 0) {
            for (x in width - 1 downTo 0) {
                val i = y * width + x
                var d = distance[i]
                if (x < width - 1) d = min(d, distance[i + 1] + STRAIGHT)
                if (y < height - 1) d = min(d, distance[i + width] + STRAIGHT)
                if (x < width - 1 && y < height - 1) d = min(d, distance[i + width + 1] + DIAGONAL)
                if (x > 0 && y < height - 1) d = min(d, distance[i + width - 1] + DIAGONAL)
                distance[i] = d
            }
        }
        return distance
    }

    private fun blurRows(values: FloatArray, radius: Int): FloatArray {
        val out = FloatArray(values.size)
        val span = (radius * 2 + 1).toFloat()
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (x in -radius..radius) sum += values[row + x.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                out[row + x] = sum / span
                sum += values[row + (x + radius + 1).coerceIn(0, width - 1)]
                sum -= values[row + (x - radius).coerceIn(0, width - 1)]
            }
        }
        return out
    }

    private fun blurColumns(values: FloatArray, radius: Int): FloatArray {
        val out = FloatArray(values.size)
        val span = (radius * 2 + 1).toFloat()
        for (x in 0 until width) {
            var sum = 0f
            for (y in -radius..radius) sum += values[y.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                out[y * width + x] = sum / span
                sum += values[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                sum -= values[(y - radius).coerceIn(0, height - 1) * width + x]
            }
        }
        return out
    }

    companion object {
        private const val HALF = 128
        private const val STRAIGHT = 3f
        private const val DIAGONAL = 4f

        /** Three box passes approximate a Gaussian; this is the radius ratio that matches its sigma. */
        private const val BOX_TO_GAUSSIAN = 1.1f
        private const val BOX_PASSES = 3

        fun everything(width: Int, height: Int) =
            PixelSelection(width, height, ByteArray(width * height) { 255.toByte() })

        fun nothing(width: Int, height: Int) = PixelSelection(width, height)
    }
}

/**
 * Turns a shape into coverage.
 *
 * Antialiased by supersampling the edge, because a selection is almost always used to cut something
 * out and a staircase edge is the single most recognisable sign of a bad composite.
 */
object Marquee {

    fun rectangle(width: Int, height: Int, rect: Rect, feather: Float = 0f): PixelSelection {
        val selection = PixelSelection(width, height, ByteArray(width * height) { i ->
            val x = (i % width).toFloat()
            val y = (i / width).toFloat()
            coverageOf(x, y) { px, py ->
                px >= rect.left && px <= rect.right && py >= rect.top && py <= rect.bottom
            }
        })
        return if (feather > 0f) selection.feathered(feather) else selection
    }

    fun ellipse(width: Int, height: Int, rect: Rect, feather: Float = 0f): PixelSelection {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val rx = (rect.width / 2f).coerceAtLeast(EPSILON)
        val ry = (rect.height / 2f).coerceAtLeast(EPSILON)
        val selection = PixelSelection(width, height, ByteArray(width * height) { i ->
            val x = (i % width).toFloat()
            val y = (i / width).toFloat()
            coverageOf(x, y) { px, py ->
                val dx = (px - cx) / rx
                val dy = (py - cy) / ry
                dx * dx + dy * dy <= 1f
            }
        })
        return if (feather > 0f) selection.feathered(feather) else selection
    }

    /**
     * A freehand or polygonal outline.
     *
     * Even-odd fill, tested at the same subsamples as the other shapes so a lasso's edge matches a
     * marquee's. The polygon is implicitly closed: a lasso the user did not quite finish is the
     * normal case, not an error.
     */
    fun polygon(width: Int, height: Int, points: List<Vec2>, feather: Float = 0f): PixelSelection {
        if (points.size < 3) return PixelSelection.nothing(width, height)
        val selection = PixelSelection(width, height, ByteArray(width * height) { i ->
            val x = (i % width).toFloat()
            val y = (i / width).toFloat()
            coverageOf(x, y) { px, py -> contains(points, px, py) }
        })
        return if (feather > 0f) selection.feathered(feather) else selection
    }

    private inline fun coverageOf(x: Float, y: Float, inside: (Float, Float) -> Boolean): Byte {
        var hits = 0
        for (sy in 0 until SUBSAMPLES) {
            for (sx in 0 until SUBSAMPLES) {
                val px = x + (sx + 0.5f) / SUBSAMPLES
                val py = y + (sy + 0.5f) / SUBSAMPLES
                if (inside(px, py)) hits++
            }
        }
        return (hits * 255 / (SUBSAMPLES * SUBSAMPLES)).toByte()
    }

    private fun contains(points: List<Vec2>, x: Float, y: Float): Boolean {
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Four by four: sixteen samples is where the staircase stops being visible on an edge. */
    private const val SUBSAMPLES = 4
    private const val EPSILON = 0.0001f
}

/**
 * Selects by colour.
 *
 * A scanline flood fill rather than a per-pixel queue: filling the sky of a photograph touches
 * millions of pixels, and a naive four-way queue puts every one of them on a stack. Scanlines push
 * one entry per run instead, which is what makes a wand on a large image feel instant.
 *
 * Tolerance is measured in the same space Photoshop uses — plain RGB distance — because that is what
 * the number on the slider has to mean for a user who already knows the tool.
 */
object MagicWand {

    /**
     * @param contiguous false selects every matching pixel in the image, not just the connected run.
     *   That is Photoshop's "Sample All Layers"-adjacent option and it is the right tool for
     *   picking out one flat colour used all over a design.
     * @param antialias softens the boundary by how close each pixel is to the tolerance, which is
     *   what stops a wand selection from having a jagged edge.
     */
    fun select(
        pixels: IntArray,
        width: Int,
        height: Int,
        at: Vec2,
        tolerance: Float = 32f,
        contiguous: Boolean = true,
        antialias: Boolean = true,
    ): PixelSelection {
        val startX = at.x.toInt()
        val startY = at.y.toInt()
        if (startX !in 0 until width || startY !in 0 until height) {
            return PixelSelection.nothing(width, height)
        }
        val target = pixels[startY * width + startX]
        val coverage = ByteArray(width * height)
        val limit = tolerance.coerceAtLeast(0f)

        if (!contiguous) {
            for (i in pixels.indices) coverage[i] = match(pixels[i], target, limit, antialias)
            return PixelSelection(width, height, coverage)
        }

        val visited = BooleanArray(width * height)
        val stack = ArrayDeque<Int>()
        stack.addLast(startY * width + startX)

        while (stack.isNotEmpty()) {
            val index = stack.removeLast()
            val y = index / width
            var left = index % width

            // Walk to the start of this run, then fill rightwards, pushing only the runs above and
            // below rather than every neighbouring pixel.
            while (left > 0 && !visited[y * width + left - 1] &&
                match(pixels[y * width + left - 1], target, limit, antialias).toInt() != 0
            ) {
                left--
            }
            var x = left
            var spanAbove = false
            var spanBelow = false
            while (x < width) {
                val i = y * width + x
                if (visited[i]) break
                val value = match(pixels[i], target, limit, antialias)
                if (value.toInt() == 0) break
                visited[i] = true
                coverage[i] = value

                if (y > 0) {
                    val above = i - width
                    val hit = !visited[above] && match(pixels[above], target, limit, antialias).toInt() != 0
                    if (hit && !spanAbove) {
                        stack.addLast(above)
                        spanAbove = true
                    } else if (!hit) {
                        spanAbove = false
                    }
                }
                if (y < height - 1) {
                    val below = i + width
                    val hit = !visited[below] && match(pixels[below], target, limit, antialias).toInt() != 0
                    if (hit && !spanBelow) {
                        stack.addLast(below)
                        spanBelow = true
                    } else if (!hit) {
                        spanBelow = false
                    }
                }
                x++
            }
        }
        return PixelSelection(width, height, coverage)
    }

    private fun match(pixel: Int, target: Int, tolerance: Float, antialias: Boolean): Byte {
        val distance = distance(pixel, target)
        if (distance > tolerance) return 0
        if (!antialias || tolerance <= 0f) return 255.toByte()
        // Full inside the middle of the range and falling off over the last quarter, so a flat
        // region stays fully selected and only its boundary softens.
        val soft = ((1f - (distance / tolerance)) / SOFT_BAND).coerceIn(0f, 1f)
        return (soft * 255f).roundToInt().toByte()
    }

    private fun distance(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        val da = ((a ushr 24) and 0xFF) - ((b ushr 24) and 0xFF)
        // Alpha is part of the distance: without it a wand on a transparent area selects every
        // fully transparent pixel regardless of the colour underneath it.
        return sqrt((dr * dr + dg * dg + db * db + da * da).toFloat())
    }

    private const val SOFT_BAND = 0.25f
}
