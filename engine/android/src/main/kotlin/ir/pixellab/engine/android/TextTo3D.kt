package ir.pixellab.engine.android

import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.mesh.Extruder
import ir.pixellab.core.mesh.MarkStyle
import ir.pixellab.core.mesh.FaceTexture
import ir.pixellab.core.mesh.Mesh
import ir.pixellab.core.mesh.Rasteriser
import ir.pixellab.core.model.Geometry3D
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.vector.PathMath

/**
 * Turns a text layer into a rendered three-dimensional letter.
 *
 * This is the app's reason to exist, so it is worth saying plainly what it is not. The reference
 * PSDs fake 3D text by duplicating a smart object twenty-nine times, each one offset a pixel. That
 * trick cannot do two things this does, and they are the two things the eye actually reads depth
 * from: the bevel catches a specular highlight that *moves* as the letter turns, and the side walls
 * fall off towards the back. In a stack every copy is lit identically, so the result reads as a
 * thick sticker however many copies are in it.
 *
 * The render is a bake. A cover's 3D text is set once and then worked around for an hour, so baking
 * is the right shape for the task rather than a compromise — and it means the letter can be
 * re-rendered at export resolution instead of at whatever the preview surface happened to be.
 */
object TextTo3D {

    fun canRender(layer: Layer, fonts: FontResolver): Boolean =
        layer is Layer.Text && layer.spec.text.isNotBlank() && fonts.resolve(layer.spec.font) != null

    /**
     * Turns the face's pattern into pixels, if it has one.
     *
     * **This is where the two ways of building a title finally meet.** Until now a mesh face could
     * only take a gradient, so a painted or photographed face meant giving up real geometry — no
     * perspective, no lit walls — and real geometry meant giving up the paint. Every commercial
     * title of this kind has both, and having to choose was the largest single reason a render came
     * out looking like an imitation of one.
     *
     * Null when the face names no pattern, which is the common case, so nothing is decoded for a
     * document that did not ask.
     */
    private fun faceTextureFor(geometry: Geometry3D, assets: AssetSource): FaceTexture? {
        val pattern = geometry.facePattern ?: return null
        val image = assets.load(pattern.asset) ?: return null
        return FaceTexture(
            pixels = image.pixels,
            width = image.width,
            height = image.height,
            // The pattern's own scale is a multiplier on the tile, so a larger number means a larger
            // tile and therefore *fewer* repeats across the letter. Inverting it here keeps the
            // control meaning the same thing it means everywhere else in the application.
            repeats = (1f / pattern.scale.x.coerceAtLeast(MIN_PATTERN_SCALE)),
            rotation = pattern.rotation,
        )
    }

    /**
     * Renders [layer] as 3D, at [width] × [height] pixels.
     *
     * @param supersample subsamples per axis. The bevel's highlight is a thin bright diagonal, and
     *   a thin bright diagonal is where aliasing is most visible — so this matters more here than
     *   on ordinary artwork.
     */
    fun render(
        layer: Layer.Text,
        geometry: Geometry3D,
        fonts: FontResolver,
        width: Int,
        height: Int,
        rasterizer: TextRasterizer = TextRasterizer(),
        supersample: Int = 2,
        /**
         * Where the document's environment map comes from, when it names one.
         *
         * A function rather than the pixels, because the common case is that no environment is
         * named and the generated studio is used — resolving an asset nobody asked for would decode
         * a photograph on every render.
         */
        assets: AssetSource = AssetSource.NONE,
    ): RasterImage? {
        val font = fonts.resolve(layer.spec.font) ?: return null
        val outline = TextToShape.outlineOf(layer.spec, font, rasterizer)
        val mesh = meshOf(outline, geometry)
        if (mesh.triangleCount == 0) return null

        val rendered = Rasteriser.render(
            mesh, geometry, width, height, supersample,
            environment = environmentFor(geometry.lighting, assets),
            faceTexture = faceTextureFor(geometry, assets),
        )
        if (rendered.isEmpty) return null
        return RasterImage(rendered.width, rendered.height, rendered.pixels)
    }

    /**
     * The document's environment, or the generated studio when it names none or the file is gone.
     *
     * A missing asset falls back rather than failing. An environment is a *look*, and a render that
     * refused to draw because a photograph had been moved would be worse than one that draws in the
     * studio it shipped with.
     */
    private fun environmentFor(
        rig: ir.pixellab.core.model.LightRig,
        assets: AssetSource,
    ): ir.pixellab.core.mesh.EnvironmentMap {
        val asset = rig.environment ?: return ir.pixellab.core.mesh.Studio
        val image = assets.load(asset) ?: return ir.pixellab.core.mesh.Studio
        return ir.pixellab.core.mesh.LatLong(
            pixels = image.pixels,
            width = image.width,
            height = image.height,
            intensity = rig.environmentIntensity,
            rotation = rig.environmentRotation,
        )
    }

    /**
     * Builds the mesh alone, for callers that want the geometry without pixels.
     *
     * The y flip is the one thing that has to happen here and nowhere else. Text is laid out in a
     * y-down frame and the extruder works in a y-up one; extruding a y-down outline turns every
     * letter inside out, and the result is not obviously broken — it is a plausible-looking letter
     * lit from behind, which is far harder to diagnose than a crash.
     */
    fun meshOf(outline: ShapeGeometry.Path, geometry: Geometry3D): Mesh {
        val contours = PathMath.flatten(outline)
            .map { contour -> contour.map { Vec2(it.x, -it.y) } }
            .filter { it.size >= MIN_POINTS }
        if (contours.isEmpty()) return Mesh.EMPTY

        return Extruder.extrude(
            contours = contours,
            depth = geometry.depth.coerceAtLeast(0f),
            bevelSize = geometry.bevelSize.coerceAtLeast(0f),
            bevelProfile = geometry.bevelProfile,
            bevelSegments = segmentsFor(geometry.bevelSize),
            marks = MarkStyle(
                depth = geometry.markDepth.coerceAtLeast(0f),
                lift = geometry.markLift,
            ),
        )
    }

    /**
     * Rings across the bevel, chosen from its width.
     *
     * A chamfer of one pixel does not need eight rings and a rounded edge of thirty does. Scaling
     * with the width keeps the silhouette smooth where it is visible and keeps the triangle count
     * — and so the bake time — from growing for an edge nobody can see.
     */
    private fun segmentsFor(bevelSize: Float): Int = when {
        bevelSize <= 0f -> 0
        bevelSize < SMALL_BEVEL -> 2
        bevelSize < LARGE_BEVEL -> 5
        else -> 9
    }

    /** Fewer than three points is a line, and a line has no interior to extrude. */
    private const val MIN_POINTS = 3

    private const val SMALL_BEVEL = 3f
    private const val LARGE_BEVEL = 12f

    /** Below this a pattern's scale would ask for thousands of repeats across one letter. */
    private const val MIN_PATTERN_SCALE = 0.01f
}
