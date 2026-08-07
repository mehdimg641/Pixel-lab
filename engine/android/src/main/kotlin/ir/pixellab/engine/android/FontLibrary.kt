package ir.pixellab.engine.android

import ir.pixellab.core.fonts.FontCatalog
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.FontScanner
import ir.pixellab.core.fonts.ScanResult
import ir.pixellab.core.model.FontRef
import java.io.File

/**
 * The device's fonts, scanned and grouped.
 *
 * Scanning is done with the project's own OpenType parser rather than by asking the platform,
 * because the platform tells you almost nothing: no variable axes, no stylistic sets, no idea
 * whether a file covers Arabic. Those are exactly the facts the font picker has to sort by, and the
 * parser is already validated against the 312 supplied fonts.
 *
 * Directories are scanned rather than files: the user adds fonts by dropping them in, at any time,
 * and a rescan has to pick them up without the project referencing them by path.
 */
class FontLibrary(private val roots: List<File>) {

    private var scan: ScanResult = ScanResult(emptyList(), emptyList())

    var catalog: FontCatalog = FontCatalog(emptyList())
        private set

    /** Files that looked like fonts and could not be parsed, so the picker can say which. */
    val failed: List<String> get() = scan.failed

    val fontCount: Int get() = scan.fonts.size

    /**
     * Walks every root and rebuilds the catalogue.
     *
     * Deliberately synchronous and deliberately cheap to call again: adding a font is a normal act
     * in this app, not a setup step, and a rescan of a few hundred files reads only the tables the
     * parser needs rather than whole files.
     */
    fun rescan(): FontCatalog {
        val paths = roots
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .maxDepth(MAX_DEPTH)
                    .filter { it.isFile && it.extension.lowercase() in FONT_EXTENSIONS }
                    .map { it.absolutePath }
                    .toList()
            }
        // Sorted so a rescan produces the same order, which keeps the picker from reshuffling
        // under the user's finger after they add one font.
        scan = FontScanner { path -> runCatching { File(path).readBytes() }.getOrNull() }
            .scan(paths.sorted())
        catalog = FontCatalog(scan.fonts)
        return catalog
    }

    /**
     * Resolves a layer's font reference, degrading rather than failing.
     *
     * Returns null only when the catalogue is empty — a project opened before any font has been
     * added still has to draw something rather than crash.
     */
    fun resolve(ref: FontRef): FontFile? = catalog.resolve(ref).file

    fun warningFor(ref: FontRef): String? = catalog.resolve(ref).warning

    companion object {
        /** Extensions worth opening. A collection folder is full of licences and previews too. */
        val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

        /**
         * Deep enough for the way font collections actually arrive — a zip extracted into a
         * family folder holding a weights folder — without walking an entire card.
         */
        const val MAX_DEPTH = 6
    }
}
