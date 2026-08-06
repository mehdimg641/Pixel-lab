package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.Editor
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.render.ParameterSpec
import ir.pixellab.core.render.ParameterValue
import ir.pixellab.core.render.builtinEffectRegistry
import ir.pixellab.core.render.flag
import ir.pixellab.core.render.num
import ir.pixellab.core.render.optionName

/**
 * The parameter sheet, built entirely from what an effect declares.
 *
 * Nothing here names a concrete effect. Adding one means adding a module in `core:render`; its panel
 * appears with no change to this file. That is the whole point of the module design — a
 * hand-written screen per effect is the coupling that makes a plug-in architecture a fiction.
 */
@Composable
fun ParameterSheetBody(
    state: EditorState,
    content: SheetContent.EffectParameters,
    model: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val act: (Editor.() -> Unit) -> Unit = model::act
    val layer = state.document.findLayer(content.layer) ?: return
    val effect = layer.style.effects.getOrNull(content.effectIndex) ?: return
    val module = builtinEffectRegistry[ir.pixellab.core.render.EffectRegistry.idOf(effect)] ?: return

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(
            module.label,
            style = MaterialTheme.typography.titleMedium,
            color = Ink.Text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        for (spec in builtinEffectRegistry.parametersOf(effect)) {
            val value = builtinEffectRegistry.read(effect, spec.key) ?: continue
            val write: (ParameterValue, Boolean) -> Unit = { v, continuous ->
                act { setEffectParameter(content.layer, content.effectIndex, spec.key, v, continuous) }
            }

            when (spec) {
                is ParameterSpec.Slider -> PrecisionSlider(
                    spec = spec,
                    value = (value as? ParameterValue.Number)?.value ?: spec.default,
                    onChange = { v, continuous -> write(num(v), continuous) },
                    onCommit = { act { endScrub() } },
                )

                is ParameterSpec.Toggle -> ToggleRow(
                    label = spec.label,
                    checked = (value as? ParameterValue.Flag)?.value ?: spec.default,
                    onChange = { write(flag(it), false) },
                )

                is ParameterSpec.Choice -> ChoiceRow(
                    spec = spec,
                    selected = value.optionName,
                    onChange = { write(ParameterValue.Option(it), false) },
                )

                is ParameterSpec.ColorPicker -> SwatchRow(
                    label = spec.label,
                    color = (value as? ParameterValue.Tint)?.value ?: Color.BLACK,
                    onChange = { write(ParameterValue.Tint(it), false) },
                )

                // The full editor rather than a colour swatch: a stroke or an overlay takes any
                // fill, and offering only a solid one silently flattened every gradient the moment
                // the user opened the panel to check a shade.
                is ParameterSpec.FillPicker -> {
                    Text(
                        spec.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink.TextMuted,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                    )
                    FillEditor(
                        fill = (value as? ParameterValue.Paint)?.value ?: Fill.Solid(Color.BLACK),
                        model = model,
                        onChange = { write(ParameterValue.Paint(it), false) },
                    )
                }

                // The curve editor is a control of its own and lands with the bevel LUTs; showing a
                // dead placeholder would be worse than showing what it is.
                is ParameterSpec.CurveEditor -> PendingRow(spec.label)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is the target and the row carries the semantics, which is two fixes in one.
    // Material's Switch is 52×32dp — under the minimum on its short axis, and there is no way to
    // enlarge it without drawing a different switch — and it announced neither what it was for nor
    // whether it was on, because the label beside it is a separate Text that means nothing to a
    // screen reader. Making the row the control is also how every settings list on the platform
    // behaves: the label is part of the switch, not a caption next to it.
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Space.touch)
            .toggleable(
                value = checked,
                onValueChange = onChange,
                role = Role.Switch,
            )
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink.Text)
        // Null: the row above handles the press and owns the semantics, and a switch that also
        // took the click would announce itself a second time and swallow taps meant for the row.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ChoiceRow(spec: ParameterSpec.Choice, selected: String?, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            spec.label,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.Text,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        // Through the shared chip. This was a fifth private one — a `Text` with a click and eight
        // points of padding, 36dp tall and running off the edge of a scroller. It sits behind every
        // layer effect in the application, which is the worst place to keep a private copy of a
        // control that had already been fixed four times elsewhere.
        SheetChips {
            for (option in spec.options) {
                SheetChip(option.label, chosen = option.value == selected) { onChange(option.value) }
            }
        }
    }
}

/**
 * A colour swatch with a small fixed palette.
 *
 * A full picker is a screen of its own; until it lands, the common choices are one tap away rather
 * than unreachable.
 */
@Composable
private fun SwatchRow(label: String, color: Color, onChange: (Color) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink.Text)
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.toUi()),
            )
        }
        // The third copy of this row, and the last. Thirty-two point squares with the click on the
        // square itself, no names for a screen reader, and a scroller that cut the last colour in
        // half — the same three defects the other two had grown independently.
        ColorSwatches(
            swatches = BASIC_SWATCHES,
            current = color,
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.tight),
            onPick = onChange,
        )
    }
}

@Composable
private fun PendingRow(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink.TextMuted)
        Text("به‌زودی", style = MaterialTheme.typography.labelMedium, color = Ink.TextMuted)
    }
}

/** Divider used between sections of a sheet. */
@Composable
fun SheetDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Divider))
}

/** The grab handle at the top of a sheet. */
@Composable
fun SheetGrip(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        // Outline rather than divider: a grip drawn in the same value as a hairline rule is
        // invisible on the sheet's own surface, and a grip nobody can see is a sheet nobody drags.
        Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.Outline))
    }
}

private fun Color.toUi() = UiColor(r, g, b, a)

