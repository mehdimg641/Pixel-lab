package ir.pixellab.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.AlignEdge
import ir.pixellab.core.editor.AlignTarget
import ir.pixellab.core.editor.DistributeAxis
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Document
import kotlinx.coroutines.launch

/**
 * Where things sit: aligning, distributing, mirroring, turning, and the exact numbers.
 *
 * The largest hole the feature audit found — about twenty rows of the 1500 with nothing behind
 * them. Every control here is arithmetic on boxes, which is why it is worth having: a phone screen
 * is exactly where nudging things into line by hand is hardest, and exactly where "make these three
 * evenly spaced" is most valuable.
 */
@Composable
fun ArrangeSheetBody(
    state: EditorState,
    model: EditorViewModel,
    render: suspend (Document) -> ir.pixellab.core.codec.RasterImage?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val chosen = state.selection.ids
    val id = state.selection.primary
    var target by remember { mutableStateOf(AlignTarget.SELECTION) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SheetHint(
            when (chosen.size) {
                0 -> "چیزی انتخاب نشده"
                1 -> "یک لایه — نسبت به بوم تراز می‌شود"
                else -> "${chosen.size} لایه انتخاب شده"
            },
        )

        SheetSection("تراز نسبت به")
        SheetChips {
            for (option in AlignTarget.entries) {
                SheetChip(
                    option.persianLabel,
                    chosen = target == option,
                    // With one layer there is no selection box to align against, so the choice
                    // would be a control that silently does nothing.
                    enabled = chosen.size > 1 || option == AlignTarget.CANVAS,
                ) { target = option }
            }
        }

        SheetSection("ترازبندی")
        SheetChips {
            for (edge in AlignEdge.entries) {
                SheetChip(edge.persianLabel, enabled = chosen.isNotEmpty()) {
                    model.act {
                        alignLayers(
                            chosen,
                            edge,
                            if (chosen.size > 1) target else AlignTarget.CANVAS,
                        )
                    }
                }
            }
        }

        SheetSection("توزیع یکنواخت")
        SheetChips {
            for (axis in DistributeAxis.entries) {
                SheetChip(axis.persianLabel, enabled = chosen.size >= MIN_TO_DISTRIBUTE) {
                    model.act { distributeLayers(chosen, axis) }
                }
            }
        }
        SheetHint(
            if (chosen.size >= MIN_TO_DISTRIBUTE) {
                "دو لایهٔ بیرونی سرِ جایشان می‌مانند و فاصلهٔ بینشان یکنواخت می‌شود"
            } else {
                "برای توزیع دست‌کم سه لایه لازم است — با دو تا یک فاصله هست و وسطی نیست"
            },
        )

        SheetSection("قرینه و چرخش")
        SheetChips {
            SheetChip("قرینهٔ افقی", enabled = id != null) { model.act { flipLayer(id!!, true) } }
            SheetChip("قرینهٔ عمودی", enabled = id != null) { model.act { flipLayer(id!!, false) } }
        }
        SheetChips {
            SheetChip("بوم ۹۰° ساعت‌گرد") { model.act { rotateCanvas(1) } }
            SheetChip("بوم ۹۰° پادساعت‌گرد") { model.act { rotateCanvas(-1) } }
            SheetChip("بوم ۱۸۰°") { model.act { rotateCanvas(2) } }
        }

        if (id != null) {
            Placement(state, model, modifier = Modifier.padding(top = 8.dp))
        }

        // ---- linking and comps ---------------------------------------------------------------

        SheetSection("پیوند")
        val linked = state.selection.ids.count { state.document.links.isLinked(it) }
        SheetAction("پیوند دادن انتخاب", enabled = state.selection.size >= 2) {
            model.act { linkSelected() }
        }
        SheetAction("برداشتن پیوند", enabled = linked > 0) { model.act { unlinkSelected() } }
        // The distinction that gets confused with grouping, said once and plainly.
        SheetHint("لایه‌های پیوندخورده با هم جابه‌جا می‌شوند — ولی گروه نمی‌شوند، پس افکت هرکدام مال خودش می‌ماند")

        SheetSection("ترکیب‌های لایه")
        var compName by remember { mutableStateOf("") }
        SheetNumberField("نام ترکیب", compName) { compName = it }
        SheetAction("ثبت وضعیت فعلی", enabled = compName.isNotBlank()) {
            model.act { captureComp(compName) }
            compName = ""
        }
        SheetChips {
            for (comp in state.document.comps) {
                SheetChip(comp.name) { model.act { applyComp(comp.name) } }
            }
        }
        if (state.document.comps.isNotEmpty()) {
            SheetHint("یک ترکیب فقط دیده‌شدن و جای لایه‌ها را برمی‌گرداند — نه رنگ و متن، پس کار بعدی‌تان را پاک نمی‌کند")
        }

        SheetSection("ادغام")
        SheetAction("ادغام با لایهٔ زیر", enabled = id != null) {
            scope.launch { model.mergeDown(render) }
        }
        SheetAction("ادغام لایه‌های مرئی", enabled = state.document.layers.count { it.visible } > 1) {
            scope.launch { model.mergeVisible(render) }
        }
        SheetAction("تبدیل به پیکسل", enabled = id != null) {
            scope.launch { model.rasterize(render) }
        }
        // Said before it happens: after this the effects are baked and the text is not text.
        SheetHint("«تبدیل به پیکسل» لایه را با تصویر خودش جایگزین می‌کند — افکت‌ها پخته می‌شوند")
    }
}

/**
 * The exact numbers.
 *
 * Position is the bounding box's top-left, which is what the user reads off the canvas — on a
 * rotated layer that differs from the transform's own translation, and showing the raw value would
 * put a number in the field that does not match anything visible.
 */
@Composable
private fun Placement(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    val id = state.selection.primary ?: return
    val box = model.placementOf(id) ?: return
    val layer = state.document.findLayer(id) ?: return

    // Re-seeded whenever the layer moves on the canvas, so dragging updates the fields rather than
    // leaving them showing where the layer used to be.
    var x by remember(box.left) { mutableStateOf(box.left.toInt().toString()) }
    var y by remember(box.top) { mutableStateOf(box.top.toInt().toString()) }
    var angle by remember(layer.transform.rotation) {
        mutableStateOf(layer.transform.rotation.toInt().toString())
    }

    Column(modifier) {
        SheetSection("مختصات دقیق")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetNumberField("X", x, modifier = Modifier.weight(1f)) { x = signedDigits(it) }
            SheetNumberField("Y", y, modifier = Modifier.weight(1f)) { y = signedDigits(it) }
            SheetNumberField("چرخش", angle, modifier = Modifier.weight(1f)) { angle = signedDigits(it) }
        }
        SheetHint("اندازه: ${box.width.toInt()}×${box.height.toInt()}")
        SheetAction("اعمال") {
            model.setPlacement(id, x.toFloatOrNull(), y.toFloatOrNull(), angle.toFloatOrNull())
        }
    }
}

/** Digits and one leading minus: a coordinate can be negative, a canvas dimension cannot. */
private fun signedDigits(entry: String): String {
    val negative = entry.startsWith("-")
    val digits = entry.filter { it.isDigit() }.take(MAX_DIGITS)
    return if (negative) "-$digits" else digits
}

private const val MIN_TO_DISTRIBUTE = 3
private const val MAX_DIGITS = 6
