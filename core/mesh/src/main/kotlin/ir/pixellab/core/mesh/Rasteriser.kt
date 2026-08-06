package ir.pixellab.core.mesh

import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Geometry3D
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.model.Material
import ir.pixellab.core.model.Ramp
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
        /**
         * What the metal reflects. Defaults to the generated studio; a caller that has resolved
         * `LightRig.environment` to pixels passes a [LatLong] instead.
         */
        environment: EnvironmentMap = Studio,
        /**
         * A picture painted across the letter's face, replacing [Geometry3D.faceFill].
         *
         * Same arrangement as [environment], and for the same reason: this module resolves no
         * assets, so a caller that has turned a `Fill.Pattern` into pixels passes them in.
         */
        faceTexture: FaceTexture? = null,
    ): Rendered {
        require(width > 0 && height > 0) { "the target must be positive, got ${width}x$height" }
        if (mesh.triangleCount == 0) return Rendered(width, height, IntArray(width * height))

        val scale = supersample.coerceIn(1, MAX_SUPERSAMPLE)
        val w = width * scale
        val h = height * scale

        // Shear first, then turn: the lean belongs to the letter's own space, so a letter that is
        // also rotated leans with it rather than in screen space.
        val model = Mat4.rotation(geometry.rotation) * Mat4.obliqueExtrusion(geometry.extrusionTilt)
        val normalMatrix = Mat4.normalMatrix(model)
        // The shadow is framed with the letters. Fitting the camera to the mesh alone slices the
        // shadow off at the edge of the picture — a hard straight cut down one side, which reads as
        // a rendering fault rather than as a choice, and is worse than having no shadow at all.
        val shadowReach = geometry.shadow
            ?.let { CastShadow.reach(mesh, model, geometry.lighting, it) }
            .orEmpty()
        val (eye, view) = framing(mesh, model, geometry, width.toFloat() / height, shadowReach)
        val projection = Mat4.perspective(
            geometry.fieldOfView.coerceIn(MIN_FOV, MAX_FOV),
            width.toFloat() / height,
            NEAR,
            FAR,
        )
        val viewProjection = projection * view

        val colour = IntArray(w * h)
        val depth = FloatArray(w * h) { Float.MAX_VALUE }

        // Before the letters, never after: a shadow is what the surface behind them looks like, so
        // anything the mesh covers should simply overwrite it. Drawing it afterwards would need the
        // silhouette masked out of it, which is the same picture arrived at by more work.
        geometry.shadow?.let { cast ->
            CastShadow.draw(
                mesh = mesh,
                model = model,
                viewProjection = viewProjection,
                rig = geometry.lighting,
                cast = cast,
                colour = colour,
                width = w,
                height = h,
            )
        }

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

        // Built once. The stops are sorted and the object-space bounds measured here rather than
        // per fragment, because both are constant across the whole letter and sorting a stop list
        // inside the inner loop would cost more than the shading does.
        // A texture wins over a gradient when both are given: the caller asked for a picture, and
        // silently preferring the ramp would be the panel ignoring the more specific instruction.
        val facePaint = faceTexture?.let { TexturePaint.of(it, mesh) }
            ?: geometry.faceFill?.let { FacePaint.of(it, mesh) }
        val sidePaint = geometry.sideFill?.let { SidePaint.of(it, mesh) }

        for (t in 0 until mesh.triangleCount) {
            val surface = mesh.surfaces[t]
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
                    materials.getValue(surface)
                },
                // Only the face, and only where a mark has not claimed its own material — a dot
                // given chrome asked for chrome, not for the body's gradient.
                paint = when {
                    markMaterial != null && mesh.marks[t] -> null
                    surface == Surface.FACE -> facePaint
                    // The bevel takes the wall's ramp too, so the two meet in one colour instead of
                    // stepping at the seam between them.
                    else -> sidePaint
                },
                geometry = geometry,
                environment = environment,
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
        paint: SurfacePaint?,
        geometry: Geometry3D,
        environment: EnvironmentMap,
        colour: IntArray,
        depth: FloatArray,
        width: Int,
        height: Int,
    ) {
        val ia = mesh.indices[triangle * 3]
        val ib = mesh.indices[triangle * 3 + 1]
        val ic = mesh.indices[triangle * 3 + 2]

        val objectA = mesh.position(ia)
        val objectB = mesh.position(ib)
        val objectC = mesh.position(ic)

        val worldA = model.transform(objectA)
        val worldB = model.transform(objectB)
        val worldC = model.transform(objectC)

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
        // every signed area with it.
        val area = -edge(sa, sb, sc)
        // Back faces dropped, which is sound again now that the caps and the walls agree about which
        // way is out — holes are wound against their outlines everywhere rather than only in the
        // tessellator.
        //
        // Drawing both sides was the stopgap while they disagreed, and it cost more than it looked.
        // Every back surface reaching the rasteriser is another candidate for the same pixel, and
        // wherever one lands at nearly the depth of the front surface covering it, the two argue
        // and the winner changes from pixel to pixel — which draws as a crease across a letter that
        // no lighting change removes. Culling removes that entire class of artefact rather than
        // resolving it, because the losing surface never reaches the depth test at all.
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

                // Divided by the *signed* area before the inside test, so a triangle wound either
                // way is handled by the same three comparisons: a back-facing one has all three
                // edge functions negative and a negative area, and the two signs cancel.
                val l0 = -edge(sb, sc, point) / area
                val l1 = -edge(sc, sa, point) / area
                val l2 = -edge(sa, sb, point) / area
                if (l0 < 0f || l1 < 0f || l2 < 0f) continue

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
                val normalRaw = Vec3(
                    (l0 * na.x * wa + l1 * nb.x * wb + l2 * nc.x * wc) / invW,
                    (l0 * na.y * wa + l1 * nb.y * wb + l2 * nc.y * wc) / invW,
                    (l0 * na.z * wa + l1 * nb.z * wb + l2 * nc.z * wc) / invW,
                )
                val toEye = (eye - world).normalised()
                val normal = normalRaw

                // The face's gradient, sampled from the letter's *own* coordinates rather than the
                // world ones interpolated above. Using world space would slide the ramp across the
                // letter as it turned, which reads as the paint being on the camera rather than on
                // the letter. Object space is fixed to the glyph, so the colours stay where the
                // artist put them under any rotation or lean.
                val painted = if (paint == null) {
                    material
                } else {
                    val ox = (l0 * objectA.x * wa + l1 * objectB.x * wb + l2 * objectC.x * wc) / invW
                    val oy = (l0 * objectA.y * wa + l1 * objectB.y * wb + l2 * objectC.y * wc) / invW
                    val oz = (l0 * objectA.z * wa + l1 * objectB.z * wb + l2 * objectC.z * wc) / invW
                    val sampled = paint.at(ox, oy, oz)
                    // An unlit surface emits its colour directly, so the ramp must stay in the space
                    // the user authored it in; a lit one is about to be shaded in linear light.
                    material.copy(
                        baseColor = if (material.unlit) sampled else Pbr.decode(sampled),
                    )
                }

                // Straight through for an unlit surface: no shading, and no tone map either. Tone
                // mapping exists to bring a lit result back into display range, and running it over
                // a colour the user chose would darken and desaturate the very swatch they picked.
                val shaded = if (painted.unlit) {
                    painted.baseColor
                } else {
                    Pbr.toneMap(
                        Pbr.shade(
                            normal = normal,
                            view = toEye,
                            material = painted,
                            rig = geometry.lighting,
                            environment = environment,
                        ),
                    )
                }
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
        /** Already in world space — the shadow is projected before the camera exists. */
        alsoHold: List<Vec3> = emptyList(),
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

        // Width and height only. The shadow lies on a plane well behind the letters, and letting it
        // stretch the depth range would push the camera back and shrink the type for no reason —
        // nothing is being framed *in depth*, only across the picture.
        for (p in alsoHold) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
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

    /**
     * Decodes a material's authored sRGB swatches into the linear light the shading runs in.
     *
     * An unlit material keeps its base colour exactly as authored, because it never reaches the
     * shading at all — decoding it here and emitting it unchanged would show the user a different
     * colour from the one they picked.
     */
    private fun linearised(material: Material) = material.copy(
        baseColor = if (material.unlit) material.baseColor else Pbr.decode(material.baseColor),
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

/**
 * A gradient ready to be sampled across a letter's face.
 *
 * Holds the sorted stops and the letter's own bounds so the inner loop does neither. The bounds are
 * the mesh's, not the canvas's, which is what makes a ramp describe *the word* — the same gradient
 * reads identically on a caption and a poster headline, and does not shift when the text moves.
 */
/** A gradient sampled per fragment from the letter's own coordinates. */
internal interface SurfacePaint {
    fun at(x: Float, y: Float, z: Float): Color
}

/**
 * The face wearing a picture rather than a ramp.
 *
 * Mapped across the letter's own object-space bounds, exactly as [FacePaint] maps its gradient, so
 * the paint travels with the word and reads the same whether it sits in a corner or fills the page.
 * Mapping in screen space instead would slide the texture across the letters as the camera moved,
 * which reads as the paint being projected onto them rather than being their surface.
 */
internal class TexturePaint private constructor(
    private val texture: FaceTexture,
    private val minX: Float,
    private val minY: Float,
    private val spanX: Float,
    private val spanY: Float,
) : SurfacePaint {
    // Flipped vertically: object space runs up, an image runs down, and getting this wrong is
    // invisible on a symmetric tile and obvious on anything with a direction to it.
    override fun at(x: Float, y: Float, z: Float): Color =
        texture.at((x - minX) / spanX, 1f - (y - minY) / spanY)

    companion object {
        fun of(texture: FaceTexture, mesh: Mesh): TexturePaint {
            val (min, max) = mesh.bounds()
            return TexturePaint(
                texture = texture,
                minX = min.x,
                minY = min.y,
                spanX = (max.x - min.x).takeIf { it > 0f } ?: 1f,
                spanY = (max.y - min.y).takeIf { it > 0f } ?: 1f,
            )
        }
    }
}

internal class FacePaint private constructor(
    private val gradient: Fill.Gradient,
    private val stops: List<GradientStop>,
    private val minX: Float,
    private val minY: Float,
    private val spanX: Float,
    private val spanY: Float,
) : SurfacePaint {
    override fun at(x: Float, y: Float, z: Float): Color = Ramp.colorAt(
        stops,
        Ramp.parameterAt(gradient, (x - minX) / spanX, (y - minY) / spanY),
    )

    companion object {
        fun of(gradient: Fill.Gradient, mesh: Mesh): FacePaint {
            val (min, max) = mesh.bounds()
            // A degenerate span would divide by zero on a single-point mesh; one unit keeps the
            // ramp sampling at a fixed place rather than producing NaNs across the whole letter.
            val spanX = (max.x - min.x).takeIf { it > 0f } ?: 1f
            val spanY = (max.y - min.y).takeIf { it > 0f } ?: 1f
            return FacePaint(
                gradient = gradient,
                stops = gradient.stops.sortedBy { it.position },
                minX = min.x,
                minY = min.y,
                spanX = spanX,
                spanY = spanY,
            )
        }
    }
}

/**
 * A gradient sampled along the depth of the extrusion rather than across the letter.
 *
 * The front of the block is 0 and the back is 1, whatever the letter's outline is doing, so every
 * wall on every letter falls off together. That is the property the treatment depends on and the
 * one physical shading cannot give: lit metal is bright where it happens to face the key light, so
 * the left of a letter blazes while its right goes black.
 */
internal class SidePaint private constructor(
    private val gradient: Fill.Gradient,
    private val stops: List<GradientStop>,
    private val minZ: Float,
    private val spanZ: Float,
) : SurfacePaint {
    // Handed to the ramp as x, so a gradient at the default angle runs front-to-back and its stops
    // read in the order the user placed them.
    override fun at(x: Float, y: Float, z: Float): Color = Ramp.colorAt(
        stops,
        Ramp.parameterAt(gradient, 1f - (z - minZ) / spanZ, HALF),
    )

    companion object {
        fun of(gradient: Fill.Gradient, mesh: Mesh): SidePaint {
            val (min, max) = mesh.bounds()
            return SidePaint(
                gradient = gradient,
                stops = gradient.stops.sortedBy { it.position },
                minZ = min.z,
                spanZ = (max.z - min.z).takeIf { it > 0f } ?: 1f,
            )
        }

        private const val HALF = 0.5f
    }
}

/** The rendered image, as straight ARGB — the layout the codecs and the asset store both use. */
class Rendered(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "pixel count does not match ${width}x$height" }
    }

    /** True when nothing was drawn, which is a real outcome for an empty or off-screen mesh. */
    val isEmpty: Boolean get() = pixels.all { (it ushr 24) == 0 }
}
