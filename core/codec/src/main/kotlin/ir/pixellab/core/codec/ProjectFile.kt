package ir.pixellab.core.codec

import ir.pixellab.core.model.Document
import ir.pixellab.core.model.decodeDocument
import ir.pixellab.core.model.encode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A project and everything it needs to open on another device. */
data class Project(
    val document: Document,
    /** Images and patterns the document references, keyed by asset id. */
    val assets: Map<String, ByteArray> = emptyMap(),
    /** Font files bundled with the project, keyed by file name. */
    val fonts: Map<String, ByteArray> = emptyMap(),
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * The project container.
 *
 * A zip rather than a single JSON file, because a design is a document *plus* the images and fonts
 * it references. Storing only the document means a project that opens on the device it was made on
 * and nowhere else — and, worse, one that silently loses a layer when the user tidies their photos.
 *
 * The document is stored uncompressed-readable JSON at a fixed path so that a future version can
 * migrate a file it does not fully understand, and so a corrupt archive can still be inspected.
 */
object ProjectFile {

    const val EXTENSION = "pxl"
    const val DOCUMENT_ENTRY = "document.json"
    const val ASSET_PREFIX = "assets/"
    const val FONT_PREFIX = "fonts/"

    fun write(project: Project, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            // The document goes in first so a truncated file still yields the structure; the
            // assets are the large part and the part that can be re-linked.
            zip.putNextEntry(ZipEntry(DOCUMENT_ENTRY))
            zip.write(project.document.encode().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for ((id, bytes) in project.assets) {
                zip.putNextEntry(ZipEntry(ASSET_PREFIX + id))
                zip.write(bytes)
                zip.closeEntry()
            }
            for ((name, bytes) in project.fonts) {
                zip.putNextEntry(ZipEntry(FONT_PREFIX + name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    fun encode(project: Project): ByteArray =
        ByteArrayOutputStream().also { write(project, it) }.toByteArray()

    /**
     * Reads a project.
     *
     * Entries are matched by prefix rather than by position, so a file written by a later version
     * that adds a directory still opens — the unknown entries are skipped rather than treated as
     * corruption.
     */
    fun read(input: InputStream): Project {
        var document: Document? = null
        val assets = LinkedHashMap<String, ByteArray>()
        val fonts = LinkedHashMap<String, ByteArray>()

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val bytes = zip.readBytes()
                when {
                    entry.name == DOCUMENT_ENTRY ->
                        document = decodeDocument(bytes.toString(Charsets.UTF_8))
                    entry.name.startsWith(ASSET_PREFIX) ->
                        assets[entry.name.removePrefix(ASSET_PREFIX)] = bytes
                    entry.name.startsWith(FONT_PREFIX) ->
                        fonts[entry.name.removePrefix(FONT_PREFIX)] = bytes
                    else -> Unit
                }
            }
        }

        val loaded = document ?: throw CodecException("this project has no document")
        return Project(loaded, assets, fonts)
    }

    fun decode(bytes: ByteArray): Project = read(bytes.inputStream())

    /**
     * True when the bytes look like a project.
     *
     * A zip and a project are indistinguishable from the magic number alone, so the check is
     * whether the document entry is there — otherwise opening any archive would produce a
     * confusing failure deep inside the reader.
     */
    fun looksLikeProject(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        return runCatching {
            ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.any { it.name == DOCUMENT_ENTRY }
            }
        }.getOrDefault(false)
    }
}
