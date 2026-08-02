package ir.pixellab.core.editor

import ir.pixellab.core.canvas.Handle
import ir.pixellab.core.canvas.SnapGuide
import ir.pixellab.core.canvas.Viewport
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2

/** The five entries on the persistent toolbar. */
enum class Tool {
    LAYERS, TEXT, IMAGE, SHAPE, ADJUST,

    /** Painting and erasing. While it is active a drag paints instead of moving a layer. */
    BRUSH,

    /** Cutting out, retouching and warping. A drag gathers a liquify stroke. */
    RETOUCH,

    /** The pen and the node editor. A drag shapes a curve rather than moving a layer. */
    PEN,

    /** Choosing pixels: marquee, lasso and wand. A drag defines a region rather than moving one. */
    SELECT,
    ;

    /**
     * True when a drag on the canvas belongs to the tool rather than to the layer under it.
     *
     * The single rule that decides whether painting works: without it the first brush stroke drags
     * whatever layer happened to be selected across the canvas.
     */
    val ownsDrag: Boolean get() = this == BRUSH || this == SELECT || this == RETOUCH || this == PEN
}

/**
 * What is selected.
 *
 * A list rather than a single id because aligning and distributing need more than one, and
 * [primary] is the one whose parameters the sheet shows.
 */
data class Selection(val ids: List<LayerId> = emptyList()) {
    val primary: LayerId? get() = ids.lastOrNull()
    val isEmpty: Boolean get() = ids.isEmpty()
    val size: Int get() = ids.size

    operator fun contains(id: LayerId) = id in ids

    fun toggle(id: LayerId) = if (id in ids) Selection(ids - id) else Selection(ids + id)

    companion object {
        val NONE = Selection()

        fun of(id: LayerId) = Selection(listOf(id))
    }
}

/**
 * The three heights a sheet rests at, plus closed.
 *
 * Discrete stops rather than free positioning: a sheet the user has to aim at is a sheet they fight
 * with, and the three stops map to the three real intents — glance at one control, work with a few,
 * or open everything.
 */
enum class SheetDetent(val screenFraction: Float) {
    HIDDEN(0f),

    /** Just the effect's most important control, so the canvas stays almost fully visible. */
    PEEK(0.18f),

    HALF(0.45f),

    FULL(0.9f),
    ;

    val isOpen: Boolean get() = this != HIDDEN
}

/** What the parameter sheet is showing. */
sealed interface SheetContent {
    /** One effect on one layer; its controls are generated from the effect module. */
    data class EffectParameters(val layer: LayerId, val effectIndex: Int) : SheetContent

    /** The layer itself: opacity, blend mode, fill. */
    data class LayerParameters(val layer: LayerId) : SheetContent

    data object LayerList : SheetContent

    data object StyleLibrary : SheetContent

    data object FontPicker : SheetContent

    /** The brush: presets, size, hardness, flow, opacity, colour. */
    data object BrushSettings : SheetContent

    /** Choosing pixels: which shape, how it combines, tolerance, feather. */
    data object PixelSelection : SheetContent

    /** Colour correction: the fourteen adjustments, and the controls of whichever is selected. */
    data object Adjustments : SheetContent

    /** Cutting out, retouching and warping the selected image layer. */
    data object Retouch : SheetContent

    /** The pen, node editing, variable width, Pathfinder and SVG. */
    data object Vector : SheetContent

    /** The layer this sheet is about, if any — used to keep it out from under the sheet. */
    val subject: LayerId?
        get() = when (this) {
            is EffectParameters -> layer
            is LayerParameters -> layer
            else -> null
        }
}

data class SheetState(
    val content: SheetContent? = null,
    val detent: SheetDetent = SheetDetent.HIDDEN,
) {
    val isOpen: Boolean get() = content != null && detent.isOpen

    companion object {
        val CLOSED = SheetState()
    }
}

/** A drag in progress. Kept in the state so the whole gesture can collapse into one undo entry. */
data class DragSession(
    val handle: Handle,
    val layer: LayerId,
    val startTransform: Transform,
    val startCanvas: Vec2,
)

/**
 * Everything the editor screen renders from.
 *
 * One immutable value, so the canvas, the toolbar and the sheet cannot disagree about what is
 * selected — the failure that shows up as a sheet editing a layer the canvas no longer highlights.
 */
data class EditorState(
    val document: Document,
    val viewport: Viewport = Viewport(),
    val selection: Selection = Selection.NONE,
    val tool: Tool = Tool.LAYERS,
    val sheet: SheetState = SheetState.CLOSED,
    /** Guides the current drag is snapped against, for the canvas to draw. */
    val guides: List<SnapGuide> = emptyList(),
    val snapEnabled: Boolean = true,
    /**
     * Suppresses every effect so the underlying shape is visible.
     *
     * The `fx` badge in the layer panel toggles this. It is a view state, never a document edit, so
     * it can never be saved by accident or land in the undo stack.
     */
    val effectsBypassed: Boolean = false,
    val drag: DragSession? = null,
    /** Viewport to restore when the sheet closes; null when no sheet moved it. */
    val viewportBeforeSheet: Viewport? = null,
    /** Layers under the last long press, topmost first, for picking a buried one. */
    val pickCandidates: List<LayerId> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val selectedLayers: List<Layer>
        get() = selection.ids.mapNotNull { document.findLayer(it) }

    val primaryLayer: Layer? get() = selection.primary?.let(document::findLayer)

    /** True when the contextual bar should be showing. */
    val hasSelection: Boolean get() = selection.ids.any { document.findLayer(it) != null }
}

/**
 * Where a layer's untransformed content sits, in its own coordinates.
 *
 * Supplied by the host rather than stored on the layer: text bounds come from shaping and image
 * bounds from the decoded asset, neither of which belongs in a pure model. Keeping it a function
 * also lets tests place layers exactly.
 */
fun interface LayerBounds {
    fun of(layer: Layer): Rect
}
