package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Layer

/**
 * The layer list.
 *
 * Shown top-most first, the opposite of how the document stores them. The document is in paint order
 * because that is what the renderer needs; a person reading a stack expects the front of it at the
 * top, and inverting one to match the other is the whole of the difference.
 */
@Composable
fun LayerPanel(state: EditorState, model: EditorViewModel) {
    val ordered = state.document.layers.asReversed()
    LazyColumn(Modifier.fillMaxWidth()) {
        items(ordered, key = { it.id.value }) { layer ->
            LayerRow(layer, selected = layer.id in state.selection, model = model)
        }
    }
}

@Composable
private fun LayerRow(layer: Layer, selected: Boolean, model: EditorViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Ink.Accent.copy(alpha = 0.14f) else Ink.ChromeRaised)
            .clickable { model.act { select(layer.id) } }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Ink.ChromeSunken),
        )

        Column(Modifier.weight(1f)) {
            Text(
                layer.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) Ink.Accent else Ink.Text,
            )
            if (layer.style.effects.isNotEmpty()) {
                Text(
                    "${layer.style.effects.size} افکت",
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
                active = !model.state.effectsBypassed,
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
            .padding(6.dp)
            .size(20.dp),
    )
}
