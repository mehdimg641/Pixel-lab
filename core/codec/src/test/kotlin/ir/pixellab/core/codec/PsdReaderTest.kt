package ir.pixellab.core.codec

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.BlendMode
import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Runs against the real commercial PSDs when they are present.
 *
 * A format parser tested only on files it wrote itself proves nothing: every interesting failure in
 * this area comes from a block some other program emitted — a padded length, a key from a newer
 * Photoshop, a run that overshoots its row. The synthetic tests below cover the arithmetic; these
 * cover reality, and they skip rather than fail when the samples are unavailable so the suite still
 * runs anywhere.
 */
class PsdSampleTest {

    private val samples: List<File> by lazy {
        val root = System.getProperty("pixellab.samples")?.let(::File)
            ?: File(System.getProperty("java.io.tmpdir"))
        if (!root.isDirectory) return@lazy emptyList()
        root.walkTopDown()
            .maxDepth(SEARCH_DEPTH)
            .filter { it.isFile && it.extension.lowercase() in setOf("psd", "psb") }
            .toList()
    }

    private fun requireSamples(): List<File> {
        assumeTrue(samples.isNotEmpty(), "no PSD samples on this machine")
        return samples
    }

    @Test
    fun `every sample parses its header`() {
        for (file in requireSamples()) {
            val document = PsdReader.read(file.readBytes(), pixels = false)
            (document.width > 0) shouldBe true
            (document.height > 0) shouldBe true
            (document.depth in setOf(8, 16, 32)) shouldBe true
        }
    }

    @Test
    fun `every sample yields layers with names`() {
        for (file in requireSamples()) {
            val document = PsdReader.read(file.readBytes(), pixels = false)
            if (document.layers.isEmpty()) continue
            // A parser whose cursor has drifted produces layers with empty or garbage names long
            // before it produces an exception, so this is the earliest honest signal.
            val named = document.layers.count { it.name.isNotBlank() }
            (named > document.layers.size / 2) shouldBe true
        }
    }

    @Test
    fun `group markers pair up`() {
        for (file in requireSamples()) {
            val document = PsdReader.read(file.readBytes(), pixels = false)
            val opens = document.layers.count { it.isGroupStart }
            val closes = document.layers.count { it.isGroupEnd }
            // Photoshop writes a hidden divider layer for every group; an unbalanced count means
            // the section-type block was read from the wrong offset.
            opens shouldBe closes
        }
    }

    @Test
    fun `the reference styles carry the effects the analysis found`() {
        val documents = requireSamples().map { PsdReader.read(it.readBytes(), pixels = false) }
        val effects = documents.flatMap { it.layers }.flatMap { it.effects }
        // The teardown counted 99 bevels across these files; recovering none would mean the
        // descriptor reader is silently bailing out.
        (effects.isNotEmpty()) shouldBe true
        // Photoshop's own four-character keys: DrSh drop shadow, ebbl bevel and emboss,
        // FrFX stroke, OrGl outer glow.
        (effects.any { it.key in setOf("DrSh", "ebbl", "FrFX", "OrGl", "IrGl", "IrSh", "SoFi") }) shouldBe true
    }

    @Test
    fun `bevel depth comes back as a number, not a blob`() {
        val documents = requireSamples().map { PsdReader.read(it.readBytes(), pixels = false) }
        val bevels = documents.flatMap { it.layers }.flatMap { it.effects }.filter { it.key == "ebbl" }
        assumeTrue(bevels.isNotEmpty(), "no bevels in the available samples")
        // The point of reading descriptors generically is that a parameter arrives as a value the
        // importer can use rather than bytes it has to guess at.
        val depths = bevels.mapNotNull { it.values["srgR"] ?: it.values["Dpth"] }
        (depths.any { it is PsdValue.Unit || it is PsdValue.Number }) shouldBe true
    }

    @Test
    fun `text layers give up their strings`() {
        val documents = requireSamples().map { PsdReader.read(it.readBytes(), pixels = false) }
        val texts = documents.flatMap { it.layers }.mapNotNull { it.text }
        assumeTrue(texts.isNotEmpty(), "no text layers in the available samples")
        (texts.any { it.text.isNotBlank() }) shouldBe true
    }

    @Test
    fun `no sample produces a parser warning about a lost cursor`() {
        for (file in requireSamples()) {
            val document = PsdReader.read(file.readBytes(), pixels = false)
            // Unexpected signatures are the symptom of reading from the wrong offset. Other
            // warnings — a legacy effect block, an unread compression — are informational.
            val lost = document.warnings.filter { it.startsWith("unexpected") }
            if (lost.isNotEmpty()) error("${file.name}: $lost")
        }
    }

    @Test
    fun `pixel data decodes for the smallest sample`() {
        val files = requireSamples().sortedBy { it.length() }
        val document = PsdReader.read(files.first().readBytes(), pixels = true)
        val withPixels = document.layers.filter { it.channels.isNotEmpty() }
        assumeTrue(withPixels.isNotEmpty(), "sample has no raster layers")
        for (layer in withPixels) {
            for (channel in layer.channels) {
                // A channel whose decompressed length does not match its rectangle means the run
                // decoder lost sync, which shows up on screen as a diagonal tear.
                val expected = layer.bounds.width * layer.bounds.height * (document.depth / 8)
                channel.samples.size shouldBe expected
            }
        }
    }

    private companion object {
        const val SEARCH_DEPTH = 6
    }
}

class PsdReaderTest {

    /** The smallest legal document: header, four empty sections. */
    private fun minimalPsd(
        width: Int = 4,
        height: Int = 3,
        version: Int = 1,
        depth: Int = 8,
        channels: Int = 3,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        fun i32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }

        out.write("8BPS".toByteArray())
        u16(version)
        repeat(6) { out.write(0) }
        u16(channels)
        i32(height)
        i32(width)
        u16(depth)
        u16(3) // RGB
        i32(0) // colour mode data
        i32(0) // image resources
        // PSB widens this length to 64 bits, which is most of what the two formats differ by.
        if (version == 2) i32(0)
        i32(0) // layer and mask information
        u16(0) // composite compression: raw
        repeat(width * height * channels * (depth / 8)) { out.write(0x7F) }
        return out.toByteArray()
    }

    @Test
    fun `a minimal document reads back its header`() {
        val document = PsdReader.read(minimalPsd(width = 640, height = 480))
        document.width shouldBe 640
        document.height shouldBe 480
        document.depth shouldBe 8
        document.colorMode shouldBe PsdColorMode.RGB
        document.large shouldBe false
    }

    @Test
    fun `the version field decides PSD from PSB, not the extension`() {
        PsdReader.read(minimalPsd(version = 2)).large shouldBe true
        Format.detect(minimalPsd(version = 2)) shouldBe Format.PSB
        Format.detect(minimalPsd(version = 1)) shouldBe Format.PSD
    }

    @Test
    fun `a file that is not a PSD is refused clearly`() {
        assertThrows<PsdException> { PsdReader.read("not a psd at all".toByteArray()) }
    }

    @Test
    fun `an unsupported version is refused rather than misread`() {
        val bytes = minimalPsd(version = 7)
        assertThrows<PsdException> { PsdReader.read(bytes) }
    }

    @Test
    fun `the composite decodes at the document's size`() {
        val document = PsdReader.read(minimalPsd(width = 4, height = 3))
        document.composite.size shouldBe 3
        document.composite.first().samples.size shouldBe 12
    }

    @Test
    fun `sixteen bit documents are read at two bytes a sample`() {
        val document = PsdReader.read(minimalPsd(width = 4, height = 3, depth = 16))
        document.depth shouldBe 16
        document.composite.first().samples.size shouldBe 24
    }

    @Test
    fun `every photoshop blend key maps to a mode`() {
        PsdReader.blendModeOf("norm") shouldBe BlendMode.NORMAL
        PsdReader.blendModeOf("mul ") shouldBe BlendMode.MULTIPLY
        PsdReader.blendModeOf("lddg") shouldBe BlendMode.LINEAR_DODGE
        PsdReader.blendModeOf("hMix") shouldBe BlendMode.HARD_MIX
        PsdReader.blendModeOf("lum ") shouldBe BlendMode.LUMINOSITY
        // Keys are space-padded to four characters, so trimming has to happen before matching.
        PsdReader.blendModeOf("div ") shouldBe BlendMode.COLOR_DODGE
        // An unfamiliar key falls back rather than throwing; a future Photoshop must still open.
        PsdReader.blendModeOf("zzzz") shouldBe BlendMode.NORMAL
    }
}
