package ir.pixellab.core.mesh

import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Geometry3D
import ir.pixellab.core.model.Material
import ir.pixellab.core.model.Vec3
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a mesh into pixels, on the CPU.
 *
 * On the CPU deliberately. 3D text in a cover design is *baked*: the user sets the depth and the
 * lighting once and then works on the artwork around it for an hour. Baking is not the compromise
 * here, it is the right shape for the task — and it buys two things a GPU path would not. The
 * result can be re-rendered at export resolution rather than at whatever the preview surface
 * happens to be, and every part of it is testable on a build machine with no GPU at all, which is
 * how the shading maths in this module is verified.
 *
 * Perspective-correct throughout. Interpolating a normal linearly in screen space is the classic
 * shortcut and it bends the shading across every triangle that recedes — visible as a crease down
 * the middle of a letter's side wall that no lighting change removes.
 */
object Rasteriser {

    /**
     * @param supersample pixels rendered per output pixel per axis. Two is enough to take the
     *   staircase off a letter's edge; the bevel's highlight is what makes aliasing obvious here,
     *   because it is a thin bright line running diagonally.
     */
    fun render(
        mesh: Mesh,
        geometry: Geometry3D,
        width: Int,
        height: Int,
        supersample: Int = 2,
    ): Rendered {
        require(width > 0 && height > 0) { "the target must be positive, got ${width}x$height" }
        if (mesh.triangleCount == 0) return Rendered(width, height, IntArray(width * height))

        val scale = supersample.coerceIn(1, MAX_SUPERSAMPLE)
        val w = width * scale
        val h = height * scale

        val model = Mat4.rotation(geometry.rotation)
        val normalMatrix = Mat4.normalMatrix(model)
        val (eye, view) = framing(mesh, model, geometry, width.toFloat() / height)
        val projection = Mat4.perspective(
            geometry.fieldOfView.coerceIn(MIN_FOV, MAX_FOV),
            width.toFloat() / height,
            NEAR,
            FAR,
        )
        val viewProjection = projection * view

        val colour = IntArray(w * h)
        val depth = FloatArray(w * h) { Float.MAX_VALUE }

        // Materials decoded once. A base colour is authored as an sRGB swatch and the shading runs
        // in linear light; decoding per pixel would be a pow per channel per fragment for a value
        // that never changes.
        val materials = mapOf(
            Surface.FACE to linearised(geometry.faceMaterial),
            Surface.BEVEL to linearised(geometry.bevelMaterial),
            Surface.SIDE to linearised(geometry.sideMaterial),
            Surface.BACK to linearised(geometry.sideMaterial),
        )

        // The dots' own material, when one is chosen. Null falls through to the table above, so a
        // letter whose dots were never given a material renders exactly as it did before — which is
        // what makes this an addition rather than a change to every existing document.
        val markMaterial = geometry.markMaterial?.let { linearised(it) }

        for (t in 0 until mesh.triangleCount) {
            drawTriangle(
                mesh = mesh,
                triangle = t,
                model = model,
                normalMatrix = normalMatrix,
                viewProjection = viewProjection,
                eye = eye,
                material = if (markMaterial != null && mesh.marks[t]) {
                    markMaterial
                } else {
                    materials.getValue(mesh.surfaces[t])
                },
                geometry = geometry,
                colour = colour,
                depth = depth,
                width = w,
                height = h,
            )
        }

        return Rendered(width, height, downsample(colour, w, h, scale))
    }

    private fun drawTriangle(
        mesh: Mesh,
        triangle: Int,
        model: Mat4,
        normalMatrix: Mat4,
        viewProjection: Mat4,
        eye: Vec3,
        material: Material,
        geometry: Geometry3D,
        colour: IntArray,
        depth: FloatArray,
        width: Int,
        height: Int,
    ) {
        val ia = mesh.indices[triangle * 3]
        val ib = mesh.indices[triangle * 3 + 1]
        val ic = mesh.indices[triangle * 3 + 2]

        val worldA = model.transform(mesh.position(ia))
        val worldB = model.transform(mesh.position(ib))
        val worldC = model.transform(mesh.position(ic))

        val clipA = viewProjection.project(worldA)
        val clipB = viewProjection.project(worldB)
        val clipC = viewProjection.project(worldC)
        // Anything crossing the near plane is dropped whole rather than clipped. At the field of
        // view a text layer uses, geometry only reaches the near plane when the camera is inside
        // the letter, and clipping properly would be a lot of code for a case nobody can see.
        if (clipA[3] <= NEAR_W || clipB[3] <= NEAR_W || clipC[3] <= NEAR_W) return

        val sa = toScreen(clipA, width, height)
        val sb = toScreen(clipB, width, height)
        val sc = toScreen(clipC, width, height)

        // Negated because the screen transform flips y, and flipping one axis reverses the sign of
        // every signed area with it. A front-facing triangle therefore comes out *clockwise* here,
        // and a cull written for the unflipped convention keeps exactly the wrong half — which
        // renders the inside of every letter, correctly lit and entirely black.
        val area = -edge(sa, sb, sc)
        // Back faces dropped. The mesh is closed, so every back face is hidden by a front one — and
        // skipping them halves the work while removing the z-fighting where the two coincide.
        if (area <= 0f) return

        val minX = max(0, floor(minOf(sa[0], sb[0], sc[0])).toInt())
        val maxX = min(width - 1, ceil(maxOf(sa[0], sb[0], sc[0])).toInt())
        val minY = max(0, floor(minOf(sa[1], sb[1], sc[1])).toInt())
        val maxY = min(height - 1, ceil(maxOf(sa[1], sb[1], sc[1])).toInt())
        if (minX > maxX || minY > maxY) return

        val na = normalMatrix.rotate(mesh.normal(ia))
        val nb = normalMatrix.rotate(mesh.normal(ib))
        val nc = normalMatrix.rotate(mesh.normal(ic))

        // The reciprocals are what make the interpolation perspective-correct: a linear ramp in
        // screen space is only linear in 1/w, so every attribute is divided by w here and the sum
        // divided back at the end.
        val wa = 1f / clipA[3]
        val wb = 1f / clipB[3]
        val wc = 1f / clipC[3]

        val point = FloatArray(2)
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                point[0] = x + HALF
                point[1] = y + HALF

                var l0 = -edge(sb, sc, point)
                var l1 = -edge(sc, sa, point)
                var l2 = -edge(sa, sb, point)
                if (l0 < 0f || l1 < 0f || l2 < 0f) continue
                l0 /= area
                l1 /= area
                l2 /= area

                val invW = l0 * wa + l1 * wb + l2 * wc
                if (invW <= 0f) continue
                val z = (l0 * sa[2] * wa + l1 * sb[2] * wb + l2 * sc[2] * wc) / invW

                val at = y * width + x
                if (z >= depth[at]) continue
                depth[at] = z

                val world = Vec3(
                    (l0 * worldA.x * wa + l1 * worldB.x * wb + l2 * worldC.x * wc) / invW,
                    (l0 * worldA.y * wa + l1 * worldB.y * wb + l2 * worldC.y * wc) / invW,
                    (l0 * worldA.z * wa + l1 * worldB.z * wb + l2 * worldC.z * wc) / invW,
                )
                val normal = Vec3(
                    (l0 * na.x * wa + l1 * nb.x * wb + l2 * nc.x * wc) / invW,
                    (l0 * na.y * wa + l1 * nb.y * wb + l2 * nc.y * wc) / invW,
                    (l0 * na.z * wa + l1 * nb.z * wb + l2 * nc.z * wc) / invW,
                )

                val shaded = Pbr.toneMap(
                    Pbr.shade(
                        normal = normal,
                        view = (eye - world).normalised(),
                        material = material,
                        rig = geometry.lighting,
                    ),
                )
                colour[at] = argb(shaded)
            }
        }
    }

    /**
     * Places the camera so the whole letter fits, whatever it was rotated to.
     *
     * Computed from the *rotated* bounds rather than the flat outline's, because a letter turned
     * forty degrees about Y is wider on screen than it was flat — and a camera framed before the
     * rotation crops it, which looks like the extrusion overflowed rather than like a framing bug.
     */
    private fun framing(
        mesh: Mesh,
        model: Mat4,
        geometry: Geometry3D,
        aspect: Float,
    ): Pair<Vec3, Mat4> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (i in 0 until mesh.vertexCount) {
            val p = model.transform(mesh.position(i))
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.z < minZ) minZ = p.z
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
            if (p.z > maxZ) maxZ = p.z
        }

        val centre = Vec3((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
        val halfHeight = ((maxY - minY) / 2f).coerceAtLeast(MIN_EXTENT)
        val halfWidth = ((maxX - minX) / 2f).coerceAtLeast(MIN_EXTENT)
        val fov = geometry.fieldOfView.coerceIn(MIN_FOV, MAX_FOV)
        val tan = kotlin.math.tan(fov * DEG_TO_RAD / 2f)

        // Whichever axis needs more room wins, with a margin so a highlight on the outermost bevel
        // is not shaved off by the frame.
        val forHeight = halfHeight / tan
        val forWidth = halfWidth / (tan * aspect)
        val distance = max(forHeight, forWidth) * MARGIN + (maxZ - minZ)

        val eye = Vec3(centre.x, centre.y, centre.z + distance)
        return eye to Mat4.lookAt(eye, centre)
    }

    /**
     * Averages the supersampled buffer down.
     *
     * In premultiplied form, because the buffer has transparent pixels around the letter: averaging
     * their leftover colour in unweighted is what puts a dark fringe round every edge of a cut-out,
     * and a 3D letter is entirely edge.
     */
    private fun downsample(source: IntArray, width: Int, height: Int, scale: Int): IntArray {
        if (scale == 1) return source
        val outWidth = width / scale
        val outHeight = height / scale
        val out = IntArray(outWidth * outHeight)

        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        val pixel = source[(y * scale + dy) * width + x * scale + dx]
                        val alpha = (pixel ushr 24) and 0xFF
                        a += alpha
                        r += ((pixel shr 16) and 0xFF) * alpha
                        g += ((pixel shr 8) and 0xFF) * alpha
                        b += (pixel and 0xFF) * alpha
                    }
                }
                val samples = scale * scale
                val alpha = a / samples
                out[y * outWidth + x] = if (a == 0) {
                    0
                } else {
                    (alpha shl 24) or ((r / a) shl 16) or ((g / a) shl 8) or (b / a)
                }
            }
        }
        return out
    }

    private fun linearised(material: Material) = material.copy(
        baseColor = Pbr.decode(material.baseColor),
        emissive = Pbr.decode(material.emissive),
    )

    private fun toScreen(clip: FloatArray, width: Int, height: Int) = floatArrayOf(
        (clip[0] / clip[3] * HALF + HALF) * width,
        // Flipped: clip space is y-up and a pixel buffer is y-down. Forgetting this is the
        // vertically-mirrored render that looks almost right until there is a letter in it.
        (HALF - clip[1] / clip[3] * HALF) * height,
        clip[2] / clip[3],
    )

    private fun edge(a: FloatArray, b: FloatArray, p: FloatArray): Float =
        (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])

    private fun argb(color: Color): Int {
        fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f + HALF).toInt()
        return (0xFF shl 24) or (byte(color.r) shl 16) or (byte(color.g) shl 8) or byte(color.b)
    }

    private const val HALF = 0.5f
    private const val NEAR = 0.1f
    private const val FAR = 10_000f

    /** Anything closer than this to the camera plane is behind it once the division happens. */
    private const val NEAR_W = 1e-3f

    private const val MIN_FOV = 5f
    private const val MAX_FOV = 120f
    private const val MIN_EXTENT = 1e-3f
    private const val MARGIN = 1.12f
    private const val MAX_SUPERSAMPLE = 4
}

/** The rendered image, as straight ARGB — the layout the codecs and the asset store both use. */
class Rendered(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "pixel count does not match ${width}x$height" }
    }

    /** True when nothing was drawn, which is a real outcome for an empty or off-screen mesh. */
    val isEmpty: Boolean get() = pixels.all { (it ushr 24) == 0 }
}
