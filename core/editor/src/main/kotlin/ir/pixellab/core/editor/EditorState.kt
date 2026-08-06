package ir.pixellab.core.editor

import ir.pixellab.core.canvas.Handle
import ir.pixellab.core.canvas.GridSpec
import ir.pixellab.core.canvas.SafeZone
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

    /**
     * Portrait: what the face model finds, and everything that can be done once it has.
     *
     * Held apart from [Retouch] because the two answer different questions. Retouch is manual and
     * works on any picture; this one needs a detected face and offers what a face makes possible —
     * makeup, teeth, eyes, reshaping. Merging them would put half a panel behind a precondition the
     * other half does not have.
     */
    data object Portrait : SheetContent

    /** The pen, node editing, variable width, Pathfinder and SVG. */
    data object Vector : SheetContent

    /** Saved styles and starting templates. */
    data object LibraryPanel : SheetContent

    /** The canvas and what arrives on it: size, crop, fill, and placing a file as a layer. */
    data object CanvasTools : SheetContent

    /** The shape tool: which primitive, and the parameters of the one already selected. */
    data object ShapeTools : SheetContent

    /**
     * Several photographs in one frame: the layout, the spacing, and which pictures.
     *
     * Its own sheet rather than a corner of [CanvasTools], because a collage is a way of *starting*
     * a document rather than an operation on one, and because it is the entry every reference app
     * puts on its home screen. Burying it under the crop tool would be putting the second most-used
     * thing in the app behind the least obvious door in it.
     */
    data object Collage : SheetContent

    /** Where things sit: aligning, distributing, mirroring, turning, merging, exact numbers. */
    data object Arrange : SheetContent

    /** The grid, the rulers, the guides and the platform safe zones. */
    data object Guides : SheetContent

    /**
     * The character and paragraph panels: everything about type except which typeface.
     *
     * Separate from [FontPicker] because choosing a face and setting its measurements are different
     * activities on different timescales — a face is picked once and the tracking is nudged twenty
     * times — and putting them in one sheet means scrolling past a font list to reach a slider.
     */
    data object Typography : SheetContent

    /** Application preferences: not part of any document, and never on the undo stack. */
    data object Settings : SheetContent

    /** Real extruded 3D: depth, bevel, materials, lights and the camera. */
    data object Dimensional : SheetContent

    /**
     * Everything that can be done to a piece of text, in one panel with a section strip.
     *
     * The shape is taken from the mobile editor people actually use for this: select the words, get
     * a labelled row, tap one and it opens where you are. The alternative — a control per sheet
     * reached from a menu — is what this application had, and it is why colour, size and shadow sat
     * three panels apart from each other while all three belong to the same decision.
     *
     * [section] is part of the identity so a control elsewhere can open the panel *at* the section
     * it is about, rather than at a landing page the user then has to navigate. It is deliberately
     * excluded from [identity] — see there.
     */
    data class TextStudio(val layer: LayerId, val section: TextSection = TextSection.CONTENT) : SheetContent

    /** The layer this sheet is about, if any — used to keep it out from under the sheet. */
    val subject: LayerId?
        get() = when (this) {
            is EffectParameters -> layer
            is LayerParameters -> layer
            is TextStudio -> layer
            else -> null
        }

    /**
     * What counts as "the same panel" for the purpose of the ✕ and ✓ at its head.
     *
     * Those two buttons work by remembering where the undo stack stood when the panel opened, and
     * that baseline is reset whenever the sheet's content changes. Moving between sections of one
     * panel is not opening a new panel, so without this, «انصراف» would only ever undo back to the
     * last section tap — the user would set a shadow, glance at the colour section, and find that
     * cancelling no longer removed the shadow. Every other sheet is its own identity.
     */
    val identity: Any
        get() = when (this) {
            is TextStudio -> layer
            else -> this
        }
}

/**
 * The sections of the text panel, in the order a hand reaches for them.
 *
 * Ordered by the sequence of decisions rather than by how the model is shaped: the words, then the
 * face, then the colour, then the things that turn it into a title. Grouping by model structure is
 * what produced a panel with «رنگ» in one sheet and «پوشش رنگ» in another.
 */
enum class TextSection {
    /** The words themselves, and which part of them the rest of the panel is aimed at. */
    CONTENT,

    /** Which typeface, at which weight, with which of its alternates switched on. */
    FONT,

    /** The paint: a colour, a gradient or a texture — for the whole layer or one chosen word. */
    COLOR,

    /** Size, tracking, leading, alignment, direction, baseline. */
    METRICS,

    /** Outlines around the letters. */
    STROKE,

    /** Cast shadow and inner shadow. */
    SHADOW,

    /** Outer and inner glow, and the neon recipe built from them. */
    GLOW,

    /** Real extruded three-dimensional type. */
    DIMENSIONAL,

    /** What the letters are made of: how metallic, how rough, how lacquered. */
    MATERIAL,

    /** A panel behind the words. */
    BACKGROUND,

    /** Warping the letters, and running the baseline along an arc. */
    CURVE,

    /** A mirrored copy beneath the line. */
    REFLECTION,

    /** Opacity and blend mode. */
    BLEND,

    /** Satin, emboss, stacked extrude, grain, colour fringing, eroded edges. */
    ADVANCED,
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
    /**
     * Where every linked partner sat when the drag began.
     *
     * Captured once rather than accumulated per frame. Adding a small delta each frame drifts over
     * a long drag — sixty tiny rounding errors a second — and the partners end up visibly offset
     * from the layer they are supposed to be pinned to.
     */
    val linkedStart: Map<LayerId, Vec2> = emptyMap(),
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
     * The grid. A working preference rather than part of the artwork, so it lives here and is not
     * saved with the document — unlike the guides, which are.
     */
    val grid: GridSpec = GridSpec(),
    /** Rulers down the top and leading edges, and the strip a guide is dragged out of. */
    val showRulers: Boolean = false,
    /** The platform crop to show, when the user has chosen one. */
    val safeZone: SafeZone? = null,
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
    /**
     * Which part of the selected text every control in the text panel is aimed at.
     *
     * Null means the whole layer, which is what almost every edit wants and what the panel opens
     * on. A range is set by choosing clusters in the glyph ribbon, and it is view state rather than
     * document state for the same reason a pixel selection is: undoing a colour change must not
     * also undo having pointed at the word.
     *
     * Cleared whenever the layer selection changes, because a range of characters means nothing
     * once a different string is selected — and a stale one would silently aim the next edit at the
     * wrong letters of the wrong layer.
     */
    val textRange: IntRange? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val selectedLayers: List<Layer>
        get() = selection.ids.mapNotNull { document.findLayer(it) }

    val primaryLayer: Layer? get() = selection.primary?.let(document::findLayer)

    /**
     * [textRange], but only when it still describes the text that is actually selected.
     *
     * Every control reads this rather than the field. A range of character indices is meaningless
     * against a different string, and a stale one would quietly aim the next colour change at the
     * wrong letters — so rather than relying on every place that changes the selection to remember
     * to clear it, the range simply stops applying when it stops making sense.
     */
    val activeTextRange: IntRange?
        get() {
            val text = (primaryLayer as? Layer.Text)?.spec?.text ?: return null
            val range = textRange ?: return null
            if (range.isEmpty() || range.first < 0 || range.last >= text.length) return null
            return range
        }

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
