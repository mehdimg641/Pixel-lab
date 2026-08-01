package ir.pixellab.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CanvasSpec(
    val width: Int,
    val height: Int,
    /** Pixels per inch, carried through to export metadata. */
    val dpi: Int = 72,
    val background: Fill? = null,
) {
    init {
        require(width > 0 && height > 0) { "canvas must be positive, got ${width}x$height" }
    }

    val size: Vec2 get() = Vec2(width.toFloat(), height.toFloat())
    val bounds: Rect get() = Rect(0f, 0f, width.toFloat(), height.toFloat())
}

/**
 * Document-wide light direction.
 *
 * Effects with `useGlobalLight` read from here, so dragging one slider re-lights every bevel and
 * shadow in the design at once.
 */
@Serializable
data class GlobalLight(val angle: Float = 90f, val altitude: Float = 30f)

/**
 * A design document: the single source of truth the renderer consumes.
 *
 * The whole model is immutable. Edits produce a new [Document] that shares every untouched subtree,
 * which makes undo a bounded list of past values rather than a hand-written inverse for each
 * command.
 */
@Serializable
data class Document(
    val id: DocumentId,
    val canvas: CanvasSpec,
    /** Bottom-most layer first, matching paint order. */
    val layers: List<Layer> = emptyList(),
    val globalLight: GlobalLight = GlobalLight(),
    /**
     * Working space, precision and blend transfer curve. Stored per document because a file
     * imported from Photoshop must keep compositing the way Photoshop composited it.
     */
    val color: ColorSettings = ColorSettings(),
    val name: String = "Untitled",
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    /** Depth-first walk in paint order, groups yielded before their children. */
    fun walk(): Sequence<Layer> = sequence { yieldAll(walkList(layers)) }

    fun findLayer(id: LayerId): Layer? = walk().firstOrNull { it.id == id }

    /** Applies [transform] to the layer with [id], rebuilding only the path down to it. */
    fun mapLayer(id: LayerId, transform: (Layer) -> Layer): Document =
        copy(layers = mapList(layers, id, transform))

    fun removeLayer(id: LayerId): Document = copy(layers = removeFromList(layers, id))

    companion object {
        /** Bumped whenever the persisted shape changes; migrations key off it. */
        const val SCHEMA_VERSION: Int = 1

        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = false
            ignoreUnknownKeys = true
            classDiscriminator = "kind"
        }

        fun blank(id: DocumentId, width: Int, height: Int, name: String = "Untitled") = Document(
            id = id,
            canvas = CanvasSpec(width, height, background = Fill.Solid(Color.WHITE)),
            name = name,
        )

        private fun walkList(layers: List<Layer>): Sequence<Layer> = sequence {
            for (layer in layers) {
                yield(layer)
                if (layer is Layer.Group) yieldAll(walkList(layer.children))
            }
        }

        private fun mapList(layers: List<Layer>, id: LayerId, f: (Layer) -> Layer): List<Layer> =
            layers.map { layer ->
                when {
                    layer.id == id -> f(layer)
                    layer is Layer.Group -> layer.copy(children = mapList(layer.children, id, f))
                    else -> layer
                }
            }

        private fun removeFromList(layers: List<Layer>, id: LayerId): List<Layer> =
            layers.mapNotNull { layer ->
                when {
                    layer.id == id -> null
                    layer is Layer.Group -> layer.copy(children = removeFromList(layer.children, id))
                    else -> layer
                }
            }
    }
}

fun Document.encode(): String = Document.json.encodeToString(Document.serializer(), this)

fun decodeDocument(text: String): Document = Document.json.decodeFromString(Document.serializer(), text)

/**
 * Bounded undo history over whole-document snapshots.
 *
 * Structural sharing makes a snapshot cost roughly the size of the edited path, so keeping fifty of
 * them is cheaper than maintaining an inverse operation for every command. Continuous gestures must
 * be wrapped in a single [transaction] or a one-second drag would fill the stack.
 */
class History(private val limit: Int = 50) {
    private val past = ArrayDeque<Document>()
    private val future = ArrayDeque<Document>()
    private var pending: Document? = null

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val depth: Int get() = past.size

    /** Records [before] as an undo point. No-op while a transaction is open. */
    fun record(before: Document) {
        if (pending != null) return
        push(before)
    }

    /**
     * Coalesces everything [body] does into one undo entry. Used for drags and slider scrubs, where
     * every intermediate frame would otherwise become its own history step.
     */
    fun <T> transaction(before: Document, body: () -> T): T {
        val outer = pending
        if (outer == null) pending = before
        try {
            return body()
        } finally {
            if (outer == null) {
                pending = null
                push(before)
            }
        }
    }

    fun undo(current: Document): Document? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current)
        return previous
    }

    fun redo(current: Document): Document? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        return next
    }

    fun clear() {
        past.clear()
        future.clear()
        pending = null
    }

    private fun push(document: Document) {
        past.addLast(document)
        while (past.size > limit) past.removeFirst()
        future.clear()
    }
}
