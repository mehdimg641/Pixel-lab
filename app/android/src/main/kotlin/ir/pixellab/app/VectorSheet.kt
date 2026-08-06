package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.render.ParameterSpec
import ir.pixellab.core.vector.NodeType
import ir.pixellab.core.vector.WidthProfile
import ir.pixellab.engine.android.PathOperation

/**
 * The vector sheet.
 *
 * Four groups, in the order the work happens: draw the path, shape its nodes, stroke it, and combine
 * it with others. Pathfinder is last because it needs more than one shape and therefore cannot be
 * the first thing anyone does.
 */
@Composable
fun VectorSheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    var strokeWidth by remember { mutableStateOf(12f) }
    var profileIndex by remember { mutableStateOf(0) }
    var offsetAmount by remember { mutableStateOf(10f) }
    val pen = model.pen

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Section("قلم")
        ChipRow {
            Chip("رسم", chosen = pen.mode == PenMode.DRAW) { pen.mode = PenMode.DRAW }
            Chip("ویرایش گره", chosen = pen.mode == PenMode.EDIT) { pen.mode = PenMode.EDIT }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip("تمام کردن", chosen = false, enabled = !pen.isEmpty) { model.commitPenPath() }
            Chip("پاک کردن", chosen = false, enabled = !pen.isEmpty) { pen.reset() }
            // Loading an existing shape is what turns the pen into a node editor for artwork that
            // is already there — without it every path is write-once.
            Chip(
                "ویرایش شکل انتخاب‌شده",
                chosen = false,
                enabled = (state.primaryLayer as? Layer.Shape)?.geometry is ShapeGeometry.Path,
            ) {
                ((state.primaryLayer as? Layer.Shape)?.geometry as? ShapeGeometry.Path)?.let(pen::load)
            }
        }

        Section("گره")
        ChipRow {
            for (type in NodeType.entries) {
                Chip(labelOf(type), chosen = pen.activeType() == type, enabled = pen.active != null) {
                    pen.retype(type)
                }
            }
        }
        ChipRow {
            Chip("حذف گره", chosen = false, enabled = pen.active != null) { pen.removeActive() }
            Chip("بریدن مسیر", chosen = false, enabled = pen.active != null) { pen.splitActive() }
        }

        Section("ضخامت متغیر")
        Text(
            "خطِ ضخامت‌دار به شکل پرشده تبدیل می‌شود، پس گرادینت، افکت و عملیات بولی مثل هر شکل دیگری " +
                "رویش کار می‌کنند — همان کاری که Outline Stroke ایلاستریتور می‌کند",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Slider("ضخامت", strokeWidth, 1f..120f) { strokeWidth = it }
        ChipRow {
            for ((index, entry) in WidthProfile.ALL.withIndex()) {
                Chip(entry.first, chosen = profileIndex == index) { profileIndex = index }
            }
        }
        ChipRow {
            Chip(
                "تبدیل به شکل پرشده",
                chosen = false,
                enabled = !pen.isEmpty || (state.primaryLayer as? Layer.Shape)?.geometry is ShapeGeometry.Path,
            ) {
                model.outlineStroke(strokeWidth, WidthProfile.ALL[profileIndex].second)
            }
        }

        Section("Pathfinder")
        Text(
            if (state.selection.size < 2) "دو شکل یا بیشتر انتخاب کنید" else "${state.selection.size} شکل انتخاب شده",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ChipRow {
            for (operation in PathOperation.entries) {
                Chip(labelOf(operation), chosen = false, enabled = state.selection.size >= 2) {
                    model.combineShapes(operation)
                }
            }
        }
        ChipRow {
            Chip("تقسیم", chosen = false, enabled = state.selection.size >= 2) { model.divideShapes() }
        }

        Section("افست مسیر")
        Slider("فاصله", offsetAmount, -60f..60f) { offsetAmount = it }
        ChipRow {
            Chip(
                "اعمال افست",
                chosen = false,
                enabled = (state.primaryLayer as? Layer.Shape)?.geometry is ShapeGeometry.Path,
            ) {
                model.offsetPath(offsetAmount)
            }
        }

        Section("SVG")
        ChipRow {
            Chip("کپی به‌صورت SVG", chosen = false, enabled = !pen.isEmpty) { model.copyPathAsSvg() }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = Ink.TextMuted,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * Delegates to [SheetChip].
 *
 * It used to be a `Text` with a click and eight points of padding — 36.5dp tall, under the touch
 * minimum, and one of four private chips across the sheets that had each drifted into the same
 * defect independently. `SheetChip`'s own documentation warned about exactly this: "two sheets with
 * their own private chip drift apart within a week". They did.
 *
 * Kept as a local name rather than deleted so the call sites stay short.
 */
@Composable
private fun Chip(
    label: String,
    chosen: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = SheetChip(label, chosen = chosen, enabled = enabled, onClick = onClick)

@Composable
private fun Slider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    PrecisionSlider(
        spec = ParameterSpec.Slider(key = label, label = label, range = range, default = value),
        value = value,
        onChange = { next, _ -> onChange(next) },
        onCommit = {},
    )
}

private fun labelOf(type: NodeType) = when (type) {
    NodeType.CORNER -> "گوشه"
    NodeType.SMOOTH -> "نرم"
    NodeType.SYMMETRIC -> "متقارن"
}

private fun labelOf(operation: PathOperation) = when (operation) {
    PathOperation.UNITE -> "اجتماع"
    PathOperation.MINUS_FRONT -> "کم‌کردن جلویی"
    PathOperation.INTERSECT -> "اشتراک"
    PathOperation.EXCLUDE -> "استثنا"
}
