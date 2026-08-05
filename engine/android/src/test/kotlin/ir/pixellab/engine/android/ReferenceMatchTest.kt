package ir.pixellab.engine.android

import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.Geometry3D
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Material
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.Vec3
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Reproducing a commercial text effect, as a way of finding what the engine cannot do.
 *
 * The reference is a stock PSD title: deep extrusion, orange metal on the bevel and side walls, and
 * a face carrying a painterly teal-to-peach texture rather than a flat colour, with a thin bright
 * rim between face and bevel and a soft shadow behind the whole word.
 *
 * Matching it is not the point. Failing to match it in a *specific* way is: each thing this cannot
 * reproduce is a feature the engine is missing, and a picture names them faster than reading the
 * model does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class ReferenceMatchTest {

    private val output = File("build/renders").apply { mkdirs() }

    /**
     * The heavy display face the reference is actually set in.
     *
     * Vazirmatn is a *text* family — its Latin is far lighter and more open than any poster face —
     * so every comparison against a commercial title spent most of its difference on letterforms
     * rather than on the renderer. Anton is the closest openly-licensed equivalent to what these
     * PSDs use, and it is bundled with its OFL text under `docs/licenses/fonts`.
     */
    private fun anton(): FontFile = load("anton.ttf", "Anton", Script.LATIN)

    private fun vazirmatn(): FontFile {
        val file = File.createTempFile("vazirmatn", ".ttf")
        file.deleteOnExit()
        checkNotNull(javaClass.classLoader?.getResourceAsStream("vazirmatn.ttf")) {
            "the test font is missing from engine/android/src/test/resources"
        }.use { input -> file.outputStream().use { input.copyTo(it) } }
        return FontFile(
            path = file.absolutePath,
            family = "Vazirmatn",
            subfamily = "Bold",
            postScriptName = "Vazirmatn-Bold",
            fullName = "Vazirmatn Bold",
            weight = 900,
            italic = false,
            // Declared, because the loader drops any axis the file does not claim to have. The real
            // app fills this in from its font scan; a fixture that leaves it empty silently renders
            // every weight as Regular.
            axes = mapOf(FontRef.AXIS_WEIGHT to 100f..900f),
            features = emptySet(),
            script = Script.ARABIC,
            hasPersianDigits = true,
            hasTatweel = true,
        )
    }

    private fun load(resource: String, family: String, script: Script): FontFile {
        val file = File.createTempFile(family, ".ttf")
        file.deleteOnExit()
        checkNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "$resource is missing from engine/android/src/test/resources"
        }.use { input -> file.outputStream().use { input.copyTo(it) } }
        return FontFile(
            path = file.absolutePath,
            family = family,
            subfamily = "Regular",
            postScriptName = "$family-Regular",
            fullName = "$family Regular",
            weight = 400,
            italic = false,
            axes = emptyMap(),
            features = emptySet(),
            script = script,
            hasPersianDigits = false,
            hasTatweel = false,
        )
    }

    /**
     * The reference's own recipe, in this engine's terms.
     *
     * The letters stand upright and frontal and the depth runs off down-left, which is the whole
     * character of the style and is a *lean*, not a turn. A small rotation is kept only to catch a
     * highlight along the top of the bevel.
     */
    private fun trendLook(size: Float, bevelFraction: Float = 0.035f) = Geometry3D(
        depth = size * 0.40f,
        bevelSize = size * bevelFraction,
        faceMaterial = Material(roughness = 0.30f, clearCoat = 1f),
        faceFill = TEAL_TO_PEACH,
        // The rim is a stroke, so it is unlit: one colour the whole way round the letter, the same
        // on the edge facing the light and the edge facing away.
        bevelMaterial = Material(baseColor = Color(1f, 0.80f, 0.20f), unlit = true),
        sideMaterial = Material(unlit = true),
        sideFill = ORANGE_BLOCK,
        // Down and to the right, which is where the reference throws its block.
        extrusionTilt = Vec2(0.34f, -0.30f),
        rotation = Vec3(-3f, 4f, 0f),
        fieldOfView = 22f,
    )

    /**
     * The two paths, joined: a painted face inside real extruded metal.
     *
     * Everything before this had to pick one. The effect path could put a painterly texture inside
     * the letters and faked its depth with offset copies — no perspective, every letter seen from
     * the same angle. The mesh path had true geometry and could only ramp between two colours on
     * the face. The reference is both at once, and that gap was the largest single reason our
     * version read as an imitation.
     *
     * Set in Anton rather than Vazirmatn, for the second-largest reason: no renderer fixes a
     * letterform, and a text family's Latin against a poster face is most of what the eye was
     * seeing.
     */
    @Test
    fun `a painted face inside real extruded metal`() {
        val fonts = FontResolver { anton() }
        val size = 240f
        val texture = brushedPaint()
        val assets = AssetSource { id -> if (id.value == PAINT) texture else null }

        val rendered = TextTo3D.render(
            layer = Layer.Text(
                id = LayerId("trend"),
                spec = TextSpec(text = "TREND", font = FontRef(family = "Anton"), size = size),
                name = "trend",
                transform = Transform(),
                style = Style.PLAIN_BLACK,
            ),
            geometry = trendLook(size).copy(
                // The join. A gradient stays declared underneath so the recipe still reads, and the
                // pattern wins — a caller who supplies a picture asked for the picture.
                facePattern = Fill.Pattern(
                    asset = ir.pixellab.core.model.AssetId(PAINT),
                    // Larger than one tile across the word: a tile is authored square and a word is
                    // wide, so one tile stretched over it smears every stroke into a streak.
                    scale = Vec2(PAINT_SCALE, PAINT_SCALE),
                ),
            ),
            fonts = fonts,
            width = 1800,
            height = 900,
            supersample = 3,
            assets = assets,
        )
        checkNotNull(rendered) { "the joined path produced no render" }
        write("merged-trend", rendered.width, rendered.height, rendered.pixels)

        // It has to be *painted*, not ramped: a gradient face would have no two neighbouring pixels
        // of unrelated colour, and the paint does. Counting distinct tones inside the word is the
        // cheapest thing that tells those apart.
        val tones = HashSet<Int>()
        for (pixel in rendered.pixels) {
            if ((pixel ushr 24) < 128) continue
            tones += (pixel and 0xF0F0F0)
        }
        check(tones.size > MIN_PAINTED_TONES) {
            "the face carries only ${tones.size} tones — the texture did not reach the mesh"
        }
    }

    /** The same painterly tile the effect path uses, so the two produce the same material. */
    private fun brushedPaint(): ir.pixellab.core.codec.RasterImage {
        val coverage = ir.pixellab.core.imaging.Procedural.pattern(
            ir.pixellab.core.imaging.Procedural.Pattern.MARBLE,
            size = 512,
            repeats = 6,
            seed = 7,
        )
        val grey = ir.pixellab.core.imaging.Raster(coverage.width, coverage.height, 3)
        for (i in 0 until coverage.width * coverage.height) {
            val v = coverage[i % coverage.width, i / coverage.width, 0].coerceIn(0f, 1f)
            grey.data[i * 3] = v
            grey.data[i * 3 + 1] = v
            grey.data[i * 3 + 2] = v
        }
        // Brushed before tinting, so the mode filter cannot vote the minority colour out — the same
        // ordering the effect path had to learn.
        val brushed = ir.pixellab.core.imaging.Artistic.oilPaint(grey, radius = 6, levels = 10)

        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (i in 0 until brushed.pixelCount) {
            val v = brushed.data[i * 3]
            if (v < low) low = v
            if (v > high) high = v
        }
        val span = (high - low).coerceAtLeast(1e-4f)

        val pixels = IntArray(coverage.width * coverage.height)
        for (i in pixels.indices) {
            val n = ((brushed.data[i * 3] - low) / span).coerceIn(0f, 1f)
            val t = Math.pow(n.toDouble(), 2.2).toFloat()
            val r = (0.03f + (0.99f - 0.03f) * t)
            val g = (0.42f + (0.72f - 0.42f) * t)
            val b = (0.40f + (0.52f - 0.40f) * t)
            pixels[i] = (0xFF shl 24) or (byteOf(r) shl 16) or (byteOf(g) shl 8) or byteOf(b)
        }
        return ir.pixellab.core.codec.RasterImage(coverage.width, coverage.height, pixels)
    }

    private fun byteOf(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    @Test
    fun `the reference effect, with what the engine has today`() {
        val fonts = FontResolver { vazirmatn() }
        val size = 240f
        val layer = Layer.Text(
            id = LayerId("trend"),
            spec = TextSpec(
                text = "TREND",
                font = FontRef(
                    family = "Vazirmatn",
                    weight = 900,
                    variations = mapOf(FontRef.AXIS_WEIGHT to 900f),
                ),
                size = size,
            ),
            name = "trend",
            transform = Transform(),
            style = Style.PLAIN_BLACK,
        )

        for ((name, bevel) in listOf("nobevel" to 0f, "bevel" to 0.035f)) {
            val rendered = TextTo3D.render(
                layer = layer,
                geometry = trendLook(size, bevel),
                fonts = fonts,
                width = 1200,
                height = 620,
                supersample = 4,
            )
            checkNotNull(rendered) { "the reference word produced no render" }
            write("diag-$name", rendered.width, rendered.height, rendered.pixels)
        }
    }

    private fun write(name: String, width: Int, height: Int, pixels: IntArray) {
        File(output, "$name.png").writeBytes(png(width, height, pixels))
    }

    private fun png(width: Int, height: Int, pixels: IntArray): ByteArray {
        val raw = java.io.ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                raw.write((pixel shr 16) and 0xFF)
                raw.write((pixel shr 8) and 0xFF)
                raw.write(pixel and 0xFF)
                raw.write((pixel ushr 24) and 0xFF)
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = ByteArray(raw.size() + 1024)
        val written = deflater.deflate(compressed)

        val out = java.io.ByteArrayOutputStream()
        val stream = DataOutputStream(out)
        stream.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, body: ByteArray) {
            stream.writeInt(body.size)
            val tag = type.toByteArray(Charsets.US_ASCII)
            stream.write(tag)
            stream.write(body)
            val crc = CRC32()
            crc.update(tag)
            crc.update(body)
            stream.writeInt(crc.value.toInt())
        }
        val header = java.io.ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(width); writeInt(height); write(8); write(6); write(0); write(0); write(0)
        }
        chunk("IHDR", header.toByteArray())
        chunk("IDAT", compressed.copyOf(written))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private companion object {
        const val PAINT = "paint"

        /** A third of a tile per letter-width, so the brushwork stays at its own scale. */
        const val PAINT_SCALE = 0.33f

        /** A ramp would give far fewer; this separates painted from gradient without a fixture. */
        const val MIN_PAINTED_TONES = 40

        /**
         * The block, ramped front to back.
         *
         * Bright saturated orange where it leaves the face, falling to a deep burnt tone at the far
         * end — stated outright rather than left to whichever way each wall happens to face.
         */
        val ORANGE_BLOCK = Fill.Gradient(
            stops = listOf(
                GradientStop(0f, Color(0.98f, 0.62f, 0.13f)),
                GradientStop(0.45f, Color(0.85f, 0.42f, 0.08f)),
                GradientStop(1f, Color(0.42f, 0.16f, 0.04f)),
            ),
        )

        /**
         * The face: deep teal at the edges, warming to peach across the middle.
         *
         * Read off the reference rather than invented. Its face is not one colour and not a plain
         * two-stop ramp either — the warm band runs through the centre with green on both sides,
         * which is four stops, and the angle carries it up to the right across the whole word.
         */
        val TEAL_TO_PEACH = Fill.Gradient(
            stops = listOf(
                GradientStop(0f, Color(0.04f, 0.28f, 0.33f)),
                GradientStop(0.22f, Color(0.10f, 0.47f, 0.42f)),
                GradientStop(0.40f, Color(0.28f, 0.62f, 0.45f)),
                GradientStop(0.55f, Color(0.97f, 0.76f, 0.58f)),
                GradientStop(0.70f, Color(0.35f, 0.62f, 0.50f)),
                GradientStop(1f, Color(0.05f, 0.30f, 0.36f)),
            ),
            // Diagonal, not vertical. The reference runs its warm band up across the word rather
            // than banding it in horizontal stripes, and a near-vertical ramp is what stripes look
            // like once the letters are wider than they are tall.
            angle = 28f,
        )
    }
}
