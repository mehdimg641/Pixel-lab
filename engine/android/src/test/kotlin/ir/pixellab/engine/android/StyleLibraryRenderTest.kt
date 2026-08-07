package ir.pixellab.engine.android

import ir.pixellab.core.editor.Library
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.render.EffectRaster
import ir.pixellab.core.render.Raster
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Every built-in style, rendered.
 *
 * A style preset is a list of effects, and a list of effects is the easiest thing in this codebase
 * to get plausibly wrong: the names are right, the numbers look sensible, and the picture is grey.
 * Nothing here can be checked by inspecting the value — the only question worth asking is what came
 * out, so this renders all of them and writes the pictures where they can be looked at.
 *
 * It also holds the compositor to its own catalogue. The CPU path implemented six of the fourteen
 * effects and silently ignored the rest, so a preset built from glows rendered as plain text and
 * nothing failed. The assertion below is deliberately blunt for that reason: a preset has to change
 * the picture it is applied to. A style that renders identically to bare type is a style that is not
 * running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class StyleLibraryRenderTest {

    private val output = File("build/renders/styles").apply { mkdirs() }

    @Test
    fun `every built-in style renders and changes the picture`() {
        val word = silhouette("طرح", WIDTH, HEIGHT)
        check(word.pixels.any { (it ushr 24) > 0 }) { "the word produced no silhouette" }

        val untouched = word.pixels.count { (it ushr 24) > 0 }
        val inert = mutableListOf<String>()

        for ((index, preset) in Library.styles.withIndex()) {
            val rendered = EffectRaster.apply(word, preset.style)
            // The preset's own ASCII handle, and the index in front of it so the directory reads in
            // catalogue order. Formatted against the root locale deliberately: this suite runs under
            // a Persian locale, and the default formatter would write the index in Persian digits —
            // giving every file a name the shell cannot easily reach.
            write(String.format(java.util.Locale.ROOT, "%02d-%s", index, preset.id), rendered)
            check(preset.id.isNotEmpty()) { "'${preset.name}' has no id to file it under" }

            // Two independent ways a style can prove it ran, because different families move
            // different things: a glow or a shadow adds pixels the bare word never had, while a
            // gradient or a bevel repaints the ones it did.
            var added = 0
            var repainted = 0
            for (i in rendered.pixels.indices) {
                val before = word.pixels[i]
                val after = rendered.pixels[i]
                if ((before ushr 24) == 0 && (after ushr 24) > 0) added++
                else if ((before ushr 24) > 0 && (after and RGB) != (before and RGB)) repainted++
            }
            if (added == 0 && repainted < untouched / 20) inert += "${preset.name} ($added added, $repainted repainted)"
        }

        check(inert.isEmpty()) { "these styles rendered as bare type: ${inert.joinToString("; ")}" }
    }

    /** White type on transparency — the input every layer style is applied to. */
    private fun silhouette(text: String, width: Int, height: Int): Raster {
        val spec = TextSpec(
            text = text,
            font = FontRef(
                family = "Vazirmatn",
                weight = 900,
                variations = mapOf(FontRef.AXIS_WEIGHT to 900f),
            ),
            size = 220f,
        )
        val bitmap = android.graphics.Bitmap.createBitmap(
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        val typeface = TypefaceLoader().load(vazirmatn(), mapOf(FontRef.AXIS_WEIGHT to 900f))
        val paint = TextRasterizer().paintFor(spec, typeface ?: android.graphics.Typeface.DEFAULT).apply {
            color = android.graphics.Color.WHITE
        }
        canvas.drawText(text, MARGIN, height * BASELINE, paint)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return Raster(width, height, pixels)
    }

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
            axes = mapOf(FontRef.AXIS_WEIGHT to 100f..900f),
            features = emptySet(),
            script = Script.ARABIC,
            hasPersianDigits = true,
            hasTatweel = true,
        )
    }

    private fun write(name: String, raster: Raster) {
        File(output, "$name.png").writeBytes(png(raster.width, raster.height, raster.pixels))
    }

    private fun png(width: Int, height: Int, pixels: IntArray): ByteArray {
        val raw = java.io.ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                raw.write((p shr 16) and 0xFF)
                raw.write((p shr 8) and 0xFF)
                raw.write(p and 0xFF)
                raw.write((p ushr 24) and 0xFF)
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()

        val out = java.io.ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val typed = type.toByteArray() + body
            data.write(typed)
            val crc = CRC32()
            crc.update(typed)
            data.writeInt(crc.value.toInt())
        }

        val header = java.io.ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(width)
            writeInt(height)
            write(byteArrayOf(8, 6, 0, 0, 0))
        }
        chunk("IHDR", header.toByteArray())
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 420
        const val MARGIN = 90f
        const val BASELINE = 0.66f
        const val RGB = 0xFFFFFF
        const val BUFFER = 8192
    }
}
