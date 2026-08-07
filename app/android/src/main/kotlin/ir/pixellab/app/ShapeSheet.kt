package ir.pixellab.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Corners
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2

/**
 * The shape tool.
 *
 * Two halves: the primitives to add, and the parameters of the one already selected. The second half
 * is the point. A shape drawn once and then only movable is a drawing; a shape whose corner radius,
 * side count and star depth stay adjustable is a *parametric* shape, which is what Illustrator and
 * Photoshop both give you and what makes a five-pointed star into a burst without redrawing it.
 *
 * Every control writes through the editor, so a slider drag is one undo entry.
 */
@Composable
fun ShapeSheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    val selected = state.primaryLayer as? Layer.Shape

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SheetSection("افزودن")
        SheetChips {
            for (kind in PRIMITIVES) {
                SheetChip(kind.label) { model.addShape(kind.build(), kind.label) }
            }
        }

        if (selected == null) {
            SheetHint("یک شکل اضافه کنید یا یکی را انتخاب کنید تا پارامترهایش اینجا بیاید")
            return@Column
        }

        SheetSection("پارامترها — ${selected.name}")
        when (val geometry = selected.geometry) {
            is ShapeGeometry.Rectangle -> {
                SizeHint(geometry.size)
                // A single radius rather than four: four corners individually is a real feature and
                // it belongs behind a disclosure, not in front of someone drawing a button.
                Param("گردی گوشه", geometry.cornerRadius.topLeft, 0f..maxRadius(geometry.size)) { value ->
                    model.updateShape { (it as ShapeGeometry.Rectangle).copy(cornerRadius = Corners.all(value)) }
                }
            }

            is ShapeGeometry.Ellipse -> SizeHint(geometry.size)

            is ShapeGeometry.Polygon -> {
                SizeHint(geometry.size)
                Param("تعداد ضلع", geometry.sides.toFloat(), MIN_SIDES..MAX_SIDES) { value ->
                    model.updateShape { (it as ShapeGeometry.Polygon).copy(sides = value.roundedSides()) }
                }
                Param("گردی گوشه", geometry.cornerRadius, 0f..maxRadius(geometry.size)) { value ->
                    model.updateShape { (it as ShapeGeometry.Polygon).copy(cornerRadius = value) }
                }
            }

            is ShapeGeometry.Star -> {
                SizeHint(geometry.size)
                Param("تعداد پره", geometry.points.toFloat(), MIN_SIDES..MAX_SIDES) { value ->
                    model.updateShape { (it as ShapeGeometry.Star).copy(points = value.roundedSides()) }
                }
                // The one number that decides whether it reads as a star, a burst or a flower.
                Param("عمق", geometry.innerRadius * PERCENT, MIN_INNER..PERCENT) { value ->
                    model.updateShape { (it as ShapeGeometry.Star).copy(innerRadius = value / PERCENT) }
                }
                Param("گردی گوشه", geometry.cornerRadius, 0f..maxRadius(geometry.size)) { value ->
                    model.updateShape { (it as ShapeGeometry.Star).copy(cornerRadius = value) }
                }
            }

            is ShapeGeometry.Arrow -> {
                Param("اندازهٔ سر", geometry.headSize, MIN_HEAD..MAX_HEAD) { value ->
                    model.updateShape { (it as ShapeGeometry.Arrow).copy(headSize = value) }
                }
                Param("خمیدگی", geometry.bend, -MAX_BEND..MAX_BEND) { value ->
                    model.updateShape { (it as ShapeGeometry.Arrow).copy(bend = value) }
                }
                SheetChips {
                    SheetChip("سرِ دوطرفه", chosen = geometry.tailHead) {
                        model.updateShape { (it as ShapeGeometry.Arrow).copy(tailHead = !geometry.tailHead) }
                    }
                }
            }

            is ShapeGeometry.Line -> SheetHint("خط را با دستگیره‌هایش روی بوم بکشید")

            is ShapeGeometry.Path -> SheetHint("این یک مسیر است — گره‌هایش را با ابزار قلم ویرایش کنید")
        }

        SheetSection("چیدمان")
        SheetChips {
            SheetChip("به جلو") { model.act { raiseLayer(selected.id) } }
            SheetChip("به عقب") { model.act { lowerLayer(selected.id) } }
            SheetChip("جلوترین") { model.act { bringToFront(selected.id) } }
            SheetChip("عقب‌ترین") { model.act { sendToBack(selected.id) } }
        }
    }
}

@Composable
private fun Param(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    SheetSlider(label, value, range, onChange = { next, _ -> onChange(next) })
}

/**
 * The size, as a reading rather than a control.
 *
 * Deliberately not editable here: the handles on the canvas already resize the shape, with the
 * artwork visible under the finger. A second, numeric way to do the same thing on a phone is one
 * more control competing for a sheet that has real parameters to show.
 */
@Composable
private fun SizeHint(size: Vec2) {
    SheetHint("اندازه: ${size.x.toInt()}×${size.y.toInt()} — با دستگیره‌های روی بوم تغییرش دهید")
}

/** A radius larger than half the short side turns the shape into its own inscribed circle. */
private fun maxRadius(size: Vec2) = (minOf(size.x, size.y) / 2f).coerceAtLeast(1f)

private fun Float.roundedSides() = toInt().coerceIn(MIN_SIDES.toInt(), MAX_SIDES.toInt())

private class Primitive(val label: String, val build: () -> ShapeGeometry)

/**
 * The six primitives, at a size that is visible the moment they land.
 *
 * A shape added at a nominal size is one the user has to hunt for and then resize before they can
 * see what it is; these are sized as a fraction of a typical canvas so the first one looks
 * deliberate.
 */
private val PRIMITIVES = listOf(
    Primitive("مستطیل") { ShapeGeometry.Rectangle(Vec2(DEFAULT_SIDE, DEFAULT_SIDE * 0.6f)) },
    Primitive("گِرد") { ShapeGeometry.Rectangle(Vec2(DEFAULT_SIDE, DEFAULT_SIDE * 0.6f), Corners.all(48f)) },
    Primitive("بیضی") { ShapeGeometry.Ellipse(Vec2(DEFAULT_SIDE, DEFAULT_SIDE)) },
    Primitive("چندضلعی") { ShapeGeometry.Polygon(Vec2(DEFAULT_SIDE, DEFAULT_SIDE), sides = 6) },
    Primitive("ستاره") { ShapeGeometry.Star(Vec2(DEFAULT_SIDE, DEFAULT_SIDE), points = 5) },
    Primitive("پیکان") {
        ShapeGeometry.Arrow(from = Vec2(0f, DEFAULT_SIDE / 2f), to = Vec2(DEFAULT_SIDE, DEFAULT_SIDE / 2f))
    },
    Primitive("خط") { ShapeGeometry.Line(from = Vec2(0f, 0f), to = Vec2(DEFAULT_SIDE, 0f)) },
)

/** About a third of a 1080 canvas: large enough to see, small enough to place. */
private const val DEFAULT_SIDE = 360f

private const val MIN_SIDES = 3f
private const val MAX_SIDES = 24f

/** Below this the points meet in the middle and the star becomes a scribble. */
private const val MIN_INNER = 5f

private const val MIN_HEAD = 4f
private const val MAX_HEAD = 96f
private const val MAX_BEND = 240f
private const val PERCENT = 100f
