package ir.pixellab.engine.android

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import ir.pixellab.core.ai.FaceLandmarks
import ir.pixellab.core.ai.FaceModel
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2

/**
 * The thirty lines that need a model file and a phone.
 *
 * Everything that decides what a lip *is*, or what "slim the face" means, is pure geometry in
 * `core:ai` and runs in every test. This is the other half: pixels in, mesh points out, nothing else.
 * Keeping the boundary exactly here is what stops the face features from being a branch that is only
 * exercised on a device nobody runs tests on.
 *
 * The model is **bundled in the APK**, not downloaded. It is 3.7 MB and Apache-2.0 licensed (see
 * `docs/licenses/README.md`), and an app that claims to work offline cannot have a feature whose
 * first use needs a network. Nothing about a detection leaves the device — this is the whole reason
 * an on-device model does not contradict the project's own offline rule.
 */
class MediaPipeFaceModel private constructor(
    private val landmarker: FaceLandmarker,
) : FaceModel, AutoCloseable {

    override fun detect(pixels: IntArray, width: Int, height: Int): List<FaceLandmarks> {
        require(pixels.size >= width * height) { "pixels do not match ${width}x$height" }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val result = try {
            landmarker.detect(BitmapImageBuilder(bitmap).build())
        } finally {
            bitmap.recycle()
        }

        return result.faceLandmarks().map { mesh ->
            // MediaPipe returns normalised coordinates; every consumer here works in image pixels,
            // and converting once at the boundary is what keeps the rest of the code free of a
            // "which space is this in?" question.
            val points = mesh.map { Vec2(it.x() * width, it.y() * height) }
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (p in points) {
                if (p.x < left) left = p.x
                if (p.y < top) top = p.y
                if (p.x > right) right = p.x
                if (p.y > bottom) bottom = p.y
            }
            FaceLandmarks(points, Rect(left, top, right, bottom))
        }
            // Largest first, so "the face" means the subject rather than whoever the detector
            // happened to list first — which on a group photograph is arbitrary.
            .sortedByDescending { (it.bounds.right - it.bounds.left) * (it.bounds.bottom - it.bounds.top) }
    }

    override fun close() = landmarker.close()

    companion object {

        /** Where the bundled model lives inside the APK's assets. */
        const val ASSET_PATH = "models/face_landmarker.task"

        /**
         * Loads the bundled detector, or returns null if it cannot be created.
         *
         * Null rather than an exception because a missing or unloadable model is not a programming
         * error — it is a device this build does not work on, and every face tool already has to
         * handle "no face found" anyway. The call site shows a message; it does not crash.
         *
         * @param maxFaces how many faces to return. More than a handful is a group photograph, where
         *   per-face retouching stops being a sensible interface anyway.
         */
        fun create(context: Context, maxFaces: Int = DEFAULT_MAX_FACES): MediaPipeFaceModel? = try {
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(ASSET_PATH).build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(maxFaces)
                // The 52 blendshape coefficients describe expression, which nothing here uses; the
                // transform matrices are for putting 3D objects on a face, which is a different
                // feature. Both cost time per detection, so both stay off.
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(false)
                .build()
            MediaPipeFaceModel(FaceLandmarker.createFromOptions(context, options))
        } catch (expected: RuntimeException) {
            // MediaPipe wraps every load failure — missing asset, unsupported ABI, no native
            // library — in an unchecked exception, so this is the only place they can be caught.
            null
        }

        private const val DEFAULT_MAX_FACES = 4
    }
}
