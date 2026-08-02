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
