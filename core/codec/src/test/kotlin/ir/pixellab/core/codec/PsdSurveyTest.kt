package ir.pixellab.core.codec

import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Prints what the reader recovered from every available sample.
 *
 * Not an assertion but a record: the numbers here are what the import step will be judged against,
 * and having them in the build output means a regression shows up as a changed count rather than as
 * a style that quietly imports without its bevel.
 */
class PsdSurveyTest {

    @Test
    fun `survey the available samples`() {
        val root = System.getProperty("pixellab.samples")?.let(::File)
        assumeTrue(root != null && root.isDirectory, "no sample directory configured")

        val files = root!!.walkTopDown().maxDepth(6)
            .filter { it.isFile && it.extension.lowercase() in setOf("psd", "psb") }
            .sortedBy { it.name }
            .toList()
        assumeTrue(files.isNotEmpty(), "no samples found")

        for (file in files) {
            val document = PsdReader.read(file.readBytes(), pixels = false)
            val effects = document.layers.flatMap { it.effects }
            println(
                "%-34s %5dx%-5d d=%-2d layers=%3d groups=%2d text=%2d fx=%3d  %s".format(
                    file.name.take(34), document.width, document.height, document.depth,
                    document.layers.size,
                    document.layers.count { it.isGroupStart },
                    document.layers.count { it.text != null },
                    effects.size,
                    effects.map { it.key }.distinct().sorted().joinToString(","),
                ),
            )
            document.warnings.distinct().take(2).forEach { println("      ! $it") }
        }
    }
}
