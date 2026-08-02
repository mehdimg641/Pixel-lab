package ir.pixellab.core.editor

import ir.pixellab.core.canvas.Handle
import ir.pixellab.core.canvas.HandleConfig
import ir.pixellab.core.canvas.Handles
import ir.pixellab.core.canvas.SnapConfig
import ir.pixellab.core.canvas.SnapEngine
import ir.pixellab.core.canvas.Viewport
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.History
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.LayerMask
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.VectorMask
import ir.pixellab.core.model.with
import ir.pixellab.core.model.withId
import ir.pixellab.core.model.withStyle
import ir.pixellab.core.model.withTransform
import ir.pixellab.core.render.EffectRegistry
import ir.pixellab.core.render.ParameterValue
import ir.pixellab.core.render.builtinEffectRegistry

/**
 * The editor's behaviour, with no reference to any UI toolkit.
 *
 * Every rule that decides what a touch does — which layer a tap selects, whether a drag snaps, when
 * the canvas moves out from under a sheet, what counts as one undo step — lives here so it can be
 * driven from a test instead of from a screen. The Compose layer above renders [state] and forwards
 * gestures; it holds no logic of its own.
 */
class Editor(
    document: Document,
    private val bounds: LayerBounds,
    private val registry: EffectRegistry = builtinEffectRegistry,
    private val handleConfig: HandleConfig = HandleConfig(),
    private val snapConfig: SnapConfig = SnapConfig(),
    historyLimit: Int = 50,
) {
    private val history = History(historyLimit)

    var state: EditorState = EditorState(document)
        private set

    init {
        state = state.copy(canUndo = history.canUndo, canRedo = history.canRedo)
    }

    // ---- viewport ------------------------------------------------------------------------------

    fun resizeScreen(size: Vec2, fitIfFirst: Boolean = true) {
        val viewport = state.viewport.copy(screenSize = size)
        state = state.copy(
            viewport = if (fitIfFirst && state.viewport.screenSize == Vec2.ZERO) {
                viewport.fit(state.document.canvas.size, padding = FIT_PADDING)
            } else {
                viewport
            },
        )
    }

    fun fitCanvas() {
        state = state.copy(viewport = state.viewport.fit(state.document.canvas.size, FIT_PADDING))
    }

    /** Restores a camera — reopening a project should put the user back where they were. */
    fun setViewport(viewport: Viewport) {
        state = state.copy(viewport = viewport)
    }

    /**
     * Replaces the whole document, for a project load or a PSD import.
     *
     * @param recordHistory false when the document is being *loaded* rather than edited; recording
     *   it would let the first undo take the user back to whatever was open before.
     */
    fun replaceDocument(document: Document, recordHistory: Boolean = true) {
        if (recordHistory) history.record(state.document) else history.clear()
        state = state.copy(document = document, canUndo = history.canUndo, canRedo = history.canRedo)
        pruneSelection()
    }

    /** A two-finger gesture on empty space. Never recorded: the camera is not part of the document. */
    fun transformViewport(pan: Vec2, scaleFactor: Float, rotationDegrees: Float, pivot: Vec2) {
        state = state.copy(viewport = state.viewport.transformBy(pan, scaleFactor, rotationDegrees, pivot))
    }

    fun panViewport(screenDelta: Vec2) {
        state = state.copy(viewport = state.viewport.panBy(screenDelta))
    }

    // ---- selection -----------------------------------------------------------------------------

    /**
     * Selects the topmost layer under a screen point, or clears the selection over empty space.
     *
     * Topmost, not first-found: the document lists layers bottom-up in paint order, so walking it
     * forwards selects whatever is furthest *behind* the finger.
     */
    fun tapCanvas(screen: Vec2, additive: Boolean = false) {
        val hit = layersUnder(screen).firstOrNull()
        state = when {
            hit == null -> state.copy(selection = Selection.NONE, pickCandidates = emptyList())
            additive -> state.copy(selection = state.selection.toggle(hit), pickCandidates = emptyList())
            else -> state.copy(selection = Selection.of(hit), pickCandidates = emptyList())
        }
        if (state.selection.isEmpty && state.sheet.content?.subject != null) closeSheet()
    }

    /**
     * Offers every layer under the point rather than selecting one.
     *
     * Without this a layer completely covered by another is unreachable on a touch screen; there is
     * no equivalent of clicking through in a layer panel when the panel is a sheet away.
     */
    fun longPressCanvas(screen: Vec2) {
        state = state.copy(pickCandidates = layersUnder(screen))
    }

    fun select(id: LayerId, additive: Boolean = false) {
        state = state.copy(
            selection = if (additive) state.selection.toggle(id) else Selection.of(id),
            pickCandidates = emptyList(),
        )
    }

    fun clearSelection() {
        state = state.copy(selection = Selection.NONE, pickCandidates = emptyList())
    }

    /** Selectable layers under a screen point, topmost first. */
    fun layersUnder(screen: Vec2): List<LayerId> =
        state.document.walk()
            .filter { it.visible && !it.locked && it !is Layer.Group }
            .filter { layer ->
                Handles.layout(bounds.of(layer), layer.transform, state.viewport, handleConfig)
                    .containsPoint(screen)
            }
            .map { it.id }
            .toList()
            .asReversed()

    /** The handle under a screen point on the current selection, if any. */
    fun handleUnder(screen: Vec2): Handle? {
        val layer = state.primaryLayer ?: return null
        return Handles.layout(bounds.of(layer), layer.transform, state.viewport, handleConfig)
            .hitTest(screen, handleConfig)
    }

    // ---- direct manipulation -------------------------------------------------------------------

    /**
     * Starts a move, resize or rotate.
     *
     * The document at this instant is what undo returns to, so a drag of a hundred frames costs one
     * history entry rather than a hundred.
     */
    fun beginDrag(handle: Handle, screen: Vec2) {
        val layer = state.primaryLayer ?: return
        if (layer.locked) return
        history.record(state.document)
        state = state.copy(
            drag = DragSession(handle, layer.id, layer.transform, state.viewport.toCanvas(screen)),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    fun dragTo(screen: Vec2, lockAspect: Boolean = false, fromCentre: Boolean = false) {
        val session = state.drag ?: return
        val layer = state.document.findLayer(session.layer) ?: return
        val local = bounds.of(layer)
        val current = state.viewport.toCanvas(screen)

        if (session.handle == Handle.ROTATE) {
            val turned = Handles.rotate(local, session.startTransform, session.startCanvas, current, snap = true)
            state = state.copy(
                document = state.document.mapLayer(session.layer) { it.withTransform(turned) },
                guides = emptyList(),
            )
            return
        }

        val moved = Handles.resize(
            handle = session.handle,
            bounds = local,
            start = session.startTransform,
            dragCanvas = current - session.startCanvas,
            lockAspect = lockAspect,
            fromCentre = fromCentre,
            config = handleConfig,
        )

        // Only a move snaps. Snapping a resize would fight the finger on both axes at once, and the
        // handle would stop tracking it.
        val snap = if (state.snapEnabled && session.handle == Handle.BODY) {
            SnapEngine.snap(
                moving = Handles.canvasBounds(local, moved),
                canvas = state.document.canvas.size,
                others = otherBounds(session.layer),
                viewport = state.viewport,
                config = snapConfig,
            )
        } else {
            null
        }

        val settled = if (snap != null && snap.snapped) {
            moved.copy(translation = moved.translation + snap.offset)
        } else {
            moved
        }
        state = state.copy(
            document = state.document.mapLayer(session.layer) { it.withTransform(settled) },
            guides = snap?.guides.orEmpty(),
        )
    }

    fun endDrag() {
        state = state.copy(drag = null, guides = emptyList())
    }

    // ---- parameters ----------------------------------------------------------------------------

    /**
     * Sets one parameter on one effect.
     *
     * @param continuous true while a slider is being dragged. The first frame of a scrub records
     *   history and the rest do not, which is what turns a one-second drag into a single undo step
     *   instead of several hundred.
     */
    fun setEffectParameter(
        layer: LayerId,
        effectIndex: Int,
        key: String,
        value: ParameterValue,
        continuous: Boolean = false,
    ) {
        val target = state.document.findLayer(layer) ?: return
        val effects = target.style.effects
        if (effectIndex !in effects.indices) return
        if (!continuous || !scrubbing) history.record(state.document)
        scrubbing = continuous

        val updated = effects.toMutableList()
        updated[effectIndex] = registry.write(effects[effectIndex], key, value)
        state = state.copy(
            document = state.document.mapLayer(layer) { it.withStyle(it.style.copy(effects = updated)) },
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    /** Ends a scrub so the next change starts a new undo entry. */
    fun endScrub() {
        scrubbing = false
    }

    fun readEffectParameter(layer: LayerId, effectIndex: Int, key: String): ParameterValue? {
        val effects = state.document.findLayer(layer)?.style?.effects ?: return null
        val effect = effects.getOrNull(effectIndex) ?: return null
        return registry.read(effect, key)
    }

    fun addEffect(layer: LayerId, effect: Effect) {
        history.record(state.document)
        state = state.copy(
            document = state.document.mapLayer(layer) { it.withStyle(it.style.withEffect(effect)) },
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        val index = (state.document.findLayer(layer)?.style?.effects?.size ?: 1) - 1
        openSheet(SheetContent.EffectParameters(layer, index), SheetDetent.HALF)
    }

    fun removeEffect(layer: LayerId, effectIndex: Int) {
        val effects = state.document.findLayer(layer)?.style?.effects ?: return
        if (effectIndex !in effects.indices) return
        history.record(state.document)
        state = state.copy(
            document = state.document.mapLayer(layer) {
                it.withStyle(it.style.copy(effects = effects.filterIndexed { i, _ -> i != effectIndex }))
            },
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        // The sheet was editing an effect that no longer exists.
        val open = state.sheet.content
        if (open is SheetContent.EffectParameters && open.layer == layer && open.effectIndex == effectIndex) {
            closeSheet()
        }
    }

    /** Moves an effect within the stack. Order matters: three strokes paint widest first. */
    fun moveEffect(layer: LayerId, from: Int, to: Int) {
        val effects = state.document.findLayer(layer)?.style?.effects ?: return
        if (from !in effects.indices || to !in effects.indices || from == to) return
        history.record(state.document)
        val reordered = effects.toMutableList().apply { add(to, removeAt(from)) }
        state = state.copy(
            document = state.document.mapLayer(layer) { it.withStyle(it.style.copy(effects = reordered)) },
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    fun applyStyle(layer: LayerId, style: Style) {
        history.record(state.document)
        state = state.copy(
            document = state.document.mapLayer(layer) { it.withStyle(style) },
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    // ---- adding and removing ---------------------------------------------------------------------

    /**
     * Adds a layer at the top of the stack and selects it.
     *
     * Selecting it is not a convenience: a new layer lands in the middle of the canvas with nothing
     * to distinguish it, and leaving the previous selection in place means the user's next drag
     * moves the wrong thing. Adding is also one undo step, so a mis-tap on the text tool costs one
     * press rather than leaving an empty layer behind.
     */
    fun addLayer(layer: Layer): LayerId {
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(layers = state.document.layers + layer),
            selection = Selection.of(layer.id),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return layer.id
    }

    /**
     * An id no layer in the document is using.
     *
     * Here rather than in the interface because the document is the only thing that knows what is
     * taken — including inside groups, and including layers that arrived from a PSD with ids the
     * app never chose. A caller counting layers gets a collision the first time one is deleted.
     */
    fun nextLayerId(prefix: String): LayerId {
        val used = state.document.walk().mapTo(HashSet()) { it.id.value }
        var n = 1
        while ("$prefix-$n" in used) n++
        return LayerId("$prefix-$n")
    }

    // ---- structure ------------------------------------------------------------------------------

    /**
     * Wraps the selected layers in a group, in place of the topmost of them.
     *
     * In place of the topmost, not appended: a group that jumps to the front of the stack changes
     * what covers what, and the user grouped those layers precisely because of how they already sit.
     *
     * Only top-level layers are gathered. Grouping across two different parents would have to move
     * layers between them, which is a reparent rather than a group, and doing it silently loses the
     * arrangement the user had.
     */
    fun groupLayers(ids: Collection<LayerId>, newId: LayerId, name: String = "گروه"): LayerId? {
        val members = state.document.layers.filter { it.id in ids }
        if (members.isEmpty()) return null

        history.record(state.document)
        val anchor = members.last().id
        val group = Layer.Group(id = newId, children = members, name = name)
        val remaining = state.document.layers.filter { it.id !in ids || it.id == anchor }
        state = state.copy(
            document = state.document.copy(
                layers = remaining.map { if (it.id == anchor) group else it },
            ),
            selection = Selection.of(newId),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return newId
    }

    /**
     * Dissolves a group, leaving its children where the group was.
     *
     * The children keep their own transforms. A group's transform is folded into nothing here,
     * which is the one lossy part — and it is why the group's transform is left alone by every
     * gesture rather than being something the user can set from the canvas.
     */
    fun ungroup(id: LayerId): List<LayerId> {
        val group = state.document.layers.firstOrNull { it.id == id } as? Layer.Group ?: return emptyList()
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(
                layers = state.document.layers.flatMap { if (it.id == id) group.children else listOf(it) },
            ),
            selection = Selection(group.children.map { it.id }),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return group.children.map { it.id }
    }

    /** Clips a layer to the first unclipped layer beneath it, or releases it. */
    fun setClipped(id: LayerId, clipped: Boolean) = edit(id) { it.with(clipped = clipped) }

    /** Whether a group flattens its children before meeting what is beneath it. */
    fun setGroupPassThrough(id: LayerId, passThrough: Boolean) {
        val group = state.document.findLayer(id) as? Layer.Group ?: return
        history.record(state.document)
        state = state.copy(
            document = state.document.mapLayer(id) { (it as Layer.Group).copy(passThrough = passThrough) },
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    fun setMask(id: LayerId, mask: LayerMask?) = edit(id) { it.with(mask = mask) }

    fun setVectorMask(id: LayerId, mask: VectorMask?) = edit(id) { it.with(vectorMask = mask) }

    /**
     * Adds a live copy of a layer rather than a duplicate of its pixels.
     *
     * The difference is the whole reason smart objects exist: editing the source updates every
     * instance. The reference PSDs build one effect out of twenty-nine instances of a single shape,
     * and duplicating instead would mean twenty-nine layers to fix when the wording changes.
     */
    fun addInstance(source: LayerId, newId: LayerId): LayerId? {
        val layer = state.document.findLayer(source) ?: return null
        return addLayer(
            Layer.Instance(
                id = newId,
                source = source,
                name = "${layer.name} ↗",
                transform = layer.transform,
            ),
        )
    }

    // ---- the contextual bar ---------------------------------------------------------------------

    fun deleteLayer(id: LayerId) {
        if (state.document.findLayer(id) == null) return
        history.record(state.document)
        state = state.copy(
            document = state.document.removeLayer(id),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        pruneSelection()
    }

    /**
     * Duplicates a layer above the original and selects the copy.
     *
     * Selecting the copy rather than leaving the original selected is what makes duplicate-and-nudge
     * work as one motion; otherwise the next drag moves the layer underneath.
     */
    fun duplicateLayer(id: LayerId, newId: LayerId): LayerId? {
        val source = state.document.findLayer(id) ?: return null
        history.record(state.document)
        val copy = source.with(name = "${source.name} ✳").withId(newId)
        state = state.copy(
            document = state.document.copy(layers = insertAfter(state.document.layers, id, copy)),
            selection = Selection.of(newId),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return newId
    }

    /** Order in the list is paint order, so "to front" means last. */
    fun bringToFront(id: LayerId) = reorder(id) { rest, layer -> rest + layer }

    fun sendToBack(id: LayerId) = reorder(id) { rest, layer -> listOf(layer) + rest }

    fun setLayerVisible(id: LayerId, visible: Boolean) =
        edit(id) { it.with(visible = visible) }

    fun setLayerLocked(id: LayerId, locked: Boolean) = edit(id) { it.with(locked = locked) }

    fun setLayerOpacity(id: LayerId, opacity: Float, continuous: Boolean = false) {
        if (!continuous || !scrubbing) history.record(state.document)
        scrubbing = continuous
        state = state.copy(
            document = state.document.mapLayer(id) { it.with(opacity = opacity.coerceIn(0f, 1f)) },
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    fun renameLayer(id: LayerId, name: String) = edit(id) { it.with(name = name) }

    /**
     * Replaces a layer with a changed version of itself, as one undo step.
     *
     * The escape hatch for edits that belong to a layer *kind* rather than to layers in general —
     * a text layer's string, an image's crop. Putting each of those on this class would make it
     * grow a method per feature; putting the history handling in the caller would make some of them
     * silently not undoable.
     */
    fun replaceLayer(id: LayerId, change: (Layer) -> Layer) = edit(id, change)

    private fun edit(id: LayerId, change: (Layer) -> Layer) {
        if (state.document.findLayer(id) == null) return
        history.record(state.document)
        state = state.copy(
            document = state.document.mapLayer(id, change),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    private fun reorder(id: LayerId, place: (List<Layer>, Layer) -> List<Layer>) {
        // Only top-level layers reorder here; moving a layer out of a group is a separate,
        // deliberate action rather than something a "to front" button should do silently.
        val layer = state.document.layers.firstOrNull { it.id == id } ?: return
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(
                layers = place(state.document.layers.filter { it.id != id }, layer),
            ),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    private fun insertAfter(layers: List<Layer>, id: LayerId, inserted: Layer): List<Layer> =
        layers.flatMap { layer ->
            when {
                layer.id == id -> listOf(layer, inserted)
                layer is Layer.Group -> listOf(layer.copy(children = insertAfter(layer.children, id, inserted)))
                else -> listOf(layer)
            }
        }

    // ---- sheets --------------------------------------------------------------------------------

    /**
     * Opens the sheet and moves the canvas so the layer it edits is not underneath it.
     *
     * The single most common flaw in mobile editors is a panel that covers the thing it adjusts.
     * The previous camera is stored so closing puts the user's framing back exactly.
     */
    fun openSheet(content: SheetContent, detent: SheetDetent = SheetDetent.HALF) {
        val before = state.viewportBeforeSheet ?: state.viewport
        state = state.copy(sheet = SheetState(content, detent), viewportBeforeSheet = before)
        revealSubject()
    }

    fun setSheetDetent(detent: SheetDetent) {
        if (detent == SheetDetent.HIDDEN) return closeSheet()
        state = state.copy(sheet = state.sheet.copy(detent = detent))
        revealSubject()
    }

    fun closeSheet() {
        val restored = state.viewportBeforeSheet
        state = state.copy(
            sheet = SheetState.CLOSED,
            viewport = restored ?: state.viewport,
            viewportBeforeSheet = null,
        )
    }

    private fun revealSubject() {
        val sheet = state.sheet
        val subject = sheet.content?.subject ?: return
        val layer = state.document.findLayer(subject) ?: return
        if (state.viewport.screenSize.y <= 0f) return
        val obstructed = state.viewport.screenSize.y * sheet.detent.screenFraction
        state = state.copy(
            viewport = (state.viewportBeforeSheet ?: state.viewport).revealing(
                canvasRect = Handles.canvasBounds(bounds.of(layer), layer.transform),
                obstructedBottom = obstructed,
                obstructedTop = TOP_BAR_HEIGHT,
            ),
        )
    }

    // ---- view state ----------------------------------------------------------------------------

    fun setTool(tool: Tool) {
        state = state.copy(tool = tool)
    }

    fun setSnapEnabled(enabled: Boolean) {
        state = state.copy(snapEnabled = enabled)
    }

    /** The `fx` badge: hides every effect so the bare shape is visible. Never a document edit. */
    fun setEffectsBypassed(bypassed: Boolean) {
        state = state.copy(effectsBypassed = bypassed)
    }

    // ---- history -------------------------------------------------------------------------------

    fun undo() {
        scrubbing = false
        val previous = history.undo(state.document) ?: return
        state = state.copy(document = previous, canUndo = history.canUndo, canRedo = history.canRedo)
        pruneSelection()
    }

    fun redo() {
        scrubbing = false
        val next = history.redo(state.document) ?: return
        state = state.copy(document = next, canUndo = history.canUndo, canRedo = history.canRedo)
        pruneSelection()
    }

    // ---- internals -----------------------------------------------------------------------------

    private var scrubbing = false

    /** Drops ids that undo removed, so the contextual bar cannot act on a layer that is gone. */
    private fun pruneSelection() {
        val surviving = state.selection.ids.filter { state.document.findLayer(it) != null }
        if (surviving.size != state.selection.ids.size) {
            state = state.copy(selection = Selection(surviving))
        }
        val subject = state.sheet.content?.subject
        if (subject != null && state.document.findLayer(subject) == null) closeSheet()
    }

    private fun otherBounds(exclude: LayerId): List<Rect> =
        state.document.walk()
            .filter { it.id != exclude && it.visible && it !is Layer.Group }
            .map { Handles.canvasBounds(bounds.of(it), it.transform) }
            .toList()

    private companion object {
        const val FIT_PADDING = 24f

        /** Reserved for the top bar, which the canvas must also stay clear of. */
        const val TOP_BAR_HEIGHT = 56f
    }
}
