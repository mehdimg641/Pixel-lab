package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Layer
import ir.pixellab.core.render.ParameterSpec
import kotlinx.coroutines.launch

/**
 * The photo sheet: cutting out, retouching and warping.
 *
 * All three act on the pixels of the selected image layer, and all three are slow enough to be felt
 * — a matte solve over a portrait is hundreds of milliseconds. Every one runs off the main thread
 * with the control disabled while it does, because a sheet that silently ignores a second press is
 * how a user ends up applying the same warp four times.
 *
 * The order on screen is the order the work is actually done in: choose the subject, refine the
 * edge, then fix what is inside it.
 */
@Composable
fun RetouchSheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var smoothing by remember { mutableStateOf(0.6f) }
    var sharpening by remember { mutableStateOf(0.5f) }
    var warpAmount by remember { mutableStateOf(0.25f) }
    val layer = state.primaryLayer as? Layer.Image

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (layer == null) {
            MissingSubject(
                message = "یک لایهٔ تصویر انتخاب کنید",
                action = "افزودن عکس",
                onAct = LocalEditorActions.current.pickImage,
            )
            return@Column
        }

        Section("جداسازی سوژه")
        Text(
            if (model.select.selection == null) {
                "اول با عصای جادویی یا کمند سوژه را انتخاب کنید، بعد لبه را پالایش کنید"
            } else {
                "انتخاب آماده است — پالایش لبه، مو را از پس‌زمینه جدا می‌کند"
            },
            style = MaterialTheme.typography.labelSmall,
            color = Ink.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Action("پالایش لبه و جداسازی", enabled = !busy && model.select.selection != null) {
            busy = true
            scope.launch {
                model.refineCutout()
                busy = false
            }
        }

        Section("ترمیم")
        Slider("نرمی پوست", smoothing, 0f..1f) { smoothing = it }
        Action("اعمال نرمی", enabled = !busy) {
            busy = true
            scope.launch {
                model.smoothSkin(smoothing)
                busy = false
            }
        }
        Action("محو لکه در انتخاب", enabled = !busy && model.select.selection != null) {
            busy = true
            scope.launch {
                model.healSelection()
                busy = false
            }
        }
        Slider("تیزی", sharpening, 0f..3f) { sharpening = it }
        Action("اعمال تیزی", enabled = !busy) {
            busy = true
            scope.launch {
                model.sharpen(sharpening)
                busy = false
            }
        }

        Section("لیکوییفای")
        Text(
            "روی بوم بکشید تا پیکسل‌ها را هل دهید؛ حالت صُلب چرخش می‌دهد ولی کشش نه، که تنها حالتی است " +
                "که چهره را قابل تشخیص نگه می‌دارد",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Slider("اندازهٔ قلم", model.warp.brushSize, 10f..300f) { model.warp.brushSize = it }
        Slider("مقدار", warpAmount, -0.8f..0.8f) { warpAmount = it }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (kind in WarpKind.entries) {
                Chip(labelOf(kind), chosen = model.warp.kind == kind) { model.warp.kind = kind }
            }
        }
        Action("اعمال تغییر شکل", enabled = !busy && !model.warp.isEmpty) {
            busy = true
            scope.launch {
                model.applyWarp(warpAmount)
                busy = false
            }
        }
        Action("لغو تغییر شکل", enabled = !model.warp.isEmpty) { model.warp.reset() }

        if (busy) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(color = Ink.Accent)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = Ink.TextMuted,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun Action(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) Ink.Accent else Ink.Divider,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.Chrome)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
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
private fun Chip(label: String, chosen: Boolean, onClick: () -> Unit) =
    SheetChip(label, chosen = chosen, onClick = onClick)

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

private fun labelOf(kind: WarpKind) = when (kind) {
    WarpKind.PUSH -> "هل دادن"
    WarpKind.BLOAT -> "باد کردن"
    WarpKind.PUCKER -> "جمع کردن"
    WarpKind.TWIRL -> "پیچاندن"
}
