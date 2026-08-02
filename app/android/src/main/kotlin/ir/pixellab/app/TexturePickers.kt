package ir.pixellab.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.imaging.Procedural
import ir.pixellab.core.imaging.Raster
import ir.pixellab.core.model.AssetId
import ir.pixellab.core.paint.BrushTip

/**
 * Choosing a brush tip and choosing a pattern.
 *
 * Both offer generated textures rather than a shipped asset pack, and that is a consequence of the
 * app being offline rather than a compromise. It also buys two things a folder of PNGs cannot: a tip
 * is exact at whatever size it is used, instead of resampled from one fixed resolution, and a
 * pattern is seamless because it was constructed to be, not because someone was careful in a
 * painting program.
 *
 * The swatches are rendered from the same generator the paint engine will use. A preview drawn any
 * other way is a promise the stroke then breaks.
 */
@Composable
fun TipPicker(model: EditorViewModel, modifier: Modifier = Modifier) {
    val preset = model.paint.preset
    val chosen = (preset.tip as? BrushTip.Sampled)?.asset

    Column(modifier.fillMaxWidth()) {
        SheetSection("نوک قلم‌مو")
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The computed round tip stays first and stays distinct from the generated one: it costs
            // nothing to store, it is exact at any size, and it is what most work uses.
            Swatch(
                bitmap = remember { coverageBitmap(Procedural.tip(Procedural.Tip.ROUND, SWATCH)) },
                label = "محاسباتی",
                chosen = preset.tip is BrushTip.Round,
                onClick = { model.paint.preset = preset.copy(tip = BrushTip.Round()) },
            )
            for (kind in Procedural.Tip.entries) {
                val id = AssetId("tip-${kind.name.lowercase()}")
                Swatch(
                    bitmap = remember(kind) { coverageBitmap(Procedural.tip(kind, SWATCH, seed = kind.ordinal)) },
                    label = kind.persianLabel,
                    chosen = chosen == id,
                    onClick = { model.useGeneratedTip(kind) },
                )
            }
        }
        SheetHint("نوک‌ها ساخته می‌شوند، نه از فایل — پس در هر اندازه‌ای دقیق‌اند و جایی اشغال نمی‌کنند")
    }
}

/**
 * The pattern picker.
 *
 * @param onPick receives the asset the tile was registered under, ready for a `Fill.Pattern`.
 */
@Composable
fun PatternPicker(model: EditorViewModel, onPick: (AssetId) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SheetSection("الگو")
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (kind in Procedural.Pattern.entries) {
                Swatch(
                    // Drawn at two repeats so the swatch itself shows the tile *repeating*, which is
                    // the only way a seam would be visible in a preview.
                    bitmap = remember(kind) {
                        coverageBitmap(Procedural.pattern(kind, SWATCH, repeats = SWATCH_REPEATS))
                    },
                    label = kind.persianLabel,
                    chosen = false,
                    onClick = { onPick(model.registerPattern(kind)) },
                )
            }
        }
        SheetHint("هر کاشی بی‌درز است — چون این‌طور ساخته شده، نه چون کسی مراقب بوده")
    }
}

@Composable
private fun Swatch(bitmap: ImageBitmap, label: String, chosen: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap = bitmap,
            contentDescription = label,
            modifier = Modifier
                .size(SWATCH_SIZE.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Ink.ChromeSunken)
                .border(
                    width = if (chosen) 2.dp else 1.dp,
                    color = if (chosen) Ink.Accent else Ink.Divider,
                    shape = RoundedCornerShape(8.dp),
                )
                // The whole swatch is the target, not the picture inside it: the platform's minimum
                // is 48dp and a smaller hit area is one people miss.
                .clickable(onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = if (chosen) "$label، انتخاب‌شده" else label },
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (chosen) Ink.Accent else Ink.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * A coverage map drawn as white on transparent.
 *
 * White rather than the brush's colour, because coverage is not colour — a swatch tinted with the
 * current colour would read as "this is what you will paint" when the tip decides only *how much*
 * of that colour lands.
 */
private fun coverageBitmap(raster: Raster): ImageBitmap {
    val pixels = IntArray(raster.pixelCount) { i ->
        val alpha = (raster.data[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        (alpha shl 24) or 0x00FFFFFF
    }
    return android.graphics.Bitmap
        .createBitmap(pixels, raster.width, raster.height, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/** The same conversion for the asset store, which stores images rather than coverage. */
fun Raster.toCoverageImage(): RasterImage = RasterImage(
    width = width,
    height = height,
    pixels = IntArray(pixelCount) { i ->
        val alpha = (data[i * channels].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        (alpha shl 24) or 0x00FFFFFF
    },
)

@Suppress("unused")
private val unusedTint = UiColor.White

/** Big enough to tell chalk from spatter at arm's length, small enough to fit six in a row. */
private const val SWATCH = 96
private const val SWATCH_SIZE = 64
private const val SWATCH_REPEATS = 2
