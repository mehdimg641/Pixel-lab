package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.unit.dp
import ir.pixellab.core.model.Color
import ir.pixellab.core.paint.BrushMode
import ir.pixellab.core.model.ToneRange
import ir.pixellab.core.paint.BrushPreset
import ir.pixellab.core.paint.BrushTip
import ir.pixellab.core.render.ParameterSpec

/**
 * The brush sheet.
 *
 * Presets first, then the four controls that get touched constantly. Photoshop's brush panel has
 * twelve groups; putting all of them on a phone would mean none of them is reachable, and the ones
 * below cover what a cover design actually needs. The rest live in the preset.
 *
 * Size and hardness edit the *preset*, not a separate live brush, so the sheet and the tool can
 * never disagree about what is about to be painted.
 */
@Composable
fun BrushSheetBody(model: EditorViewModel, modifier: Modifier = Modifier) {
    val preset = model.paint.preset

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        PresetRow(preset) { model.paint.preset = it }

        // What the dab does when it lands. A row rather than a separate tool per mode, because
        // everything above and below it — tip, hardness, flow, spacing, smoothing, pressure — is
        // identical for all seven and duplicating this panel six times would be the actual mistake.
        SheetSection("کار قلم")
        SheetChips {
            for (mode in BrushMode.entries) {
                SheetChip(mode.persianLabel, chosen = preset.mode == mode) {
                    model.paint.preset = preset.copy(mode = mode)
                }
            }
        }
        if (preset.mode == BrushMode.DODGE || preset.mode == BrushMode.BURN) {
            SheetChips {
                for (range in ToneRange.entries) {
                    SheetChip(range.persianLabel, chosen = preset.toneRange == range) {
                        model.paint.preset = preset.copy(toneRange = range)
                    }
                }
                SheetChip("حفظ رنگ", chosen = preset.protectTones) {
                    model.paint.preset = preset.copy(protectTones = !preset.protectTones)
                }
            }
            // The one control people do not know they need until the result is wrong.
            SheetHint("محدوده تعیین می‌کند کدام تن‌ها حرکت کنند — سایه، میانی یا روشنایی")
        }
        if (preset.mode == BrushMode.SPONGE) {
            SheetHint("رنگ سفید اشباع را بالا می‌برد و رنگ مشکی پایین — همان قراردادِ خودِ فتوشاپ")
        }
        if (!preset.mode.paintsColour) {
            SheetHint("این قلم‌ها پیکسل زیر خودشان را می‌خوانند — «جریان» شدتشان است")
        }

        BrushSlider("اندازه", preset.size, 1f..400f, ParameterSpec.Slider.Unit.PIXELS) {
            model.paint.preset = preset.copy(size = it)
        }
        TipPicker(model)

        val hardness = (preset.tip as? BrushTip.Round)?.hardness
        if (hardness != null) {
            BrushSlider("سختی", hardness, 0f..1f, ParameterSpec.Slider.Unit.PERCENT) {
                model.paint.preset = preset.copy(tip = BrushTip.Round(it.coerceIn(0f, 1f)))
            }
        }
        BrushSlider("جریان", preset.flow, 0.01f..1f, ParameterSpec.Slider.Unit.PERCENT) {
            model.paint.preset = preset.copy(flow = it.coerceIn(0.01f, 1f))
        }
        BrushSlider("شفافیت", preset.opacity, 0.01f..1f, ParameterSpec.Slider.Unit.PERCENT) {
            model.paint.preset = preset.copy(opacity = it.coerceIn(0.01f, 1f))
        }
        BrushSlider("فاصله", preset.spacing, 0.02f..2f, ParameterSpec.Slider.Unit.PERCENT) {
            model.paint.preset = preset.copy(spacing = it.coerceAtLeast(0.02f))
        }
        BrushSlider("نرمی حرکت", preset.smoothing, 0f..0.95f, ParameterSpec.Slider.Unit.PERCENT) {
            model.paint.preset = preset.copy(smoothing = it.coerceIn(0f, 0.95f))
        }

        if (preset.clone) {
            CloneSource(model)
        } else if (!preset.erase && (preset.mode.paintsColour || preset.mode == BrushMode.SPONGE)) {
            PalettePair(model)
            ColorRow(model.palette[model.editingSlot]) { model.setPaletteColor(it) }
            Eyedropper(model)
        }
    }
}

/**
 * Foreground and background, with swap and reset.
 *
 * The pair the whole toolbar reads from, and its absence was quietly shaping this interface: every
 * sheet that needed a colour carried its own, so a colour chosen in one place could not be reached
 * from another — which is *why* those sheets grew fixed swatches.
 *
 * Swap and reset are the ergonomics, not decoration. Photoshop's X and D are pressed constantly,
 * because masking is a continuous alternation between adding and removing and with one colour that
 * means opening a picker every few seconds.
 */
@Composable
private fun PalettePair(model: EditorViewModel) {
    val palette = model.palette
    SheetChips {
        for (slot in ir.pixellab.core.editor.Palette.Slot.entries) {
            SheetChip(
                slot.persianLabel,
                chosen = model.editingSlot == slot,
                tint = palette[slot].toCompose(),
            ) { model.editingSlot = slot }
        }
        SheetChip("جابه‌جایی") { model.swapPalette() }
        SheetChip("پیش‌فرض") { model.resetPalette() }
    }
    SheetHint("قلم‌مو با پیش‌زمینه می‌کشد و پاک‌کن پس‌زمینه را برمی‌گرداند — در ماسک سریع، سفید اضافه می‌کند و مشکی کم")
}

private fun ir.pixellab.core.model.Color.toCompose() =
    androidx.compose.ui.graphics.Color(r, g, b, 1f)

/**
 * The eyedropper.
 *
 * Arms the next canvas tap, for the same reason the clone stamp's source does: a touch screen has
 * no modifier key, and a long press is already the gesture that reaches a buried layer. It samples
 * the **selected image layer**, not the composited screen — reading the composite means a round
 * trip to the GL thread for one pixel, and it would also pick up the chequerboard behind a
 * transparent area, which is not a colour that exists in the user's document.
 */
@Composable
private fun Eyedropper(model: EditorViewModel) {
    val onPixels = model.state.primaryLayer is ir.pixellab.core.model.Layer.Image
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SheetChip(
                "قطره‌چکان",
                chosen = model.armingEyedropper,
                enabled = onPixels,
            ) {
                model.armingEyedropper = !model.armingEyedropper
            }
        }
        Text(
            when {
                !onPixels -> "یک لایهٔ تصویر انتخاب کنید تا بشود از آن رنگ برداشت"
                model.armingEyedropper -> "حالا روی بوم، جایی که رنگش را می‌خواهید بزنید"
                else -> "رنگ را از پیکسل‌های لایهٔ انتخاب‌شده برمی‌دارد، نه از تصویر روی صفحه"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (model.armingEyedropper) Ink.Accent else Ink.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Where the clone stamp copies from.
 *
 * Photoshop sets the source with alt-click, which a touch screen has no equivalent of. A button that
 * arms the *next* tap is the honest substitute: the user is told what the next thing they do will
 * mean, rather than discovering it by painting something they did not intend.
 */
@Composable
private fun CloneSource(model: EditorViewModel) {
    val source = model.paint.cloneSource
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            when {
                model.paint.armingCloneSource -> "حالا روی بوم، جایی که می‌خواهید از آن کپی شود را بزنید"
                source == null -> "هنوز مبدأیی انتخاب نشده"
                else -> "مبدأ: ${source.x.toInt()}، ${source.y.toInt()}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (model.paint.armingCloneSource) Ink.Accent else Ink.Text,
        )
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SheetChip(
                "انتخاب مبدأ",
                chosen = model.paint.armingCloneSource,
            ) {
                model.paint.armingCloneSource = !model.paint.armingCloneSource
            }
            SheetChip("پاک‌کردن مبدأ", enabled = source != null) { model.paint.setCloneSource(null) }
        }
        Text(
            // The behaviour people rely on and the reason the offset is not re-taken every stroke.
            "فاصلهٔ انگشت تا مبدأ ثابت می‌ماند، پس چند پاس پشت سر هم با هم جور درمی‌آید",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun PresetRow(current: BrushPreset, onPick: (BrushPreset) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (preset in BrushPreset.ALL) {
            val chosen = preset.name == current.name
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (chosen) Ink.Accent.copy(alpha = 0.18f) else Ink.Chrome)
                    .clickable {
                        // The colour follows the user across presets. Resetting it to the preset's
                        // own black every time is the fastest way to make a brush picker annoying.
                        onPick(preset.copy(color = current.color, erase = preset.erase))
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (chosen) Ink.Accent else Ink.Text,
                )
            }
        }
    }
}

/**
 * The palette.
 *
 * A fixed row rather than a wheel, because a wheel needs a picker of its own and this is the sheet
 * people open mid-stroke. The full picker belongs with the adjustment work.
 */
@Composable
private fun ColorRow(current: Color, onPick: (Color) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("رنگ", style = MaterialTheme.typography.labelMedium, color = Ink.TextMuted)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (color in PALETTE) {
                val chosen = color == current
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(UiColor(color.r, color.g, color.b, color.a))
                        .border(
                            width = if (chosen) 2.dp else 1.dp,
                            color = if (chosen) Ink.Accent else Ink.Divider,
                            shape = CircleShape,
                        )
                        .clickable { onPick(color) },
                )
            }
        }
    }
}

@Composable
private fun BrushSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: ParameterSpec.Slider.Unit,
    onChange: (Float) -> Unit,
) {
    PrecisionSlider(
        spec = ParameterSpec.Slider(
            key = label,
            label = label,
            range = range,
            default = value,
            unit = unit,
            // Size needs fine control at the small end, where a few pixels change the mark
            // completely, and coarse control at the large end where they do not.
            skew = if (unit == ParameterSpec.Slider.Unit.PIXELS) SIZE_SKEW else 1f,
        ),
        value = value,
        onChange = { next, _ -> onChange(next) },
        onCommit = {},
    )
}

/** Black, white and the colours a cover actually uses; a wheel belongs with the colour picker. */
private val PALETTE = listOf(
    Color.BLACK,
    Color.WHITE,
    Color(0.85f, 0.15f, 0.2f),
    Color(0.95f, 0.6f, 0.1f),
    Color(0.95f, 0.85f, 0.2f),
    Color(0.2f, 0.7f, 0.35f),
    Color(0.2f, 0.5f, 0.9f),
    Color(0.55f, 0.25f, 0.8f),
)

private const val SIZE_SKEW = 0.5f
