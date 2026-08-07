package ir.pixellab.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.pixellab.core.codec.Codecs
import ir.pixellab.core.codec.Project
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.model.AssetId
import ir.pixellab.core.model.Vec2
import ir.pixellab.engine.android.AssetSource
import ir.pixellab.engine.android.ImageSizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the application's own textures live inside the APK, and how they are named once decoded. */
const val BUNDLED_TEXTURES = "textures"

/**
 * The id a bundled texture answers to.
 *
 * Prefixed so a built-in can never collide with an asset the user's own project brought with it,
 * and derived from the file name so a preset can name its texture without a registry to look it up
 * in — the preset and the file agree because they spell the same thing.
 */
fun bundledId(fileName: String) = AssetId("bundled:" + fileName.substringBeforeLast('.'))

/**
 * The decoded images a document refers to.
 *
 * Masks and placed photographs are referenced by id rather than embedded, because the same mask is
 * routinely shared between layers and a document that carried its pixels would be copied every time
 * a layer was. This is where those ids become pixels.
 *
 * Published the same way the font library is: as a *new* [AssetSource] each time the contents
 * change, because the canvas decides whether to drop its cached mask textures by comparing source
 * identity. A store that mutated in place would leave a repainted mask showing its old shape.
 */
class AssetStore {

    var source: AssetSource by mutableStateOf(AssetSource.NONE)
        private set

    /** How large each placed image is, for the measurer that positions selection handles. */
    val sizes = ImageSizes { id -> decoded[id.value]?.let { Vec2(it.width.toFloat(), it.height.toFloat()) } }

    private val decoded = LinkedHashMap<String, RasterImage>()

    /**
     * Decodes everything a project brought with it.
     *
     * Off the main thread and all at once rather than lazily per layer: a project's assets are what
     * makes it look right, and decoding them as each layer first draws means the document appears
     * and then visibly corrects itself.
     */
    suspend fun load(project: Project) = withContext(Dispatchers.IO) {
        val fresh = LinkedHashMap<String, RasterImage>()
        for ((id, bytes) in project.assets) {
            // One unreadable asset must not cost the rest: a project half-decoded is still a
            // project the user can work in and re-link.
            runCatching { Codecs.decode(bytes) }.getOrNull()?.let { fresh[id] = it }
        }
        withContext(Dispatchers.Main) { replace(fresh) }
    }

    fun put(id: AssetId, image: RasterImage) {
        replace(LinkedHashMap(decoded).also { it[id.value] = image })
    }

    /**
     * Decodes the textures shipped inside the application.
     *
     * These are the exception to this app's rule that it carries no asset pack, and the exception
     * earns itself: a built-in style is only built-in if everything it references is present the
     * first time it is tapped. A preset whose texture had to be downloaded, or generated on the
     * device and then differ between versions, would be a preset that sometimes works.
     *
     * Failures are swallowed per file for the same reason a project's assets are: one texture that
     * will not decode must not cost the others, and a style missing its pattern still applies
     * everything else it carries.
     */
    suspend fun loadBundled(assets: android.content.res.AssetManager) = withContext(Dispatchers.IO) {
        val fresh = LinkedHashMap(decoded)
        val names = runCatching { assets.list(BUNDLED_TEXTURES) }.getOrNull().orEmpty()
        for (name in names) {
            val bytes = runCatching {
                assets.open("$BUNDLED_TEXTURES/$name").use { it.readBytes() }
            }.getOrNull() ?: continue
            runCatching { Codecs.decode(bytes) }.getOrNull()?.let {
                fresh[bundledId(name).value] = it
            }
        }
        withContext(Dispatchers.Main) { replace(fresh) }
    }

    fun clear() = replace(LinkedHashMap())

    /** What a save has to write back, so an asset survives a round trip through the project file. */
    fun encoded(): Map<String, ByteArray> = decoded.mapNotNull { (id, image) ->
        Codecs.encoderFor(ir.pixellab.core.codec.Format.PNG)?.let { id to it.encode(image) }
    }.toMap()

    private fun replace(fresh: LinkedHashMap<String, RasterImage>) {
        decoded.clear()
        decoded.putAll(fresh)
        val snapshot = fresh.toMap()
        source = AssetSource { id -> snapshot[id.value] }
    }
}
