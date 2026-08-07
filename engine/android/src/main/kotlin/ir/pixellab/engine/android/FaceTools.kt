package ir.pixellab.engine.android

import ir.pixellab.core.ai.FaceLandmarks
import ir.pixellab.core.ai.FaceMask
import ir.pixellab.core.ai.FaceRegion
import ir.pixellab.core.ai.FaceReshape
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.imaging.FaceRetouch
import ir.pixellab.core.imaging.MovingLeastSquares
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec3

/**
 * One pass over a photograph that applies every face setting at once.
 *
 * "At once" is the whole design. Each of these operations on its own is a full-image copy, and a
 * retouch panel with nine live sliders that ran them in sequence would resample and re-copy the
 * picture nine times per drag — and, worse, warp it more than once, which softens a face visibly.
 * So the shape is: gather the settings, do the geometry once, do the colour work in one accumulation.
 *
 * Everything here is arithmetic on masks produced in `core:ai` and pixel operations from
 * `core:imaging`. The only Android in it is [RasterImage].
 */
object FaceTools {

    /**
     * What the retouch panel holds. All zero is the identity, and [isIdentity] says so cheaply so
     * the caller can skip the work entirely rather than producing an identical copy.
     */
    data class Settings(
        val smoothSkin: Float = 0f,
        val reduceShine: Float = 0f,
        val whitenTeeth: Float = 0f,
        val brightenEyes: Float = 0f,
        val removeRedEye: Boolean = false,
        val lipstick: Color? = null,
        val lipstickStrength: Float = DEFAULT_MAKEUP,
        val blush: Color? = null,
        val blushStrength: Float = DEFAULT_BLUSH,
        val eyeshadow: Color? = null,
        val eyeshadowStrength: Float = DEFAULT_MAKEUP,
        val brows: Color? = null,
        val browStrength: Float = DEFAULT_MAKEUP,
        val reshape: Map<FaceReshape.Adjustment, Float> = emptyMap(),
    ) {
        val isIdentity: Boolean
            get() = smoothSkin == 0f && reduceShine == 0f && whitenTeeth == 0f &&
                brightenEyes == 0f && !removeRedEye &&
                lipstick == null && blush == null && eyeshadow == null && brows == null &&
                reshape.values.none { it != 0f }
    }

    /**
     * Applies [settings] to every face in [faces].
     *
     * Colour work first, geometry last. The order matters: a warp resamples, and resampling a
     * lipstick edge that has already been feathered is softer than feathering it after the warp —
     * but re-detecting the mesh after a warp would cost a second inference per drag. Colour-then-warp
     * is the trade every retouch app makes, and at these displacements the difference is invisible.
     */
    fun apply(source: RasterImage, faces: List<FaceLandmarks>, settings: Settings): RasterImage {
        if (faces.isEmpty() || settings.isIdentity) return source

        var raster = source.toRaster()
        val width = source.width
        val height = source.height

        for (face in faces) {
            // Skin: everything inside the face outline except the features, which must stay sharp.
            if (settings.smoothSkin > 0f || settings.reduceShine > 0f) {
                val skin = skinMask(face, width, height)
                if (settings.smoothSkin > 0f) {
                    raster = FaceRetouch.smoothSkin(raster, skin, settings.smoothSkin)
                }
                if (settings.reduceShine > 0f) {
                    raster = FaceRetouch.reduceShine(raster, skin, settings.reduceShine)
                }
            }

            settings.lipstick?.let {
                val mask = FaceMask.of(face, FaceRegion.LIPS, width, height)
                raster = FaceRetouch.tint(raster, mask, it.toVec3(), settings.lipstickStrength)
            }

            settings.blush?.let {
                // Grown well past the cheek contour and heavily feathered — blusher has no edge, and
                // a blush with a visible boundary is the most obvious tell in digital makeup.
                for (region in listOf(FaceRegion.LEFT_CHEEK, FaceRegion.RIGHT_CHEEK)) {
                    val mask = FaceMask.of(face, region, width, height, feather = BLUSH_FEATHER, grow = BLUSH_GROW)
                    raster = FaceRetouch.tint(raster, mask, it.toVec3(), settings.blushStrength)
                }
            }

            settings.eyeshadow?.let {
                for (region in listOf(FaceRegion.LEFT_LID, FaceRegion.RIGHT_LID)) {
                    val mask = FaceMask.of(face, region, width, height, feather = LID_FEATHER)
                    raster = FaceRetouch.tint(raster, mask, it.toVec3(), settings.eyeshadowStrength)
                }
            }

            settings.brows?.let {
                for (region in listOf(FaceRegion.LEFT_BROW, FaceRegion.RIGHT_BROW)) {
                    val mask = FaceMask.of(face, region, width, height)
                    raster = FaceRetouch.tint(raster, mask, it.toVec3(), settings.browStrength)
                }
            }

            if (settings.whitenTeeth > 0f) {
                val mask = FaceMask.of(face, FaceRegion.MOUTH_INNER, width, height)
                raster = FaceRetouch.whiten(raster, mask, settings.whitenTeeth)
            }

            if (settings.brightenEyes > 0f || settings.removeRedEye) {
                for (region in listOf(FaceRegion.LEFT_EYE, FaceRegion.RIGHT_EYE)) {
                    val mask = FaceMask.of(face, region, width, height)
                    if (settings.brightenEyes > 0f) {
                        raster = FaceRetouch.brighten(raster, mask, settings.brightenEyes)
                    }
                    if (settings.removeRedEye) {
                        raster = FaceRetouch.removeRedEye(raster, mask)
                    }
                }
            }
        }

        // One warp for every face and every slider together, so the picture is resampled once.
        val bounds = Rect(0f, 0f, width.toFloat(), height.toFloat())
        val controls = ArrayList<MovingLeastSquares.ControlPoint>()
        for (face in faces) {
            controls += FaceReshape.controlPoints(face, settings.reshape, bounds)
        }
        if (controls.isNotEmpty()) {
            raster = MovingLeastSquares.deform(raster, controls, MovingLeastSquares.Mode.RIGID)
        }
        return raster.toImage()
    }

    /**
     * Skin: inside the face, outside the features.
     *
     * Subtracting the eyes, lips and brows is what separates "smooth skin" from "a soft photograph".
     * Eyelashes and a lip line are the highest-frequency detail on a face, and smoothing them is
     * exactly what makes an over-retouched portrait look like plastic.
     */
    fun skinMask(face: FaceLandmarks, width: Int, height: Int): FloatArray {
        val skin = FaceMask.of(face, FaceRegion.FACE, width, height, feather = FACE_FEATHER)
        for (region in FEATURES) {
            // Grown before subtracting, so the smoothing stops short of the feature rather than
            // right at it — a sharp eye ringed by a smoothed halo is worse than either.
            val cut = FaceMask.of(face, region, width, height, feather = FEATURE_FEATHER, grow = FEATURE_GROW)
            for (i in skin.indices) skin[i] *= (1f - cut[i])
        }
        return skin
    }

    private fun Color.toVec3() = Vec3(r, g, b)

    private val FEATURES = listOf(
        FaceRegion.LEFT_EYE, FaceRegion.RIGHT_EYE,
        FaceRegion.LEFT_BROW, FaceRegion.RIGHT_BROW,
        FaceRegion.LIPS,
    )

    /** Softer than a lip line: the edge of a face against hair is not a sharp boundary either. */
    private const val FACE_FEATHER = 0.03f
    private const val FEATURE_FEATHER = 0.02f
    private const val FEATURE_GROW = 0.012f

    private const val BLUSH_FEATHER = 0.08f
    private const val BLUSH_GROW = 0.03f
    private const val LID_FEATHER = 0.02f

    private const val DEFAULT_MAKEUP = 0.5f

    /** Blusher is the one that reads as fake first, so its default is the most restrained. */
    private const val DEFAULT_BLUSH = 0.3f
}
