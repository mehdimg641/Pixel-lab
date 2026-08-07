package ir.pixellab.core.editor

import ir.pixellab.core.model.Rect

/**
 * A fixed proportion for a crop frame, and the two things anyone ever does with one.
 *
 * `Editor.cropCanvas(rect)` has taken a rectangle since wave 1, and until now the only rectangle
 * anything could hand it was a selection's bounding box — so a user who wanted a square, a story or
 * a post had to draw one by eye and accept whatever they got. That is the single most-used operation
 * in every photo app on the reference list and it was the one we did not have.
 *
 * Photoshop calls this the crop tool's ratio, and the same thing again as the rectangular marquee's
 * *Fixed Ratio* style. Both are this class: a proportion, plus [fit] for "give me the biggest one" and
 * [constrain] for "keep what I dragged, but make it this shape".
 */
data class AspectRatio(val width: Float, val height: Float) {

    init {
        require(width > 0f && height > 0f) { "an aspect ratio needs positive sides, got ${width}x$height" }
    }

    /** Width divided by height. Greater than one is landscape. */
    val value: Float get() = width / height

    /**
     * The largest rectangle of this proportion that fits inside [bounds], centred.
     *
     * Centred rather than anchored top-left because a crop frame is a *proposal* — the user is about
     * to drag it — and the centre is the only starting point that is equally wrong in both
     * directions. Anchoring it would systematically favour one edge of every photograph.
     */
    fun fit(bounds: Rect): Rect {
        val available = bounds.width / bounds.height
        val w: Float
        val h: Float
        if (available > value) {
            // The space is wider than the ratio wants, so height is what runs out first.
            h = bounds.height
            w = h * value
        } else {
            w = bounds.width
            h = w / value
        }
        val cx = (bounds.left + bounds.right) / 2f
        val cy = (bounds.top + bounds.bottom) / 2f
        return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /**
     * Reshapes [rect] to this proportion about its own centre, then keeps it inside [bounds].
     *
     * **Shrinks to fit, never grows.** A frame corrected outwards would jump out from under the
     * finger that is dragging it, and on the last few pixels of a drag towards a corner it would keep
     * pushing back — the interaction reads as a fight. Taking the smaller of the two candidate sizes
     * means the frame only ever settles inside what was asked for.
     *
     * Clamping is a translation, not a second reshape, so a frame pushed against an edge slides along
     * it at the right proportion instead of quietly becoming a different shape.
     */
    fun constrain(rect: Rect, bounds: Rect? = null): Rect {
        val w = minOf(rect.width, rect.height * value)
        val h = w / value
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        var out = Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        if (bounds != null) {
            // Too big for the canvas at all: fall back to the biggest one that does fit, rather than
            // returning something that hangs off two opposite edges and cannot be slid back.
            if (out.width > bounds.width || out.height > bounds.height) return fit(bounds)
            var dx = 0f
            var dy = 0f
            if (out.left < bounds.left) dx = bounds.left - out.left
            if (out.right > bounds.right) dx = bounds.right - out.right
            if (out.top < bounds.top) dy = bounds.top - out.top
            if (out.bottom > bounds.bottom) dy = bounds.bottom - out.bottom
            out = Rect(out.left + dx, out.top + dy, out.right + dx, out.bottom + dy)
        }
        return out
    }

    /** The same proportion the other way up — the button every crop interface has. */
    fun flipped() = AspectRatio(height, width)

    companion object {
        val SQUARE = AspectRatio(1f, 1f)

        /**
         * The ratios worth a button, and no others.
         *
         * These are not arbitrary: 4:5 and 1:1 are what a feed post is cropped to, 9:16 is a story,
         * 16:9 is a video frame and a desktop wallpaper, and 2:3, 3:4 and their inverses are what
         * cameras and print sizes actually produce. A menu of thirty would be worse than this at the
         * only thing it has to do, which is being pressed without reading.
         */
        val PRESETS: List<Pair<String, AspectRatio>> = listOf(
            "۱:۱" to SQUARE,
            "۴:۵" to AspectRatio(4f, 5f),
            "۵:۴" to AspectRatio(5f, 4f),
            "۲:۳" to AspectRatio(2f, 3f),
            "۳:۲" to AspectRatio(3f, 2f),
            "۳:۴" to AspectRatio(3f, 4f),
            "۴:۳" to AspectRatio(4f, 3f),
            "۹:۱۶" to AspectRatio(9f, 16f),
            "۱۶:۹" to AspectRatio(16f, 9f),
        )
    }
}
