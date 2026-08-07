package ir.pixellab.core.model

import kotlinx.serialization.Serializable

/**
 * A ruler guide the user placed.
 *
 * Part of the **document**, not of the view, and that is deliberate: a guide marks something about
 * the design — a margin, a fold, where the logo goes — so it has to survive being saved and come
 * back when the file is reopened. Photoshop stores guides in the PSD for the same reason. The grid,
 * by contrast, is a preference about how you like to work and is not saved with the artwork.
 *
 * @param vertical true for a vertical line at x = [position]; false for a horizontal line at y.
 */
@Serializable
data class Guide(
    val vertical: Boolean,
    val position: Float,
    /** A locked guide still snaps but cannot be dragged, so it cannot be nudged away by accident. */
    val locked: Boolean = false,
)
