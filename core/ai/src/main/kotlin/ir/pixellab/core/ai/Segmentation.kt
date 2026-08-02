package ir.pixellab.core.ai

import ir.pixellab.core.paint.PixelSelection
import ir.pixellab.core.paint.SubjectSelection

/**
 * Cutting a subject out, whether or not a neural model is present.
 *
 * The design constraint here comes from two things the user said that pull in opposite directions:
 * everything should work offline and without AI where possible, and background removal should reach
 * Photoshop's quality including hair. A segmentation model is genuinely better at deciding *what*
 * the subject is; it is not better at the edge, where the refinement pass does the work.
 *
 * So this is a seam rather than a dependency. The app ships with the classical path and works on the
 * first day with no download. If a model file is placed beside it, the same call uses it instead —
 * with no change to any caller and no code path that only exists when a model is present. A branch
 * that is only exercised once someone has installed a 176 MB file is a branch that is never tested.
 */
interface SegmentationModel {

    /** A name for the settings screen, so a user can see which model is answering. */
    val name: String

    /**
     * Per-pixel confidence that the pixel is subject, 0..255, at the model's own resolution.
     *
     * Returned at the model's resolution rather than the image's, because every one of these
     * networks runs at a fixed small size — 320 or 512 square — and pretending otherwise would hide
     * the upsample that has to happen, which is exactly where a mask goes soft.
     */
    fun infer(pixels: IntArray, width: Int, height: Int): Mask?

    /** The size the model runs at, so a caller can scale once rather than twice. */
    val inputSize: Int

    data class Mask(val width: Int, val height: Int, val confidence: ByteArray) {
        init {
            require(confidence.size == width * height) { "mask does not match ${width}x$height" }
        }

        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }
}

/**
 * Picks the subject, using a model when one is available and the classical path when it is not.
 *
 * Both paths end in the same place: a coverage mask that the edge refinement then works on. That
 * shared ending is the point. It means the quality of a cut-out's *edge* — the part that decides
 * whether hair looks like hair — does not depend on whether a model was installed, and it means the
 * classical path is exercised by every test rather than being a fallback nobody runs.
 */
class SubjectCutout(private val model: SegmentationModel? = null) {

    val usingModel: Boolean get() = model != null

    val describe: String get() = model?.name ?: "کلاسیک — بدون مدل"

    /**
     * @param sensitivity 0..1, higher takes more of the image. Applied to both paths, because a
     *   user who nudges it after a model run expects the same control to do the same thing.
     */
    fun select(
        pixels: IntArray,
        width: Int,
        height: Int,
        sensitivity: Float = 0.5f,
    ): PixelSelection {
        require(pixels.size == width * height) { "expected ${width * height} pixels, got ${pixels.size}" }

        val inferred = model?.runCatching { infer(pixels, width, height) }?.getOrNull()
        // Null covers three different failures — no model, a model that threw, and a model that
        // declined — and all three want the same answer. Distinguishing them here would put three
        // branches in the caller for one outcome.
        if (inferred == null) return SubjectSelection.select(pixels, width, height, sensitivity)

        return upsample(inferred, width, height, sensitivity)
    }

    /**
     * Scales the model's small mask up to the image, with a threshold applied after.
     *
     * Bilinear, and the threshold applied *after* the scaling rather than before. Thresholding at
     * the model's resolution turns the mask into hard 320-pixel blocks and then smooths the blocks,
     * which produces a visibly stepped edge that no amount of refinement recovers. Scaling the
     * confidence and thresholding at full resolution keeps the sub-pixel information the model
     * actually produced.
     */
    internal fun upsample(
        mask: SegmentationModel.Mask,
        width: Int,
        height: Int,
        sensitivity: Float,
    ): PixelSelection {
        val coverage = ByteArray(width * height)
        val scaleX = mask.width.toFloat() / width
        val scaleY = mask.height.toFloat() / height
        // Higher sensitivity takes more, so it lowers the bar a pixel has to clear.
        val threshold = (1f - sensitivity.coerceIn(0f, 1f)) * FULL

        for (y in 0 until height) {
            val sy = (y + HALF) * scaleY - HALF
            for (x in 0 until width) {
                val sx = (x + HALF) * scaleX - HALF
                val value = sample(mask, sx, sy)
                // Kept as a soft value rather than driven to 0 or 255: partial coverage at the
                // boundary is what the refinement pass has to work with, and a hard mask hands it
                // nothing to refine.
                coverage[y * width + x] = when {
                    value <= threshold -> 0
                    else -> ((value - threshold) / (FULL - threshold) * FULL).toInt()
                        .coerceIn(0, 255).toByte()
                }
            }
        }
        return PixelSelection(width, height, coverage)
    }

    private fun sample(mask: SegmentationModel.Mask, x: Float, y: Float): Float {
        val x0 = kotlin.math.floor(x).toInt().coerceIn(0, mask.width - 1)
        val y0 = kotlin.math.floor(y).toInt().coerceIn(0, mask.height - 1)
        val x1 = (x0 + 1).coerceAtMost(mask.width - 1)
        val y1 = (y0 + 1).coerceAtMost(mask.height - 1)
        val tx = (x - x0).coerceIn(0f, 1f)
        val ty = (y - y0).coerceIn(0f, 1f)

        fun at(px: Int, py: Int) = (mask.confidence[py * mask.width + px].toInt() and 0xFF).toFloat()

        val top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * tx
        val bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * tx
        return top + (bottom - top) * ty
    }

    private companion object {
        const val FULL = 255f
        const val HALF = 0.5f
    }
}
