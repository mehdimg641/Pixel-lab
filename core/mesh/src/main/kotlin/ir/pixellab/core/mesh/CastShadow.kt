package ir.pixellab.core.mesh

import ir.pixellab.core.model.LightRig
import ir.pixellab.core.model.ShadowCast
import ir.pixellab.core.model.Vec3
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The shadow the letters throw behind themselves.
 *
 * **`Light.castsShadow` has been in the model since the lighting rig was written, set at two call
 * sites, and read by no renderer at all.** Nothing in this application has ever cast a shadow. That
 * is the twelfth time this repository has found the same shape of gap — the document could express
 * it, save it, reload it, and no pixel ever changed — and it is the one that costs the most,
 * because a shadow is not a refinement on a 3D render. It is the thing that says the letters are
 * *somewhere*. Without one they are a cut-out laid on the page, and no amount of work on the metal
 * fixes that; every reference cover this project is measured against has one.
 *
 * ### What it actually computes
 *
 * A real projection, not an offset copy. The receiving surface is a plane behind the type — the
 * background the type is set against — and every vertex is pushed along the light's own direction
 * until it meets that plane:
 *
 * ```
 * P' = P + L·t   where   t = (zPlane − P.z) / L.z
 * ```
 *
 * which is exact for a directional light. The difference from the offset-copy shortcut shows up
 * wherever the type has depth: the back of the slab is further from the plane than the face, so its
 * shadow lands further away, and the shadow of a deep letter is *larger and displaced* rather than
 * being the same silhouette moved sideways. On a lean like the cover styles use, that is the whole
 * difference between a shadow that belongs to the block and a smudge behind it.
 *
 * ### Why it is drawn into the letter's own buffer
 *
 * There is no backdrop here, and there should not be: the 3D text is one layer and the background
 * is another, so baking a surface into this render would take the user's photograph out of the
 * picture. Instead the shadow is written as dark translucent pixels where the letters are not, and
 * ordinary source-over compositing does the rest — `dst·(1−a)` is a multiply, which is what a
 * shadow on a surface is.
 */
internal object CastShadow {

    /**
     * Computes the shadow and lays it into the buffer, underneath everything.
     *
     * @param plane how far behind the type its receiving surface sits, in world units.
     */
    fun draw(
        mesh: Mesh,
        model: Mat4,
        viewProjection: Mat4,
        rig: LightRig,
        cast: ShadowCast,
        colour: IntArray,
        width: Int,
        height: Int,
    ) {
        val cover = coverage(mesh, model, viewProjection, rig, cast, width, height) ?: return
        // Straight alpha, not premultiplied: that is what the rest of this buffer holds, and the
        // downsample premultiplies on its way out. Writing premultiplied here would show up as a
        // shadow that pales as it softens.
        val rgb = (byte(cast.color.r) shl 16) or (byte(cast.color.g) shl 8) or byte(cast.color.b)
        for (i in colour.indices) {
            val a = cover[i] * cast.opacity
            if (a <= 0f) continue
            colour[i] = (byte(a) shl 24) or rgb
        }
    }

    private fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f + HALF).toInt()

    /**
     * The plane that throws the shadow the requested distance.
     *
     * The user asks for a *throw* — how far behind the letters the shadow falls, as a fraction of
     * their height — and this solves for the surface that produces it:
     *
     * ```
     * throw = |L.xy| · (zFront − zPlane) / |L.z|      ⟹      zPlane = zFront − throw · |L.z| / |L.xy|
     * ```
     *
     * Clamped to sit behind the letters, because a plane solved to a position *inside* the slab
     * would be a surface cutting through the thing standing on it. That happens whenever the light
     * is nearly head-on: a lamp directly behind the camera throws almost no shadow sideways, and the
     * honest answer is a shadow tucked right under the type rather than an impossible geometry.
     */
    private fun plane(mesh: Mesh, model: Mat4, light: Vec3, throwFraction: Float): Float {
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (i in 0 until mesh.vertexCount) {
            val p = model.transform(mesh.position(i))
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
            if (p.z < minZ) minZ = p.z
            if (p.z > maxZ) maxZ = p.z
        }
        val height = (maxY - minY).coerceAtLeast(MIN_EXTENT)
        val lateral = kotlin.math.hypot(light.x, light.y)
        if (lateral < MIN_SLOPE) return minZ - height * throwFraction
        val wanted = height * throwFraction
        return min(minZ, maxZ - wanted * kotlin.math.abs(light.z) / lateral)
    }

    /**
     * Where the shadow reaches, so the camera can be framed to hold it.
     *
     * Without this the frame is fitted to the letters alone and the shadow is sliced off by the
     * edge of the picture — a hard straight cut down one side, which is worse than having no shadow
     * at all because it reads as a rendering fault rather than as a choice.
     *
     * The eight corners of the letters' box, not every vertex. The projection is affine, so the
     * shadow of a convex hull is the hull of the projected corners; walking thousands of vertices
     * would arrive at the same eight numbers.
     */
    fun reach(mesh: Mesh, model: Mat4, rig: LightRig, cast: ShadowCast): List<Vec3> {
        val light = caster(rig, cast) ?: return emptyList()
        val zPlane = plane(mesh, model, light, cast.distance)

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
        if (minX > maxX) return emptyList()

        return buildList {
            for (x in listOf(minX, maxX)) {
                for (y in listOf(minY, maxY)) {
                    for (z in listOf(minZ, maxZ)) {
                        add(onPlane(Vec3(x, y, z), light, zPlane))
                    }
                }
            }
        }
    }

    /**
     * The one light that throws the shadow, or null when none does.
     *
     * Only the lights that were told to. A fill light casting its own is what makes a render look
     * computed: two shadows at two angles is a thing photographers work to avoid.
     */
    private fun caster(rig: LightRig, cast: ShadowCast): Vec3? {
        // The rig decides *whether*; the shadow decides *where from*. `castsShadow` is still the
        // switch — a rig with every light told not to cast throws nothing — but the direction comes
        // from the look, for the reason set out on `ShadowCast.direction`.
        if (listOfNotNull(rig.key, rig.fill, rig.rim).none { it.castsShadow }) return null
        val light = cast.direction.normalised()
        // Travelling towards the viewer, so it would throw its shadow behind the type where no
        // surface is. Nothing to draw, and pretending otherwise puts it on the wrong side.
        return if (light.z >= -MIN_SLOPE) null else light
    }

    private fun coverage(
        mesh: Mesh,
        model: Mat4,
        viewProjection: Mat4,
        rig: LightRig,
        cast: ShadowCast,
        width: Int,
        height: Int,
    ): FloatArray? {
        val light = caster(rig, cast) ?: return null
        val zPlane = plane(mesh, model, light, cast.distance)

        val cover = FloatArray(width * height)
        for (t in 0 until mesh.triangleCount) {
            val a = onPlane(model.transform(mesh.position(mesh.indices[t * 3])), light, zPlane)
            val b = onPlane(model.transform(mesh.position(mesh.indices[t * 3 + 1])), light, zPlane)
            val c = onPlane(model.transform(mesh.position(mesh.indices[t * 3 + 2])), light, zPlane)
            fill(a, b, c, viewProjection, cover, width, height)
        }

        // Blurred after the whole silhouette is accumulated, never per triangle: a mesh is hundreds
        // of triangles covering the same ground, and blurring each would pile penumbra on top of
        // penumbra until the shadow's interior was darker than its own edge.
        val radius = (cast.softness * min(width, height) * SOFTNESS_SCALE).roundToInt()
        if (radius > 0) blur(cover, width, height, radius)
        return cover
    }

    /** Where a point lands when pushed along the light until it meets the plane. */
    private fun onPlane(p: Vec3, light: Vec3, zPlane: Float): Vec3 {
        val t = (zPlane - p.z) / light.z
        return Vec3(p.x + light.x * t, p.y + light.y * t, zPlane)
    }

    /**
     * Coverage, not colour, and taken as a maximum rather than a sum.
     *
     * Every triangle of a solid projects onto the same patch of plane — the face, the walls and the
     * back all land on top of one another — so adding them would make a shadow whose darkness
     * counted the geometry behind it. A shadow is binary at the surface: lit, or not.
     */
    private fun fill(
        a: Vec3,
        b: Vec3,
        c: Vec3,
        viewProjection: Mat4,
        cover: FloatArray,
        width: Int,
        height: Int,
    ) {
        val ca = viewProjection.project(a)
        val cb = viewProjection.project(b)
        val cc = viewProjection.project(c)
        if (ca[3] <= NEAR_W || cb[3] <= NEAR_W || cc[3] <= NEAR_W) return

        val sa = screen(ca, width, height)
        val sb = screen(cb, width, height)
        val sc = screen(cc, width, height)

        // Both windings kept. The projection flattens a solid onto a plane, which turns half its
        // triangles inside out — culling here would punch the back of every letter out of its own
        // shadow.
        val area = edge(sa, sb, sc)
        if (area == 0f) return
        val sign = if (area < 0f) -1f else 1f

        val minX = max(0, floor(minOf(sa[0], sb[0], sc[0])).toInt())
        val maxX = min(width - 1, ceil(maxOf(sa[0], sb[0], sc[0])).toInt())
        val minY = max(0, floor(minOf(sa[1], sb[1], sc[1])).toInt())
        val maxY = min(height - 1, ceil(maxOf(sa[1], sb[1], sc[1])).toInt())
        if (minX > maxX || minY > maxY) return

        val p = floatArrayOf(0f, 0f)
        for (y in minY..maxY) {
            p[1] = y + HALF
            for (x in minX..maxX) {
                p[0] = x + HALF
                if (edge(sa, sb, p) * sign < 0f) continue
                if (edge(sb, sc, p) * sign < 0f) continue
                if (edge(sc, sa, p) * sign < 0f) continue
                cover[y * width + x] = 1f
            }
        }
    }

    /**
     * Three box passes, which is a Gaussian to within a percent and costs four adds per pixel.
     *
     * A true Gaussian here would be a kernel hundreds of samples wide — the penumbra on a cover-size
     * shadow is a large fraction of the letter — and the difference is invisible under a shadow that
     * is by definition soft.
     */
    private fun blur(cover: FloatArray, width: Int, height: Int, radius: Int) {
        val scratch = FloatArray(cover.size)
        repeat(BOX_PASSES) {
            boxHorizontal(cover, scratch, width, height, radius)
            boxVertical(scratch, cover, width, height, radius)
        }
    }

    private fun boxHorizontal(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int) {
        val span = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            // Edges clamp rather than wrap or darken: a shadow running off the frame continues off
            // the frame, and treating the outside as unlit would draw a dark band down the border.
            var sum = 0f
            for (i in -radius..radius) sum += src[row + i.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                dst[row + x] = sum / span
                sum -= src[row + (x - radius).coerceIn(0, width - 1)]
                sum += src[row + (x + radius + 1).coerceIn(0, width - 1)]
            }
        }
    }

    private fun boxVertical(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int) {
        val span = radius * 2 + 1
        for (x in 0 until width) {
            var sum = 0f
            for (i in -radius..radius) sum += src[i.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                dst[y * width + x] = sum / span
                sum -= src[(y - radius).coerceIn(0, height - 1) * width + x]
                sum += src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            }
        }
    }

    private fun screen(clip: FloatArray, width: Int, height: Int) = floatArrayOf(
        (clip[0] / clip[3] * HALF + HALF) * width,
        (HALF - clip[1] / clip[3] * HALF) * height,
    )

    private fun edge(a: FloatArray, b: FloatArray, p: FloatArray): Float =
        (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])

    /**
     * Below this the light is running almost parallel to the receiving plane, and `1/L.z` throws the
     * silhouette to the far side of the world. Photographers call the same thing a raking light and
     * get the same answer: the shadow leaves the frame.
     */
    private const val MIN_SLOPE = 0.05f

    /** Softness is authored as a fraction of the frame, so a look survives a change of resolution. */
    private const val SOFTNESS_SCALE = 0.12f

    private const val MIN_EXTENT = 1e-3f

    private const val BOX_PASSES = 3
    private const val NEAR_W = 1e-4f
    private const val HALF = 0.5f
}

