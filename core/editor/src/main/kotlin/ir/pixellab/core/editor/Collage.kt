package ir.pixellab.core.editor

import ir.pixellab.core.model.AssetId
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Corners
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Style as LayerStyle
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import kotlin.math.max
import kotlin.math.min

/**
 * Collage — several photographs in one frame.
 *
 * In every reference app and in none of ours, and it is not a small omission: it is one of the two
 * things on Hypic's and AirBrush's home screens, ahead of most of the editing tools. People make
 * collages far more often than they retouch a jawline.
 *
 * The whole thing is a **layout of normalised cells** plus the arithmetic that turns them into
 * layers. Keeping the layouts as fractions of the frame rather than as pixel rectangles is what lets
 * one definition serve a square post, a 9:16 story and a print — and it is why there is one list of
 * layouts here rather than one per output size.
 *
 * Each cell is a **shape layer with the photograph clipped to it** — Photoshop's clipping mask, and
 * the arrangement every collage in this app should be made of. It is not the obvious choice and it
 * is the right one:
 *
 * - A *crop* would bake the framing in, and sliding the photograph inside its cell afterwards is the
 *   first thing anyone does to a collage.
 * - A *vector mask* looks like the answer and is not, because this renderer maps a vector mask
 *   through the layer's own transform (`DocumentRenderer.maskFor`). The window would travel with the
 *   photograph instead of holding still, so dragging the picture would drag its cell around the page
 *   and nothing would ever line up again.
 * - A clipping mask puts the cell in a layer of its own. The photograph moves; the cell does not.
 *
 * It costs one extra layer per cell and buys the empty cells too: a cell with no photograph is still
 * drawn, so a gap in a collage is a visible frame waiting to be filled rather than nothing at all.
 */
object Collage {

    /**
     * A photograph and how large it is.
     *
     * The size is required rather than optional because filling a cell is not possible without it —
     * a collage whose pictures sat at their own pixel size would be a grid of wildly mismatched
     * fragments, and that is the single most visible way to get this wrong.
     */
    data class Photo(val asset: AssetId, val size: Vec2, val name: String = "") {
        init {
            require(size.x > 0f && size.y > 0f) { "a photograph needs a positive size, got $size" }
        }
    }

    /**
     * How the frame is divided.
     *
     * @param cells each as a fraction of the frame, before spacing is taken out.
     */
    data class Layout(
        val name: String,
        val persianLabel: String,
        val cells: List<Rect>,
    ) {
        val count: Int get() = cells.size

        init {
            require(cells.isNotEmpty()) { "a layout needs at least one cell" }
        }
    }

    /**
     * Everything about the look that is not the arrangement.
     *
     * @param spacing gap between cells, as a fraction of the frame's shorter side. A fraction rather
     *   than pixels so the same setting looks the same on a thumbnail and a print.
     * @param margin border around the whole collage, in the same units. Separate from [spacing]
     *   because a collage with a wide outer border and tight inner gaps is a specific, common look
     *   and one slider cannot express it.
     * @param cornerRadius rounding on each cell, again as a fraction of the shorter side.
     * @param background what shows between the cells.
     * @param cell what an empty cell is filled with. Distinct from [background] on purpose: an empty
     *   cell the same colour as the gaps is invisible, and the user cannot tap what they cannot see.
     */
    data class Style(
        val spacing: Float = 0.02f,
        val margin: Float = 0.02f,
        val cornerRadius: Float = 0f,
        val background: Color = Color.WHITE,
        val cell: Color = Color(0.88f, 0.88f, 0.88f),
    )

    /**
     * Builds the document.
     *
     * @param photos one per cell, in order. Extra photographs are ignored and missing ones leave
     *   their cell empty rather than shifting everything up — a collage whose layout changed because
     *   one photograph failed to load would be worse than a gap.
     */
    fun build(
        photos: List<Photo>,
        layout: Layout,
        width: Int,
        height: Int,
        style: Style = Style(),
        id: String = "collage",
    ): Document {
        require(width > 0 && height > 0) { "a collage needs a positive canvas, got ${width}x$height" }

        val shorter = min(width, height).toFloat()
        val gap = (style.spacing * shorter).coerceAtLeast(0f)
        val edge = (style.margin * shorter).coerceAtLeast(0f)
        val radius = (style.cornerRadius * shorter).coerceAtLeast(0f)

        val layers = ArrayList<Layer>(layout.count * 2)
        for ((index, cell) in layout.cells.withIndex()) {
            val frame = frameOf(cell, width.toFloat(), height.toFloat(), gap, edge)
            if (frame.width <= 1f || frame.height <= 1f) continue

            // The cell itself, and the base of the clipping group. Below the photograph in the list
            // because this document's layer order runs bottom to top.
            layers += Layer.Shape(
                id = cellId(id, index),
                geometry = ShapeGeometry.Rectangle(
                    size = Vec2(frame.width, frame.height),
                    cornerRadius = Corners(radius, radius, radius, radius),
                ),
                name = "قاب ${index + 1}",
                transform = Transform(translation = Vec2(frame.left, frame.top)),
                style = LayerStyle(fill = Fill.Solid(style.cell)),
            )

            val photo = photos.getOrNull(index) ?: continue
            layers += Layer.Image(
                id = photoId(id, index),
                asset = photo.asset,
                name = photo.name.ifBlank { "عکس ${index + 1}" },
                transform = fill(photo.size, frame),
                clipped = true,
            )
        }

        return Document(
            id = DocumentId(id),
            canvas = CanvasSpec(width, height, background = Fill.Solid(style.background)),
            name = "کلاژ",
            layers = layers,
        )
    }

    /**
     * How a photograph sits in its cell before anyone touches it.
     *
     * Scaled to **cover** and centred, which is what a collage means by "put this picture here": the
     * alternative — fitting the whole picture inside the cell — leaves a band of background down two
     * sides of every cell whose aspect ratio does not match the layout's, which is most of them.
     * Covering crops, and the crop is recoverable because the photograph keeps its full extent and
     * can be dragged inside the cell afterwards.
     */
    fun fill(photo: Vec2, frame: Rect): Transform {
        val scale = max(frame.width / photo.x, frame.height / photo.y)
        return Transform(
            translation = Vec2(
                frame.left - (photo.x * scale - frame.width) / 2f,
                frame.top - (photo.y * scale - frame.height) / 2f,
            ),
            scale = Vec2(scale, scale),
        )
    }

    /**
     * Where a normalised cell lands, once spacing and the outer margin are taken out.
     *
     * Half the gap on each internal side rather than a whole gap on one — otherwise the first column
     * is narrower than the rest and the whole grid sits off-centre, which is visible immediately on
     * a square layout and is the classic mistake here.
     *
     * The outer margin is applied to the frame first, so a cell touching the edge gets the margin and
     * a cell in the middle gets the gap, rather than both.
     */
    fun frameOf(cell: Rect, width: Float, height: Float, gap: Float, margin: Float): Rect {
        val inner = Rect(margin, margin, width - margin, height - margin)
        val w = max(0f, inner.width)
        val h = max(0f, inner.height)
        val half = gap / 2f

        // A cell edge that sits on the frame's own boundary is not an internal seam, so it gets no
        // half-gap. Comparing against the normalised bounds is what distinguishes the two.
        val left = inner.left + cell.left * w + if (cell.left > EDGE_EPSILON) half else 0f
        val top = inner.top + cell.top * h + if (cell.top > EDGE_EPSILON) half else 0f
        val right = inner.left + cell.right * w - if (cell.right < 1f - EDGE_EPSILON) half else 0f
        val bottom = inner.top + cell.bottom * h - if (cell.bottom < 1f - EDGE_EPSILON) half else 0f
        return Rect(left, top, max(left, right), max(top, bottom))
    }

    /** The cell frame's layer, so a tap on the canvas can be traced back to which cell it was. */
    fun cellId(document: String, index: Int) = LayerId("$document-cell-$index")

    /** The photograph clipped to [cellId]. */
    fun photoId(document: String, index: Int) = LayerId("$document-photo-$index")

    /** An even grid, which is the layout most collages actually are. */
    fun grid(columns: Int, rows: Int, name: String = "${columns}x$rows"): Layout {
        require(columns > 0 && rows > 0) { "a grid needs positive dimensions" }
        val cells = ArrayList<Rect>(columns * rows)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                cells += Rect(
                    column / columns.toFloat(),
                    row / rows.toFloat(),
                    (column + 1) / columns.toFloat(),
                    (row + 1) / rows.toFloat(),
                )
            }
        }
        return Layout(name, "$columns در $rows", cells)
    }

    /**
     * The layouts worth a button.
     *
     * Grids plus the asymmetric ones people actually reach for — a big photograph with two small
     * ones beside it is the commonest collage there is, and no number of even grids produces it.
     * A menu of forty would be worse than this at the only job it has, which is being chosen from
     * quickly.
     */
    val LAYOUTS: List<Layout> = listOf(
        Layout("1", "تکی", listOf(Rect(0f, 0f, 1f, 1f))),
        grid(2, 1, "2-across"),
        grid(1, 2, "2-down"),
        Layout(
            "1+2-right", "یکی بزرگ + دو کوچک",
            listOf(
                Rect(0f, 0f, 0.62f, 1f),
                Rect(0.62f, 0f, 1f, 0.5f),
                Rect(0.62f, 0.5f, 1f, 1f),
            ),
        ),
        Layout(
            "1+2-below", "یکی بالا + دو پایین",
            listOf(
                Rect(0f, 0f, 1f, 0.6f),
                Rect(0f, 0.6f, 0.5f, 1f),
                Rect(0.5f, 0.6f, 1f, 1f),
            ),
        ),
        grid(3, 1, "3-across"),
        grid(2, 2, "4-grid"),
        Layout(
            "1+3-right", "یکی بزرگ + سه کوچک",
            listOf(
                Rect(0f, 0f, 0.66f, 1f),
                Rect(0.66f, 0f, 1f, 1f / 3f),
                Rect(0.66f, 1f / 3f, 1f, 2f / 3f),
                Rect(0.66f, 2f / 3f, 1f, 1f),
            ),
        ),
        Layout(
            "2+3", "دو بالا + سه پایین",
            listOf(
                Rect(0f, 0f, 0.5f, 0.5f),
                Rect(0.5f, 0f, 1f, 0.5f),
                Rect(0f, 0.5f, 1f / 3f, 1f),
                Rect(1f / 3f, 0.5f, 2f / 3f, 1f),
                Rect(2f / 3f, 0.5f, 1f, 1f),
            ),
        ),
        grid(3, 2, "6-grid"),
        grid(3, 3, "9-grid"),
    )

    /** The layouts that fit a given number of photographs exactly. */
    fun forCount(count: Int): List<Layout> = LAYOUTS.filter { it.count == count }

    /**
     * The best layout for a number of photographs when the user has not chosen one.
     *
     * Exact fit first, then the smallest layout that holds them all — a photograph left out of a
     * collage is a worse failure than an empty cell, because the user cannot see what is missing.
     */
    fun bestFor(count: Int): Layout =
        forCount(count).firstOrNull()
            ?: LAYOUTS.filter { it.count >= count }.minByOrNull { it.count }
            ?: LAYOUTS.last()

    /** Below this a cell edge counts as sitting on the frame's boundary rather than beside another. */
    private const val EDGE_EPSILON = 1e-3f
}
