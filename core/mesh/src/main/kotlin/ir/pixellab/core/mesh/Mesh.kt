package ir.pixellab.core.mesh

import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.Vec3

/**
 * Which of an extruded glyph's three surfaces a triangle belongs to.
 *
 * Three rather than one because the reference covers paint gold on the bevel and the side walls
 * while keeping the face white, and a single material cannot say that. It is also the split that
 * makes an extruded letter read as *carved* rather than as a coloured slab: the eye reads depth
 * from the bevel catching a different light than the face.
 */
enum class Surface { FACE, BEVEL, SIDE, BACK }

/**
 * A triangle mesh with per-vertex normals, grouped by surface.
 *
 * Flat arrays rather than a list of vertex objects: this is handed to a rasteriser that walks it
 * per pixel, and an array of objects at that size is cache-miss after cache-miss for data that is
 * three floats wide.
 */
class Mesh(
    val positions: FloatArray,
    val normals: FloatArray,
    /** Surface per *triangle*, not per vertex — a vertex on the bevel's edge belongs to both. */
    val surfaces: Array<Surface>,
    val indices: IntArray,
    /**
     * Whether each triangle belongs to a dot or a vowel mark rather than to the letter's body.
     *
     * A second flag alongside [surfaces] rather than more values in that enum, because the two
     * questions are independent: a dot has a face, a bevel and a wall exactly as the body does, and
     * folding "is it a dot" into the surface would have meant four more enum entries and a renderer
     * that had to remember to handle each. It also means a caller who does not care — every existing
     * one — is unaffected.
     */
    val marks: BooleanArray = BooleanArray(indices.size / 3),
) {
    init {
        require(positions.size == normals.size) { "every position needs a normal" }
        require(positions.size % 3 == 0) { "positions are triples, got ${positions.size}" }
        require(indices.size % 3 == 0) { "indices are triples, got ${indices.size}" }
        require(surfaces.size == indices.size / 3) { "one surface per triangle" }
        require(marks.size == indices.size / 3) { "one mark flag per triangle" }
    }

    /** True when at least one triangle belongs to a dot, so a caller can skip the whole question. */
    val hasMarks: Boolean get() = marks.any { it }

    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    fun position(index: Int) = Vec3(positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2])

    fun normal(index: Int) = Vec3(normals[index * 3], normals[index * 3 + 1], normals[index * 3 + 2])

    /** The axis-aligned box, which is what the camera is framed against. */
    fun bounds(): Pair<Vec3, Vec3> {
        if (vertexCount == 0) return Vec3.ZERO to Vec3.ZERO
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (i in 0 until vertexCount) {
            val x = positions[i * 3]
            val y = positions[i * 3 + 1]
            val z = positions[i * 3 + 2]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (z < minZ) minZ = z
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            if (z > maxZ) maxZ = z
        }
        return Vec3(minX, minY, minZ) to Vec3(maxX, maxY, maxZ)
    }

    companion object {
        val EMPTY = Mesh(FloatArray(0), FloatArray(0), emptyArray(), IntArray(0), BooleanArray(0))
    }
}

/** Accumulates triangles while the extruder walks the outline. */
class MeshBuilder {
    private val positions = ArrayList<Float>()
    private val normals = ArrayList<Float>()
    private val surfaces = ArrayList<Surface>()
    private val indices = ArrayList<Int>()
    private val marks = ArrayList<Boolean>()

    val vertexCount: Int get() = positions.size / 3

    /**
     * Whether what is being added now belongs to a dot or a mark.
     *
     * A mode on the builder rather than an argument on every call. The extruder walks a whole
     * contour at a time and every triangle it emits during that walk has the same answer, so the
     * alternative was threading one boolean through six functions to say the same thing each time.
     */
    var building: Boolean = false

    fun vertex(position: Vec3, normal: Vec3): Int {
        positions += position.x
        positions += position.y
        positions += position.z
        normals += normal.x
        normals += normal.y
        normals += normal.z
        return vertexCount - 1
    }

    fun triangle(a: Int, b: Int, c: Int, surface: Surface) {
        indices += a
        indices += b
        indices += c
        surfaces += surface
        marks += building
    }

    /** Two triangles across a quad, wound consistently so back-face culling can be trusted. */
    fun quad(a: Int, b: Int, c: Int, d: Int, surface: Surface) {
        triangle(a, b, c, surface)
        triangle(a, c, d, surface)
    }

    fun build() = Mesh(
        positions = positions.toFloatArray(),
        normals = normals.toFloatArray(),
        surfaces = surfaces.toTypedArray(),
        indices = indices.toIntArray(),
        marks = marks.toBooleanArray(),
    )
}

/** A point on a contour with the direction the outline turns there — what a bevel is offset along. */
internal data class Rim(val point: Vec2, val outward: Vec2)
