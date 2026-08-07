package ir.pixellab.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AlignHorizontalLeft
import androidx.compose.material.icons.automirrored.outlined.AlignHorizontalRight
import androidx.compose.material.icons.outlined.AlignHorizontalCenter
import androidx.compose.material.icons.outlined.AlignVerticalBottom
import androidx.compose.material.icons.outlined.AlignVerticalCenter
import androidx.compose.material.icons.outlined.AlignVerticalTop
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.HorizontalDistribute
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.VerticalDistribute
import androidx.compose.ui.graphics.vector.ImageVector
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

        // Diagrams rather than words. An alignment is a *spatial* fact: «وسط افقی» has to be read
        // and then pictured, where the icon simply is the picture. Split across two rows by axis,
        // which is the grouping the icons themselves already imply — and the one `isHorizontal`
        // has always known about.
        SheetSection("ترازبندی")
        for (horizontal in listOf(true, false)) {
            SheetChips {
                for (edge in AlignEdge.entries.filter { it.isHorizontal == horizontal }) {
                    SheetIconChip(edge.icon, edge.persianLabel, enabled = chosen.isNotEmpty()) {
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
        }

        SheetSection("توزیع یکنواخت")
        SheetChips {
            for (axis in DistributeAxis.entries) {
                SheetIconChip(axis.icon, axis.persianLabel, enabled = chosen.size >= MIN_TO_DISTRIBUTE) {
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

        SheetSection("قرینهٔ لایه")
        SheetChips {
            SheetIconChip(Icons.Outlined.Flip, "قرینهٔ افقی", enabled = id != null) {
                model.act { flipLayer(id!!, true) }
            }
            SheetIconChip(Icons.Outlined.SwapVert, "قرینهٔ عمودی", enabled = id != null) {
                model.act { flipLayer(id!!, false) }
            }
        }

        // Its own heading, because these three turn the **canvas** and the two above turn the
        // layer. As text pills the word «بوم» carried that distinction; as icons it would be lost,
        // and rotating the whole artboard when you meant to rotate one layer is not a small
        // surprise.
        SheetSection("چرخش بوم")
        SheetChips {
            // Deliberately *not* the auto-mirrored rotate icons. Those flip under a right-to-left
            // layout because a "go back" arrow should, and a canvas rotation is not a reading
            // direction — mirroring it would draw a counter-clockwise arrow on the clockwise
            // button for every user of this application.
            SheetIconChip(Icons.Outlined.Rotate90DegreesCw, "بوم ۹۰° ساعت‌گرد") { model.act { rotateCanvas(1) } }
            SheetIconChip(Icons.Outlined.Rotate90DegreesCcw, "بوم ۹۰° پادساعت‌گرد") { model.act { rotateCanvas(-1) } }
            SheetIconChip(Icons.Outlined.Autorenew, "بوم ۱۸۰°") { model.act { rotateCanvas(2) } }
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
    var skewX by remember(layer.transform.skew.x) {
        mutableStateOf(layer.transform.skew.x.toInt().toString())
    }
    var skewY by remember(layer.transform.skew.y) {
        mutableStateOf(layer.transform.skew.y.toInt().toString())
    }
    // Recovered from the corners rather than stored twice: the model's four points are the truth,
    // and a second copy of "how much perspective" would be one more thing that can disagree.
    var perspectiveAmount by remember(layer.transform.perspective) {
        mutableStateOf(
            layer.transform.perspective?.let { warp ->
                val span = (warp.bottomRight.x - warp.bottomLeft.x).takeIf { it != 0f } ?: 1f
                (warp.topLeft.x - warp.bottomLeft.x) * 2f / span
            } ?: 0f,
        )
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
        // Skew beside rotation rather than on its own screen: they are the same gesture in
        // Photoshop's Transform menu, and a designer reaching for one usually wants the other.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetNumberField("اریب افقی", skewX, modifier = Modifier.weight(1f)) { skewX = signedDigits(it) }
            SheetNumberField("اریب عمودی", skewY, modifier = Modifier.weight(1f)) { skewY = signedDigits(it) }
        }
        // Perspective is a corner drag in Photoshop and one degree of freedom in practice: moving a
        // corner moves its pair symmetrically, which is exactly what makes it perspective rather
        // than distort. So it is one slider here, applied on release rather than on every step,
        // because each change rebuilds the layer's four corners.
        SheetSlider(
            label = "پرسپکتیو",
            value = perspectiveAmount,
            range = -0.9f..0.9f,
            onChange = { value, _ -> perspectiveAmount = value },
            onCommit = { model.setPerspective(id, perspectiveAmount) },
        )
        SheetHint("اندازه: ${box.width.toInt()}×${box.height.toInt()} — اریب بر حسب درجه، تا ۸۵")
        SheetAction("اعمال") {
            model.setPlacement(
                id,
                x.toFloatOrNull(),
                y.toFloatOrNull(),
                angle.toFloatOrNull(),
                skewX.toFloatOrNull(),
                skewY.toFloatOrNull(),
            )
            // **And the perspective above it.** It used to commit only when the slider was
            // released, which meant a user who moved the slider and then pressed the button
            // labelled «اعمال» — the obvious thing to do, and the only thing the layout suggests —
            // saw nothing happen to it. A button that applies four of the five controls above it is
            // worse than no button, because the two that did work prove it is not broken.
            model.setPerspective(id, perspectiveAmount)
        }

        // **Free distort — the eight numbers the slider above stands in for.**
        //
        // Perspective is one degree of freedom: a corner and its pair move together, which is what
        // makes it perspective. Distort is four independent corners, and the model has carried them
        // in `Transform.perspective` from the beginning — `Affine.warpAware` builds the projective
        // matrix and the compositor samples through it. There was simply no way for a finger to
        // reach any of it.
        //
        // A mode rather than a held modifier, because a phone has no modifier key and every
        // two-finger variant collides with the pinch that zooms the canvas.
        SheetSection("اعوجاج آزاد")
        SheetChips {
            SheetChip("کشیدن گوشه‌ها", chosen = state.distorting) {
                model.setDistorting(!state.distorting)
            }
            SheetChip("بازنشانی اعوجاج", enabled = layer.transform.perspective != null) {
                model.clearDistortion()
            }
        }
        SheetHint(
            if (state.distorting) {
                "حالا هر گوشه آزاد است — سه گوشهٔ دیگر سرِ جایشان می‌مانند"
            } else {
                "روشن کنید، بعد گوشه‌های کادر انتخاب را روی بوم بکشید"
            },
        )
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

/**
 * The diagram for each alignment.
 *
 * Kept beside the sheet rather than on the enum in `core:editor`, because that module knows nothing
 * about Compose and should not start now for the sake of a picture.
 */
private val AlignEdge.icon: ImageVector
    get() = when (this) {
        AlignEdge.LEFT -> Icons.AutoMirrored.Outlined.AlignHorizontalLeft
        AlignEdge.CENTER_X -> Icons.Outlined.AlignHorizontalCenter
        AlignEdge.RIGHT -> Icons.AutoMirrored.Outlined.AlignHorizontalRight
        AlignEdge.TOP -> Icons.Outlined.AlignVerticalTop
        AlignEdge.CENTER_Y -> Icons.Outlined.AlignVerticalCenter
        AlignEdge.BOTTOM -> Icons.Outlined.AlignVerticalBottom
    }

private val DistributeAxis.icon: ImageVector
    get() = when (this) {
        DistributeAxis.HORIZONTAL -> Icons.Outlined.HorizontalDistribute
        DistributeAxis.VERTICAL -> Icons.Outlined.VerticalDistribute
    }
