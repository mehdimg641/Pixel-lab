package ir.pixellab.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Masks
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.editor.SheetDetent
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.VectorMask

/**
 * The layer list.
 *
 * Shown top-most first, the opposite of how the document stores them. The document is in paint order
 * because that is what the renderer needs; a person reading a stack expects the front of it at the
 * top, and inverting one to match the other is the whole of the difference.
 *
 * Groups are shown nested. A flat list of a document that has groups is not a simplification, it is
 * a lie about where the layers are — and the moment group isolation is real, where a layer sits
 * decides what it blends with.
 */
@Composable
fun LayerPanel(state: EditorState, model: EditorViewModel) {
    Column(Modifier.fillMaxWidth()) {
        LayerStructureBar(state, model)
        LayerOrderBar(state, model)
        LayerStack(state, model)
    }
}

/**
 * The rows alone, so a host can decide what goes above and below them.
 *
 * The work panel puts the stack *first* and the verbs under it, which is where Photoshop has had
 * them since 3.0 and is right for the same reason: a panel called «لایه‌ها» whose first four rows
 * are buttons shows you no layers at all until you scroll, and at 208dp in Console it showed none
 * at any point. A sheet has the room to lead with its controls; a docked panel does not.
 */
@Composable
internal fun LayerStack(state: EditorState, model: EditorViewModel) {
    Column(Modifier.fillMaxWidth()) {
        // **A plain column, not a `LazyColumn`.**
        //
        // Virtualisation buys nothing here and costs the ability to put this panel anywhere: a lazy
        // list may not sit inside a scrolling parent — Compose measures it with an infinite height
        // and throws — so as long as this was lazy, the two bars above it could never scroll with
        // it. In a 208dp Console panel that meant the bars filled the panel and the stack was
        // clipped to nothing.
        //
        // `flatten` already builds every row eagerly, so nothing was being skipped anyway; the only
        // thing that was lazy was the *composition* of rows past the fold, on a list whose realistic
        // length is under fifty. The host decides where the scrolling happens now.
        for (row in flatten(state.document.layers)) {
            key(row.layer.id.value) {
                LayerRow(row, selected = row.layer.id in state.selection, state = state, model = model)
            }
        }
    }
}

/** One visible line of the panel: a layer and how deep in the tree it sits. */
private data class PanelRow(val layer: Layer, val depth: Int)

/**
 * Flattens the tree for the list, front to back, skipping collapsed groups.
 *
 * The stack is reversed at every level rather than only at the top: a child of a group has to read
 * front-first for the same reason its parent does.
 */
private fun flatten(layers: List<Layer>, depth: Int = 0): List<PanelRow> =
    layers.asReversed().flatMap { layer ->
        val row = PanelRow(layer, depth)
        if (layer is Layer.Group && layer.expanded) {
            listOf(row) + flatten(layer.children, depth + 1)
        } else {
            listOf(row)
        }
    }

/**
 * The four structural actions, above the list rather than inside a row.
 *
 * They act on the selection, and three of the four need more than one layer or a specific kind, so
 * putting them on each row would mean four buttons per row that are usually disabled. Enabled state
 * is computed from the selection so a disabled button is a readable answer rather than a dead one.
 */
@Composable
internal fun LayerStructureBar(state: EditorState, model: EditorViewModel) {
    val selected = state.selectedLayers
    val primary = state.selection.primary
    val topLevel = state.document.layers.map { it.id }.toSet()
    val group = selected.singleOrNull() as? Layer.Group
    val clippable = primary != null && state.document.layers.indexOfFirst { it.id == primary } > 0

    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink.Chrome)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StructureButton(
            icon = Icons.Filled.CreateNewFolder,
            label = "گروه",
            // Only top-level layers can be gathered: grouping across two parents is a reparent, and
            // doing that silently loses the arrangement the user had.
            enabled = selected.size >= 2 && selected.all { it.id in topLevel },
        ) {
            model.act { groupLayers(selected.map { it.id }, nextLayerId("group")) }
        }

        StructureButton(Icons.Filled.FolderOff, "بازکردن", enabled = group != null) {
            model.act { ungroup(group!!.id) }
        }

        StructureButton(
            icon = Icons.Filled.ContentCut,
            label = "برش",
            enabled = clippable,
            active = selected.singleOrNull()?.clipped == true,
        ) {
            val layer = state.document.findLayer(primary!!) ?: return@StructureButton
            model.act { setClipped(layer.id, !layer.clipped) }
        }

        StructureButton(
            icon = Icons.Filled.Masks,
            label = "ماسک",
            enabled = primary != null,
            active = selected.singleOrNull()?.vectorMask != null,
        ) {
            val layer = state.document.findLayer(primary!!) ?: return@StructureButton
            model.act {
                // An ellipse the size of the layer: the mask people reach for first, and one that
                // is immediately visible so the control has an obvious effect.
                val box = model.bounds.of(layer)
                setVectorMask(
                    layer.id,
                    if (layer.vectorMask != null) {
                        null
                    } else {
                        VectorMask(ShapeGeometry.Ellipse(Vec2(box.width, box.height)))
                    },
                )
            }
        }

        StructureButton(Icons.Filled.Link, "کپی زنده", enabled = primary != null) {
            model.act { addInstance(primary!!, nextLayerId("instance")) }
        }
    }
}

/**
 * Where a layer sits, and how it blends.
 *
 * Buttons rather than drag-to-reorder. A drag inside a sheet on a phone competes with the sheet's
 * own vertical scroll and with the drag that dismisses it; two of the three gestures then feel
 * unreliable. One press moving one place is slower to think about and never wrong.
 */
@Composable
internal fun LayerOrderBar(state: EditorState, model: EditorViewModel) {
    val primary = state.selection.primary
    val layer = primary?.let { state.document.findLayer(it) }
    val target = primary?.let { groupBelow(state.document.layers, it) }
    val nested = primary != null && state.document.layers.none { it.id == primary }

    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink.Chrome)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StructureButton(Icons.Filled.KeyboardArrowUp, "بالا", enabled = layer != null) {
            model.act { raiseLayer(primary!!) }
        }
        StructureButton(Icons.Filled.KeyboardArrowDown, "پایین", enabled = layer != null) {
            model.act { lowerLayer(primary!!) }
        }
        StructureButton(Icons.Filled.SubdirectoryArrowRight, "به گروه", enabled = target != null) {
            model.act { moveIntoGroup(primary!!, target!!) }
        }
        StructureButton(Icons.Filled.NorthWest, "از گروه بیرون", enabled = nested) {
            model.act { moveOutOfGroup(primary!!) }
        }
        StructureButton(Icons.Filled.Tune, "پارامترها", enabled = layer != null) {
            model.act { openSheet(SheetContent.LayerParameters(primary!!), SheetDetent.FULL) }
        }
    }
}

/**
 * The group a layer would drop into: the nearest one below it among its own siblings.
 *
 * Below rather than above, because "into the group" on a stack read front-to-back means the group
 * the layer is currently sitting on top of. Only siblings are considered — moving a layer into a
 * group somewhere else in the tree is a different intent, and guessing at it moves the user's work
 * somewhere they did not ask for.
 */
private fun groupBelow(layers: List<Layer>, id: ir.pixellab.core.model.LayerId): ir.pixellab.core.model.LayerId? {
    val index = layers.indexOfFirst { it.id == id }
    if (index >= 0) {
        for (below in index - 1 downTo 0) {
            (layers[below] as? Layer.Group)?.let { return it.id }
        }
        return null
    }
    for (layer in layers) {
        if (layer is Layer.Group) groupBelow(layer.children, id)?.let { return it }
    }
    return null
}

@Composable
private fun StructureButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            // Ten points of padding around an icon made a 44dp target — close enough to look right
            // and short enough to miss. The floor is stated rather than arrived at.
            .sizeIn(minWidth = Space.touch, minHeight = Space.touch)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val tint = when {
            !enabled -> Ink.Divider
            active -> Ink.Accent
            else -> Ink.Text
        }
        Icon(icon, label, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerRow(row: PanelRow, selected: Boolean, state: EditorState, model: EditorViewModel) {
    val layer = row.layer
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Ink.Accent.copy(alpha = 0.14f) else Ink.ChromeRaised)
            .combinedClickable(
                onClick = { model.act { select(layer.id) } },
                // Long press adds to the selection. Grouping needs more than one layer and a sheet
                // has no modifier key, so the gesture has to carry it.
                onLongClick = { model.act { select(layer.id, additive = true) } },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Indentation is the only thing telling the user a layer is inside a group, and with
        // isolation real that is the difference between blending with the document and not.
        if (row.depth > 0) Box(Modifier.width((row.depth * 14).dp))

        if (layer is Layer.Group) {
            // A triangle four points wide is not a target. It only escaped the measurement
            // because the fixture document has no group in it — which is exactly how a control
            // this small survives in a panel nobody has measured.
            Box(
                Modifier
                    .size(Space.touch)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = if (layer.expanded) "بستن گروه" else "بازکردن گروه") {
                        model.act {
                            replaceLayer(layer.id) { (it as Layer.Group).copy(expanded = !it.expanded) }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (layer.expanded) "▾" else "▸", color = Ink.TextMuted)
            }
        }

        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Ink.ChromeSunken),
        )

        Column(Modifier.weight(1f)) {
            Text(
                // The arrow is how Photoshop marks a clipped layer, and reading it at a glance is
                // the difference between understanding a stack and guessing at it.
                if (layer.clipped) "↳ ${layer.name}" else layer.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) Ink.Accent else Ink.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val notes = buildList {
                if (layer.style.effects.isNotEmpty()) add("${layer.style.effects.size} افکت")
                if (layer.mask != null || layer.vectorMask != null) add("ماسک")
                if (layer is Layer.Group && !layer.passThrough) add("ایزوله")
                if (layer is Layer.Instance) add("کپی زنده")
            }
            if (notes.isNotEmpty()) {
                Text(
                    notes.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.TextMuted,
                )
            }
        }

        // The fx badge hides every effect while held, which is the fastest way to see what a stack
        // is actually contributing. A view state, never a document edit.
        if (layer.style.effects.isNotEmpty()) {
            RowIcon(
                icon = Icons.Filled.AutoAwesome,
                label = "افکت‌ها",
                active = !state.effectsBypassed,
            ) { model.act { setEffectsBypassed(!state.effectsBypassed) } }
        }

        RowIcon(
            icon = if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            label = "نمایش",
            active = layer.visible,
        ) { model.act { setLayerVisible(layer.id, !layer.visible) } }

        RowIcon(
            icon = if (layer.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            label = "قفل",
            active = !layer.locked,
        ) { model.act { setLayerLocked(layer.id, !layer.locked) } }
    }
}

@Composable
private fun RowIcon(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    // The target is the Box; the icon is what it draws. Icon-plus-padding came to 36dp square,
    // which is what a target sized by its *graphic* rather than by a finger always comes to — and
    // these two are pressed more than anything else in the panel.
    Box(
        Modifier
            .size(Space.touch)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (active) Ink.Text else Ink.TextMuted)
    }
}
