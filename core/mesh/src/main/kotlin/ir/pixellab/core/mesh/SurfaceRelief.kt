package ir.pixellab.core.mesh

import ir.pixellab.core.model.Material
import ir.pixellab.core.model.Relief
import ir.pixellab.core.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The fine height variation that makes a surface look like a material.
 *
 * ### Why this exists at all
 *
 * The reference images this project is measured against are made of *materials* — leather, knitted
 * wool, rusted steel, hammered copper, frost. None of them is a colour. Each is a fine height
 * variation catching the light at slightly different angles across a surface, and without one a
 * "leather" letter is a brown letter and everybody can tell. This is the missing half of the
 * material system: the shading model in [Pbr] has been able to render these convincingly the whole
 * time, and had nothing but a flat surface to render.
 *
 * ### Generated, not loaded
 *
 * Every one of these is a formula. Two reasons that happen to agree: the project ships no asset pack
 * and never uploads anything, so a texture needing a download is a texture nobody has — and a
 * formula has no resolution, so a letter blown up to poster size keeps its grain where a
 * 1024-pixel photograph of leather turns to mush at exactly the size a cover is printed at.
 *
 * ### The normal, not the geometry
 *
 * Nothing here moves a vertex. The height is sampled three times per shaded point and turned into a
 * tilt of the surface normal, which is what a bump map is and is the right trade here: real
 * displacement on a CPU rasteriser would mean subdividing every letter's face into hundreds of
 * thousands of triangles for a variation smaller than a pixel of silhouette. The one thing it
 * cannot do is change the letter's *outline* — a knitted letter has smooth edges rather than
 * fuzzy ones — and that is worth knowing rather than discovering.
 */
internal object SurfaceRelief {

    /**
     * The perturbed normal at a point on a surface.
     *
     * @param u,v the surface's own coordinates, 0..1 across the letter's bounding box.
     * @param normal the geometric normal, unit.
     *
     * Tangent and bitangent are taken as the world X and Y axes rather than from a per-vertex frame.
     * That is exact for the face of an extruded letter — which is flat, faces the camera, and is
     * where all of the visible surface is — and an approximation on the walls, where the relief is
     * mostly hidden anyway. Building a real tangent frame would mean carrying two more vectors per
     * vertex through the extruder for a difference nobody can see on a letter.
     */
    fun perturb(material: Material, u: Float, v: Float, normal: Vec3): Vec3 {
        if (material.relief == Relief.NONE || material.reliefDepth <= 0f) return normal

        val scale = max(material.reliefScale, MIN_SCALE)
        val step = scale * STEP_FRACTION
        val here = height(material.relief, u / scale, v / scale)
        val du = (height(material.relief, (u + step) / scale, v / scale) - here) / step
        val dv = (height(material.relief, u / scale, (v + step) / scale) - here) / step

        // The standard bump construction: tilt the normal against the height gradient. The depth
        // multiplies the gradient rather than the height, so turning it down flattens the surface
        // smoothly instead of shrinking the features.
        val strength = material.reliefDepth
        return Vec3(
            normal.x - du * strength,
            normal.y - dv * strength,
            normal.z,
        ).normalised()
    }

    /**
     * Height at a point, in the range 0..1, in the relief's own repeating coordinates.
     *
     * Each of these is an attempt at one specific surface rather than a generic noise with a name.
     * The difference matters more than it sounds: three parameterisations of the same fractal noise
     * labelled "leather", "concrete" and "rust" look like the same material three times, and a user
     * comparing them concludes the feature does not work.
     */
    fun height(relief: Relief, u: Float, v: Float): Float = when (relief) {
        Relief.NONE -> 0f

        // Irregular cells with soft creases between them: a Worley distance field, softened. The
        // creases are the feature — leather is defined by the valleys, not by the bumps.
        Relief.LEATHER -> {
            val cell = worley(u, v)
            val crease = smooth(cell.second - cell.first, 0f, 0.55f)
            crease * 0.75f + fractal(u * 6f, v * 6f, octaves = 2) * 0.25f
        }

        // Interlocking loops in rows, every other row offset. The row offset is what turns a grid
        // of blobs into knitting; without it the eye reads a waffle.
        Relief.KNIT -> {
            val row = floor(v)
            val shifted = u + if ((row.toInt() and 1) == 0) 0f else 0.5f
            val x = frac(shifted) - 0.5f
            val y = frac(v) - 0.5f
            // Two lobes leaning opposite ways make the over-and-under of a stitch.
            val a = lobe(x - 0.18f, y, 0.34f)
            val b = lobe(x + 0.18f, y, 0.34f)
            max(a, b) * 0.9f + fractal(u * 9f, v * 9f, octaves = 1) * 0.1f
        }

        // Fine parallel scratches, all running the same way. Deliberately almost invariant along
        // the grain: the *direction* is the entire content of brushed metal, and a version that
        // varies equally in both axes is simply a rough surface with a misleading name.
        //
        // The frequency is stated per tile like every other relief here, and [Material.reliefScale]
        // is what turns a tile into a real size. An earlier version multiplied by ninety inside the
        // tile, which put the scratches below the sampling rate at the scale the preset uses — the
        // gradient then read three effectively unrelated values and the "grain" came out as
        // isotropic hash. A test comparing variation along the grain with variation across it
        // caught it; nothing about the rendered picture would have.
        Relief.BRUSHED -> {
            val jitter = noise(floor(v * 3f), 0f) * 0.3f
            fractal((v + jitter) * 2.5f, u * 0.08f, octaves = 3)
        }

        // Pitted and eaten away: a high-contrast threshold on a coarse fractal, so the surface is
        // mostly intact with deep holes in it rather than evenly lumpy.
        Relief.RUST -> {
            val base = fractal(u * 2.2f, v * 2.2f, octaves = 4)
            val pits = smooth(fractal(u * 5f + 11f, v * 5f - 7f, octaves = 3), 0.52f, 0.78f)
            (base * 0.45f + pits * 0.55f).coerceIn(0f, 1f)
        }

        // Sparse hard aggregate sitting in a fine matrix — two very different frequencies, which is
        // what stops it reading as generic noise.
        Relief.CONCRETE -> {
            val aggregate = smooth(worley(u * 1.4f, v * 1.4f).first, 0.05f, 0.3f)
            val matrix = fractal(u * 14f, v * 14f, octaves = 3)
            aggregate * 0.6f + matrix * 0.4f
        }

        // Long grain along one axis with the lines bent by a slow warp, plus occasional knots.
        Relief.WOOD -> {
            val warp = fractal(u * 0.7f, v * 2.4f, octaves = 3) - 0.5f
            val rings = frac((v + warp * 0.55f) * 3.5f)
            val grain = abs(rings - 0.5f) * 2f
            val knot = smooth(worley(u * 0.5f, v * 0.5f).first, 0.28f, 0.05f)
            (grain * 0.7f + fractal(u * 30f, v * 3f, octaves = 2) * 0.3f) * (1f - knot * 0.6f)
        }

        // Overlapping shallow dents. Worley again, but the *nearest* distance rather than the gap
        // between the two nearest — a dent is a bowl, and a bowl is a distance.
        Relief.HAMMERED -> {
            val d = worley(u, v).first
            1f - smooth(d, 0f, 0.6f)
        }

        // Crystalline branching. Ridged noise — the absolute value of a signed fractal — is what
        // gives sharp ridges instead of round hills, and frost is all ridges.
        Relief.FROST -> {
            val n = fractal(u * 3f, v * 3f, octaves = 4) * 2f - 1f
            val ridged = 1f - abs(n)
            ridged * ridged
        }

        // A regular over-and-under weave: two perpendicular waves, whichever is on top winning.
        Relief.CANVAS -> {
            val warp = sin(u * TWO_PI) * 0.5f + 0.5f
            val weft = sin(v * TWO_PI) * 0.5f + 0.5f
            max(warp * smoothstep(weft), weft * smoothstep(warp))
        }
    }

    // ---- the small pieces everything above is built from ---------------------------------------

    /**
     * Distances to the nearest and second-nearest scattered point.
     *
     * The second one is not an extra: the *gap* between the two is what draws a crease exactly along
     * a cell boundary, which is how leather and cracked earth are made. The nearest distance alone
     * gives bowls, which is how a dent is made.
     */
    private fun worley(u: Float, v: Float): Pair<Float, Float> {
        val cx = floor(u)
        val cy = floor(v)
        var nearest = Float.MAX_VALUE
        var second = Float.MAX_VALUE
        for (dy in -1..1) {
            for (dx in -1..1) {
                val gx = cx + dx
                val gy = cy + dy
                val px = gx + noise(gx, gy)
                val py = gy + noise(gy, gx + 17f)
                val d = sqrt((px - u) * (px - u) + (py - v) * (py - v))
                if (d < nearest) {
                    second = nearest
                    nearest = d
                } else if (d < second) {
                    second = d
                }
            }
        }
        return nearest to second
    }

    /** Value noise, smoothly interpolated. Cheap, and every relief above is built out of it. */
    private fun valueNoise(u: Float, v: Float): Float {
        val x0 = floor(u)
        val y0 = floor(v)
        val fx = smoothstep(u - x0)
        val fy = smoothstep(v - y0)
        val a = noise(x0, y0)
        val b = noise(x0 + 1f, y0)
        val c = noise(x0, y0 + 1f)
        val d = noise(x0 + 1f, y0 + 1f)
        return (a + (b - a) * fx) * (1f - fy) + (c + (d - c) * fx) * fy
    }

    /** Sums octaves at halving amplitude, normalised so the result stays in 0..1. */
    private fun fractal(u: Float, v: Float, octaves: Int): Float {
        var sum = 0f
        var amplitude = 1f
        var total = 0f
        var frequency = 1f
        repeat(octaves) {
            sum += valueNoise(u * frequency, v * frequency) * amplitude
            total += amplitude
            amplitude *= 0.5f
            frequency *= 2.07f
        }
        return if (total > 0f) sum / total else 0f
    }

    /**
     * A deterministic pseudo-random value for a lattice point.
     *
     * Hashed rather than drawn from a generator, because the same point has to give the same answer
     * every time it is asked — and it is asked three times per shaded pixel from three slightly
     * different places. A stateful generator here would make the surface flicker.
     */
    private fun noise(x: Float, y: Float): Float {
        val s = sin(x * 127.1f + y * 311.7f) * 43758.5453f
        return s - floor(s)
    }

    private fun frac(x: Float): Float = x - floor(x)

    private fun smoothstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Smoothstep between two edges, tolerating them being given the other way round. */
    private fun smooth(value: Float, edge0: Float, edge1: Float): Float {
        if (edge0 == edge1) return if (value < edge0) 0f else 1f
        return smoothstep((value - edge0) / (edge1 - edge0))
    }

    /** A rounded bump of the given radius, 1 at its centre and 0 outside it. */
    private fun lobe(x: Float, y: Float, radius: Float): Float {
        val d = sqrt(x * x + y * y) / radius
        return if (d >= 1f) 0f else cos(d * PI.toFloat() / 2f)
    }

    /** Below this the gradient step underflows and the surface comes out flat. */
    private const val MIN_SCALE = 1e-4f

    /** The finite-difference step, as a fraction of the feature size. */
    private const val STEP_FRACTION = 0.02f

    private const val TWO_PI = (2.0 * PI).toFloat()
}

/**
 * The letter's own bounding box, as the frame a surface pattern is measured in.
 *
 * Object space rather than world space, and normalised rather than absolute. Object space so the
 * grain stays on the letter when it turns instead of sliding across it like a projection; and
 * normalised so a relief scale set on a small letter still means the same thing when the letter is
 * enlarged — an absolute size would make the grain of a poster-sized headline invisible.
 */
internal class SurfaceFrame(
    val minX: Float,
    val minY: Float,
    val spanX: Float,
    val spanY: Float,
) {
    companion object {
        fun of(mesh: Mesh): SurfaceFrame {
            val (min, max) = mesh.bounds()
            // A degenerate span would divide by zero on a single-point mesh, which puts NaN into
            // every normal and turns the whole letter black.
            return SurfaceFrame(
                minX = min.x,
                minY = min.y,
                spanX = (max.x - min.x).takeIf { it > 0f } ?: 1f,
                spanY = (max.y - min.y).takeIf { it > 0f } ?: 1f,
            )
        }
    }
}
