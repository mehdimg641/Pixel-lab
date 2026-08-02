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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Masks
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.EditorState
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
        StructureBar(state, model)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(flatten(state.document.layers), key = { it.layer.id.value }) { row ->
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
private fun StructureBar(state: EditorState, model: EditorViewModel) {
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
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
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
            Text(
                if (layer.expanded) "▾" else "▸",
                color = Ink.TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        model.act {
                            replaceLayer(layer.id) { (it as Layer.Group).copy(expanded = !it.expanded) }
                        }
                    }
                    .padding(horizontal = 4.dp),
            )
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
    Icon(
        icon,
        label,
        tint = if (active) Ink.Text else Ink.TextMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    )
}
