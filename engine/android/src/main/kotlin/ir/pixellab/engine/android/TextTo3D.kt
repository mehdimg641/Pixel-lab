package ir.pixellab.engine.android

import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.mesh.Extruder
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
    ): RasterImage? {
        val font = fonts.resolve(layer.spec.font) ?: return null
        val outline = TextToShape.outlineOf(layer.spec, font, rasterizer)
        val mesh = meshOf(outline, geometry)
        if (mesh.triangleCount == 0) return null

        val rendered = Rasteriser.render(mesh, geometry, width, height, supersample)
        if (rendered.isEmpty) return null
        return RasterImage(rendered.width, rendered.height, rendered.pixels)
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
}
