package ir.pixellab.core.editor

import ir.pixellab.core.model.Vec2

/**
 * Where the existing artwork sits when the canvas changes size.
 *
 * The nine-cell grid from Photoshop's Canvas Size dialog. It is drawn as a grid rather than offered
 * as two dropdowns because the choice is spatial: the user is pointing at where their work should
 * end up, and every alternative wording of "top, vertically" has to be read twice.
 */
enum class CanvasAnchor(
    /** 0 keeps the artwork against the leading edge, 0.5 centres it, 1 pushes it to the far edge. */
    private val horizontal: Float,
    private val vertical: Float,
) {
    TOP_LEFT(0f, 0f),
    TOP(0.5f, 0f),
    TOP_RIGHT(1f, 0f),
    LEFT(0f, 0.5f),
    CENTER(0.5f, 0.5f),
    RIGHT(1f, 0.5f),
    BOTTOM_LEFT(0f, 1f),
    BOTTOM(0.5f, 1f),
    BOTTOM_RIGHT(1f, 1f),
    ;

    /**
     * How far everything on the canvas moves for this resize.
     *
     * A fraction of the *difference*, which is what makes one rule cover both directions: growing a
     * canvas from the centre adds half the new space on each side, and shrinking it from the centre
     * takes half off each side, with the same arithmetic and no sign test.
     */
    fun offsetFor(oldWidth: Int, oldHeight: Int, newWidth: Int, newHeight: Int): Vec2 = Vec2(
        (newWidth - oldWidth) * horizontal,
        (newHeight - oldHeight) * vertical,
    )

    val persianLabel: String
        get() = when (this) {
            TOP_LEFT -> "بالا چپ"
            TOP -> "بالا"
            TOP_RIGHT -> "بالا راست"
            LEFT -> "چپ"
            CENTER -> "وسط"
            RIGHT -> "راست"
            BOTTOM_LEFT -> "پایین چپ"
            BOTTOM -> "پایین"
            BOTTOM_RIGHT -> "پایین راست"
        }
}
