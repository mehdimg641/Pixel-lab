package ir.pixellab.core.editor

import ir.pixellab.core.canvas.Handle
import ir.pixellab.core.canvas.HandleConfig
import ir.pixellab.core.canvas.Handles
import ir.pixellab.core.canvas.SnapConfig
import ir.pixellab.core.canvas.SnapEngine
import ir.pixellab.core.canvas.Viewport
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.Guide
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.History
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerComp
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.LayerMask
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.VectorMask
import ir.pixellab.core.model.with
import ir.pixellab.core.model.withId
import ir.pixellab.core.model.withStyle
import ir.pixellab.core.model.withTransform
import ir.pixellab.core.render.EffectRegistry
import ir.pixellab.core.render.ParameterValue
import ir.pixellab.core.render.builtinEffectRegistry
import ir.pixellab.core.text.StyleRuns

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
    snapConfig: SnapConfig = SnapConfig(),
    historyLimit: Int = 50,
) {
    private val history = History(historyLimit)

    /**
     * Which kinds of snapping are live, and how strongly.
     *
     * Mutable rather than a constructor value because it is a *preference* — the user changes it in
     * settings and expects the next drag to obey, not the next launch. Deliberately not in
     * [EditorState]: it is not part of the document, and it must never appear on the undo stack.
     */
    var snapConfig: SnapConfig = snapConfig

    var state: EditorState = EditorState(document)
        private set

    init {
        state = state.copy(canUndo = history.canUndo, canRedo = history.canRedo)
    }

    /**
     * How many document steps are on the undo stack.
     *
     * Exposed so a host that has edits of its own — painted pixels, which are not part of the
     * document — can tell whether a call it just made recorded a step, and interleave its own
     * history with this one. Without it the two stacks drift and undo starts skipping steps.
     */
    val undoDepth: Int get() = history.depth

    // ---- viewport ------------------------------------------------------------------------------

    fun resizeScreen(size: Vec2, fitIfFirst: Boolean = true) {
        val viewport = state.viewport.copy(screenSize = size)
        state = state.copy(
            viewport = if (fitIfFirst && state.viewport.screenSize == Vec2.ZERO) {
                fitted(viewport)
            } else {
                viewport
            },
        )
    }

    fun fitCanvas() {
        state = state.copy(viewport = fitted(state.viewport))
    }

    /**
     * How much of the screen the fixed bars cover, top and bottom.
     *
     * Told to the editor rather than assumed, because only the screen knows: the bars are laid out
     * in dp against the device's density, and a constant here would be right on exactly one phone.
     */
    fun setChrome(top: Float, bottom: Float) {
        chromeTop = top
        chromeBottom = bottom
    }

    private var chromeTop = TOP_BAR_HEIGHT
    private var chromeBottom = 0f

    private fun fitted(viewport: Viewport) = viewport.fit(
        canvasSize = state.document.canvas.size,
        padding = FIT_PADDING,
        insetTop = chromeTop,
        insetBottom = chromeBottom,
    )

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
            textRange = null,
        )
    }

    /**
     * Aims the text panel at part of the selected string, or at all of it when [range] is null.
     *
     * Not on the undo stack, and deliberately: pointing at a word is not an edit to the artwork,
     * and a user who undoes a colour change expects the word to still be selected so they can try a
     * different one. The same reasoning the pixel selection follows.
     *
     * The range is snapped outward to whole connected clusters, so it can never fall in the middle
     * of a letter group — which in Persian is the difference between styling a word and breaking it.
     */
    fun selectTextRange(range: IntRange?) {
        val text = (state.primaryLayer as? Layer.Text)?.spec?.text
        state = state.copy(
            textRange = when {
                range == null || text == null -> null
                else -> StyleRuns.snap(text, range).takeIf { !it.isEmpty() }
            },
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
            drag = DragSession(
                handle = handle,
                layer = layer.id,
                startTransform = layer.transform,
                startCanvas = state.viewport.toCanvas(screen),
                linkedStart = (state.document.links.partners(layer.id) - layer.id)
                    .mapNotNull { id -> state.document.findLayer(id)?.let { id to it.transform.translation } }
                    .toMap(),
            ),
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
                guides = state.document.guides,
                grid = state.grid,
            )
        } else {
            null
        }

        val settled = if (snap != null && snap.snapped) {
            moved.copy(translation = moved.translation + snap.offset)
        } else {
            moved
        }
        // Linked layers follow by the same delta rather than being transformed themselves: they
        // may be rotated or scaled differently, and copying the dragged layer's whole transform
        // onto them would snap every partner to its shape.
        var document = state.document.mapLayer(session.layer) { it.withTransform(settled) }
        val followers = state.document.links.partners(session.layer) - session.layer
        if (followers.isNotEmpty()) {
            val delta = settled.translation - session.startTransform.translation
            for (id in followers) {
                document = document.mapLayer(id) { layer ->
                    // The partner's *own* start is unknown mid-drag, so the delta is applied to the
                    // live value each frame. Recomputed rather than accumulated, because
                    // accumulating a per-frame delta drifts over a long drag.
                    val start = session.linkedStart[id] ?: layer.transform.translation
                    layer.withTransform(layer.transform.copy(translation = start + delta))
                }
            }
        }

        state = state.copy(document = document, guides = snap?.guides.orEmpty())
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

    /**
     * Moves a layer one place up or down within whatever list it is already in.
     *
     * Within its own list, and no further: a layer at the top of a group does not silently escape
     * the group on the next press. Leaving a group is [moveOutOfGroup], which the user asks for
     * explicitly, because a layer that drifts out of a group has quietly stopped being clipped,
     * masked and blended by it.
     */
    fun raiseLayer(id: LayerId): Boolean = restack(id, +1)

    fun lowerLayer(id: LayerId): Boolean = restack(id, -1)

    private fun restack(id: LayerId, delta: Int): Boolean {
        val reordered = restacked(state.document.layers, id, delta) ?: return false
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(layers = reordered),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return true
    }

    /**
     * Puts a layer inside a group, at the top of it.
     *
     * At the top rather than at the bottom because the layer the user is dragging in is the one they
     * are working on, and burying it under the group's existing contents hides it.
     */
    fun moveIntoGroup(id: LayerId, group: LayerId): Boolean {
        if (id == group) return false
        val layer = state.document.findLayer(id) ?: return false
        val target = state.document.findLayer(group) as? Layer.Group ?: return false
        // A group cannot contain itself at any depth; the document would stop being a tree and
        // every walk over it would run forever.
        if (layer is Layer.Group && containsLayer(layer, group)) return false
        if (target.children.any { it.id == id }) return false

        history.record(state.document)
        state = state.copy(
            document = state.document.removeLayer(id)
                .mapLayer(group) { (it as Layer.Group).copy(children = it.children + layer) },
            selection = Selection.of(id),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return true
    }

    /**
     * Lifts a layer out of its group, leaving it directly above the group.
     *
     * Above rather than below: the layer was drawn on top of the group's contents a moment ago, and
     * dropping it underneath them makes it disappear.
     */
    fun moveOutOfGroup(id: LayerId): Boolean {
        val parent = parentOf(state.document.layers, id) ?: return false
        val layer = state.document.findLayer(id) ?: return false
        history.record(state.document)
        val without = state.document.removeLayer(id)
        state = state.copy(
            document = without.copy(layers = insertAfter(without.layers, parent.id, layer)),
            selection = Selection.of(id),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return true
    }

    /**
     * Reorders within one list, returning null when [id] is not in this subtree or cannot move.
     *
     * The two are deliberately the same answer. A layer already at the end of its list has nowhere
     * to go, and reporting that separately would only give the caller a second way to write the
     * same no-op.
     */
    private fun restacked(layers: List<Layer>, id: LayerId, delta: Int): List<Layer>? {
        val index = layers.indexOfFirst { it.id == id }
        if (index >= 0) {
            val target = index + delta
            if (target !in layers.indices) return null
            return layers.toMutableList().apply { add(target, removeAt(index)) }
        }
        for ((i, layer) in layers.withIndex()) {
            if (layer !is Layer.Group) continue
            val changed = restacked(layer.children, id, delta) ?: continue
            return layers.toMutableList().also { it[i] = layer.copy(children = changed) }
        }
        return null
    }

    private fun containsLayer(group: Layer.Group, id: LayerId): Boolean =
        group.children.any { it.id == id || (it is Layer.Group && containsLayer(it, id)) }

    private fun parentOf(layers: List<Layer>, id: LayerId): Layer.Group? {
        for (layer in layers) {
            if (layer !is Layer.Group) continue
            if (layer.children.any { it.id == id }) return layer
            parentOf(layer.children, id)?.let { return it }
        }
        return null
    }

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
            // The link is dropped with the layer. A link holding a deleted id would keep reporting
            // its survivors as linked to something that is no longer in the document.
            document = state.document.removeLayer(id).let { it.copy(links = it.links.forget(id)) },
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

    // ---- linking and comps ---------------------------------------------------------------------

    /**
     * Links the selection so the layers move together.
     *
     * Fewer than two selected does nothing rather than reporting an error: the button is visible
     * whatever is selected, and a link of one is not a state worth having.
     */
    fun linkSelected(): Boolean {
        val ids = state.selection.ids
        if (ids.size < 2) return false
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(links = state.document.links.link(ids)),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return true
    }

    fun unlinkSelected(): Boolean {
        val ids = state.selection.ids
        if (ids.none { state.document.links.isLinked(it) }) return false
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(links = state.document.links.unlink(ids)),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return true
    }

    /** Everything that would move with [id], for the canvas to outline. */
    fun linkPartners(id: LayerId): Set<LayerId> = state.document.links.partners(id)

    /**
     * Saves the current visibility and positions under [name].
     *
     * Replaces a comp of the same name rather than adding a second. Two comps called "Persian
     * title" is a state a user cannot resolve, since the panel shows them by name alone.
     */
    fun captureComp(name: String, visibility: Boolean = true, positions: Boolean = true): Boolean {
        if (name.isBlank()) return false
        val comp = LayerComp.capture(name, state.document.walk(), visibility, positions)
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(
                comps = state.document.comps.filterNot { it.name == name } + comp,
            ),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return true
    }

    /**
     * Restores a comp.
     *
     * Only what the comp captured, and only for layers that still exist. A layer added since the
     * comp was saved keeps whatever it has — a comp is a layout switch, not a version of the
     * document, and hiding new work because an old comp did not know about it would be the worse
     * of the two behaviours.
     */
    fun applyComp(name: String): Boolean {
        val comp = state.document.comps.firstOrNull { it.name == name } ?: return false
        history.record(state.document)
        var document = state.document
        for (layer in state.document.walk().toList()) {
            val key = layer.id.value
            comp.visibility[key]?.let { visible ->
                document = document.mapLayer(layer.id) { it.with(visible = visible) }
            }
            comp.positions[key]?.let { at ->
                document = document.mapLayer(layer.id) {
                    it.withTransform(it.transform.copy(translation = at))
                }
            }
        }
        state = state.copy(document = document, canUndo = history.canUndo, canRedo = history.canRedo)
        return true
    }

    fun deleteComp(name: String): Boolean {
        if (state.document.comps.none { it.name == name }) return false
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(comps = state.document.comps.filterNot { it.name == name }),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return true
    }

    fun setLayerOpacity(id: LayerId, opacity: Float, continuous: Boolean = false) =
        scrub(id, continuous) { it.with(opacity = opacity.coerceIn(0f, 1f)) }

    /**
     * Fill opacity: the layer's own paint, leaving its effects at full strength.
     *
     * The distinction Photoshop draws between Opacity and Fill, and it is not a nicety — it is how
     * a hollow title is made. Dropping layer opacity fades the stroke and the shadow along with the
     * letterform; dropping fill opacity takes the letterform away and leaves the stroke standing.
     */
    fun setFillOpacity(id: LayerId, opacity: Float, continuous: Boolean = false) =
        scrub(id, continuous) { it.withStyle(it.style.copy(fillOpacity = opacity.coerceIn(0f, 1f))) }

    fun setLayerBlendMode(id: LayerId, mode: ir.pixellab.core.model.BlendMode) =
        edit(id) { it.with(blendMode = mode) }

    /**
     * One change to a text layer's spec, coalescing a drag into a single undo entry.
     *
     * The panel's per-range controls are sliders like any other, so they need the same contract:
     * the whole sweep is one step. Going through `replaceLayer` instead — which is what the
     * existing typographic setters do, because none of them was draggable — would leave a hundred
     * entries behind a two-second drag on the size of one word.
     */
    fun setTextSpec(id: LayerId, continuous: Boolean = false, change: (TextSpec) -> TextSpec) {
        scrub(id, continuous) { layer -> if (layer is Layer.Text) layer.copy(spec = change(layer.spec)) else layer }
    }

    /**
     * One change from a control the user is dragging.
     *
     * The first frame of a scrub records history and the rest do not, so a one-second drag is a
     * single undo step rather than several hundred.
     */
    private fun scrub(id: LayerId, continuous: Boolean, change: (Layer) -> Layer) {
        if (state.document.findLayer(id) == null) return
        if (!continuous || !scrubbing) history.record(state.document)
        scrubbing = continuous
        state = state.copy(
            document = state.document.mapLayer(id, change),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    // ---- arranging ----------------------------------------------------------------------------

    /**
     * Lines the chosen layers up.
     *
     * Against the selection's own box when more than one is chosen and against the canvas when only
     * one is — Photoshop's rule, and the only one that makes a single-layer align mean anything at
     * all. A caller can override it when the user asks for the canvas explicitly.
     */
    fun alignLayers(
        ids: Collection<LayerId>,
        edge: AlignEdge,
        target: AlignTarget = if (ids.size > 1) AlignTarget.SELECTION else AlignTarget.CANVAS,
    ): Boolean = applyMoves(Arrange.align(placedOf(ids), edge, target, state.document.canvas.bounds))

    /** Evens out the gaps between the chosen layers. Needs three; two have one gap and no middle. */
    fun distributeLayers(ids: Collection<LayerId>, axis: DistributeAxis): Boolean =
        applyMoves(Arrange.distribute(placedOf(ids), axis))

    /**
     * Mirrors a layer in place.
     *
     * A negative scale rather than a rebuilt geometry: it works for every layer kind without one of
     * them having to know how to reverse itself, and it survives being flipped back — mirroring a
     * path by rewriting its nodes accumulates error and eventually reverses its winding.
     */
    fun flipLayer(id: LayerId, horizontal: Boolean) = edit(id) { layer ->
        val scale = layer.transform.scale
        layer.withTransform(
            layer.transform.copy(
                scale = if (horizontal) scale.copy(x = -scale.x) else scale.copy(y = -scale.y),
            ),
        )
    }

    /**
     * Turns the whole canvas in quarter turns.
     *
     * Every top-level layer rotates about the canvas centre and the canvas swaps its sides on an odd
     * number of turns. Rotating the layers without swapping the canvas is the version that silently
     * pushes a portrait design off the sides of a landscape artboard.
     */
    fun rotateCanvas(quarterTurns: Int) {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0) return
        val canvas = state.document.canvas
        val swapped = turns % 2 == 1
        val width = if (swapped) canvas.height else canvas.width
        val height = if (swapped) canvas.width else canvas.height

        history.record(state.document)
        val degrees = turns * QUARTER_TURN
        val from = Vec2(canvas.width / 2f, canvas.height / 2f)
        val to = Vec2(width / 2f, height / 2f)
        val moved = state.document.layers.map { layer ->
            val transform = layer.transform
            val turned = rotateAbout(transform.translation, from, degrees) - from + to
            layer.withTransform(
                transform.copy(translation = turned, rotation = transform.rotation + degrees),
            )
        }
        state = state.copy(
            document = state.document.copy(
                canvas = canvas.copy(width = width, height = height),
                layers = moved,
            ),
            viewportBeforeSheet = null,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        fitCanvas()
    }

    /**
     * Replaces a run of layers with one, for merging and flattening.
     *
     * The editor only does the *structure*: the pixels have to be rendered, which needs the GPU and
     * therefore the host. Splitting it this way is what keeps merging testable — the rule about
     * where the merged layer lands and what happens to the selection is decided here, and the host
     * supplies a picture.
     *
     * The result takes the place of the **topmost** of the merged layers, because that is where the
     * combined image sits in the stack; putting it at the bottom-most position would move it behind
     * anything that was between them.
     */
    fun replaceWithMerged(ids: Collection<LayerId>, merged: Layer): Boolean {
        val chosen = state.document.layers.filter { it.id in ids }
        if (chosen.size < 2) return false

        history.record(state.document)
        val anchor = chosen.last().id
        state = state.copy(
            document = state.document.copy(
                layers = state.document.layers
                    .filter { it.id !in ids || it.id == anchor }
                    .map { if (it.id == anchor) merged else it },
            ),
            selection = Selection.of(merged.id),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return true
    }

    /** Which top-level layers a merge or flatten would take, front to back. */
    fun mergeableBelow(id: LayerId): List<LayerId> {
        val index = state.document.layers.indexOfFirst { it.id == id }
        if (index <= 0) return emptyList()
        return listOf(state.document.layers[index - 1].id, id)
    }

    fun visibleLayers(): List<LayerId> = state.document.layers.filter { it.visible }.map { it.id }

    private fun placedOf(ids: Collection<LayerId>): List<Placed> =
        state.document.layers
            .filter { it.id in ids }
            .map { Placed(it.id, Handles.canvasBounds(bounds.of(it), it.transform)) }

    private fun applyMoves(moves: Map<LayerId, Vec2>): Boolean {
        if (moves.isEmpty()) return false
        history.record(state.document)
        var document = state.document
        for ((id, delta) in moves) {
            document = document.mapLayer(id) {
                it.withTransform(it.transform.copy(translation = it.transform.translation + delta))
            }
        }
        state = state.copy(document = document, canUndo = history.canUndo, canRedo = history.canRedo)
        return true
    }

    private fun rotateAbout(point: Vec2, pivot: Vec2, degrees: Float): Vec2 {
        val radians = degrees * DEGREES_TO_RADIANS
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)
        val dx = point.x - pivot.x
        val dy = point.y - pivot.y
        return Vec2(pivot.x + dx * cos - dy * sin, pivot.y + dx * sin + dy * cos)
    }

    // ---- the canvas itself ------------------------------------------------------------------------

    /**
     * Resizes the canvas without touching what is on it.
     *
     * Photoshop's Canvas Size, and the anchor is the whole point: growing a 1080 square to a 1080×1920
     * story from the top-left leaves the artwork where it was and adds space below, while the same
     * change anchored in the centre splits the new space above and below it. Everything moves by one
     * offset, so the composition holds together rather than every layer being re-placed individually.
     */
    fun resizeCanvas(width: Int, height: Int, anchor: CanvasAnchor = CanvasAnchor.CENTER) {
        if (width <= 0 || height <= 0) return
        val canvas = state.document.canvas
        if (width == canvas.width && height == canvas.height) return
        val offset = anchor.offsetFor(canvas.width, canvas.height, width, height)
        applyCanvas(canvas.copy(width = width, height = height), offset)
    }

    /**
     * Crops to a rectangle in canvas coordinates.
     *
     * The rectangle is rounded outwards rather than to nearest: rounding inwards clips a pixel off
     * an edge the user aligned something to, and a crop that loses content is the one mistake here
     * that undo is the only recovery from.
     */
    fun cropCanvas(rect: Rect) {
        val left = floor(rect.left)
        val top = floor(rect.top)
        val width = ceil(rect.right) - left
        val height = ceil(rect.bottom) - top
        if (width < 1 || height < 1) return
        applyCanvas(
            state.document.canvas.copy(width = width, height = height),
            Vec2(-left.toFloat(), -top.toFloat()),
        )
    }

    /**
     * The colour behind everything.
     *
     * On the canvas rather than as a bottom layer, which is what Photoshop's Background layer is and
     * why that layer is special-cased everywhere in it — it cannot be moved, reordered or given
     * transparency. Keeping it a property of the canvas removes all of those exceptions, and null is
     * a real value meaning a transparent document.
     */
    fun setCanvasBackground(fill: ir.pixellab.core.model.Fill?) {
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(canvas = state.document.canvas.copy(background = fill)),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    /**
     * The canvas's resolution, in pixels per inch.
     *
     * Metadata only. It decides the physical size of an exported page and nothing about the pixels,
     * so it goes through its own path rather than [applyCanvas] — which moves layers and refits the
     * viewport, neither of which a change of DPI has any business doing.
     */
    /**
     * The document's working precision — Photoshop's Image ▸ Mode ▸ 8/16/32 Bits.
     *
     * Expressible since wave 1 and unreachable until now: `ColorSettings.precision` was read only by
     * the memory budget, so a document could be sixteen bit and no control could say so.
     *
     * It matters most on exactly the work this app is for. A style from the reference files runs a
     * gradient through ten stacked shadows and a bevel; every pass quantises at eight bits, and the
     * banding that survives to the final image cannot be recovered afterwards.
     */
    fun setPrecision(precision: ir.pixellab.core.model.Precision) {
        if (precision == state.document.color.precision) return
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(color = state.document.color.copy(precision = precision)),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    fun setCanvasDpi(dpi: Int) {
        if (dpi == state.document.canvas.dpi) return
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(canvas = state.document.canvas.copy(dpi = dpi)),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    private fun applyCanvas(canvas: ir.pixellab.core.model.CanvasSpec, offset: Vec2) {
        history.record(state.document)
        // Top-level layers only: a layer inside a group already moves with its parent, and shifting
        // both would move it twice.
        val moved = if (offset == Vec2.ZERO) {
            state.document.layers
        } else {
            state.document.layers.map {
                it.withTransform(it.transform.copy(translation = it.transform.translation + offset))
            }
        }
        state = state.copy(
            document = state.document.copy(canvas = canvas, layers = moved),
            // The stored camera was framed for a canvas that no longer exists; restoring it when the
            // sheet closes would jump the user somewhere off the new artboard.
            viewportBeforeSheet = null,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        fitCanvas()
    }

    private fun floor(value: Float): Int = kotlin.math.floor(value).toInt()

    private fun ceil(value: Float): Int = kotlin.math.ceil(value).toInt()

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

    // ---- grid, rulers and guides ------------------------------------------------------------------

    fun setGrid(grid: ir.pixellab.core.canvas.GridSpec) {
        state = state.copy(grid = grid)
    }

    fun setRulersVisible(visible: Boolean) {
        state = state.copy(showRulers = visible)
    }

    fun setSafeZone(zone: ir.pixellab.core.canvas.SafeZone?) {
        state = state.copy(safeZone = zone)
    }

    /**
     * Places a guide.
     *
     * A document edit, so it is undoable and travels with the file — a guide records a decision
     * about the design, and losing it on save means re-measuring the next time the file is opened.
     *
     * Returns the guide's index, which is how every later move or removal names it: a guide has no
     * identity of its own, and keying on its position would break the moment the user dragged it.
     */
    fun addGuide(guide: Guide): Int {
        history.record(state.document)
        val guides = state.document.guides + guide
        state = state.copy(
            document = state.document.copy(guides = guides),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        return guides.lastIndex
    }

    /**
     * Moves a guide.
     *
     * @param continuous true while it is being dragged, so a drag is one undo step rather than one
     *   per frame — the same rule every scrub in the editor follows.
     */
    fun moveGuide(index: Int, position: Float, continuous: Boolean = false) {
        val guides = state.document.guides
        if (index !in guides.indices || guides[index].locked) return
        if (!continuous || !scrubbing) history.record(state.document)
        scrubbing = continuous
        state = state.copy(
            document = state.document.copy(
                guides = guides.mapIndexed { i, g -> if (i == index) g.copy(position = position) else g },
            ),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    fun removeGuide(index: Int) {
        val guides = state.document.guides
        if (index !in guides.indices || guides[index].locked) return
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(guides = guides.filterIndexed { i, _ -> i != index }),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    fun clearGuides() {
        // Locked guides survive: locking one is exactly the instruction "do not let me lose this".
        val kept = state.document.guides.filter { it.locked }
        if (kept.size == state.document.guides.size) return
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(guides = kept),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    fun setGuidesLocked(locked: Boolean) {
        if (state.document.guides.isEmpty()) return
        history.record(state.document)
        state = state.copy(
            document = state.document.copy(guides = state.document.guides.map { it.copy(locked = locked) }),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    /**
     * Lays guides out as even columns and rows — Photoshop's New Guide Layout.
     *
     * Replaces the unlocked guides rather than adding to them, because running it twice with
     * different numbers is how it is actually used, and appending would leave the first layout
     * behind as clutter the user then has to clear by hand.
     */
    fun guideLayout(columns: Int, rows: Int, margin: Float = 0f) {
        val canvas = state.document.canvas
        val fresh = ArrayList<Guide>()
        if (margin > 0f) {
            fresh += Guide(vertical = true, position = margin)
            fresh += Guide(vertical = true, position = canvas.width - margin)
            fresh += Guide(vertical = false, position = margin)
            fresh += Guide(vertical = false, position = canvas.height - margin)
        }
        val innerWidth = canvas.width - margin * 2
        val innerHeight = canvas.height - margin * 2
        for (i in 1 until columns.coerceAtLeast(1)) {
            fresh += Guide(vertical = true, position = margin + innerWidth * i / columns)
        }
        for (i in 1 until rows.coerceAtLeast(1)) {
            fresh += Guide(vertical = false, position = margin + innerHeight * i / rows)
        }
        if (fresh.isEmpty() && state.document.guides.none { !it.locked }) return

        history.record(state.document)
        state = state.copy(
            document = state.document.copy(guides = state.document.guides.filter { it.locked } + fresh),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    /**
     * The guide nearest a canvas point, for a finger to grab.
     *
     * @param tolerance in canvas units, so the caller converts from screen pixels and the grab
     *   radius stays the same size under the finger at every zoom.
     */
    fun guideAt(point: Vec2, tolerance: Float): Int? =
        state.document.guides
            .mapIndexed { index, guide ->
                val distance = if (guide.vertical) {
                    kotlin.math.abs(point.x - guide.position)
                } else {
                    kotlin.math.abs(point.y - guide.position)
                }
                index to distance
            }
            .filter { it.second <= tolerance }
            .minByOrNull { it.second }
            ?.first

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

        const val QUARTER_TURN = 90f

        const val DEGREES_TO_RADIANS = (kotlin.math.PI / 180.0).toFloat()

        /** Reserved for the top bar, which the canvas must also stay clear of. */
        const val TOP_BAR_HEIGHT = 56f
    }
}
