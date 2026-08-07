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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.pixellab.core.canvas.SafeZone
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Guide

/**
 * The grid, the rulers, the guides and the platform safe zones.
 *
 * These four are the difference between placing things by eye and placing them. On a phone that
 * gap is wider than on a desktop: the artwork is small, the finger is large, and nudging a title
 * into the same margin twice is genuinely hard without something to catch it.
 *
 * The grid and the rulers are working preferences and are not saved with the file. The guides are,
 * because a guide records a decision about the design — where the margin is, where the fold falls —
 * and losing it on save means measuring it again every time.
 */
@Composable
fun GuideSheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    val grid = state.grid
    val guides = state.document.guides
    var columns by remember { mutableStateOf("3") }
    var rows by remember { mutableStateOf("3") }
    var margin by remember { mutableStateOf("0") }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SheetSection("شبکه")
        SheetChips {
            SheetChip("نمایش شبکه", chosen = grid.visible) {
                model.act { setGrid(grid.copy(visible = !grid.visible)) }
            }
            SheetChip("چفت‌شدن به شبکه", chosen = grid.snap) {
                model.act { setGrid(grid.copy(snap = !grid.snap)) }
            }
        }
        SheetSlider(
            label = "فاصلهٔ خطوط اصلی",
            value = grid.spacing,
            range = MIN_SPACING..MAX_SPACING,
            onChange = { value, _ ->
                model.act { setGrid(grid.copy(spacing = value.coerceAtLeast(MIN_SPACING))) }
            },
        )
        SheetSlider(
            label = "تقسیمات",
            value = grid.subdivisions.toFloat(),
            range = 1f..MAX_SUBDIVISIONS,
            onChange = { value, _ ->
                model.act { setGrid(grid.copy(subdivisions = value.toInt().coerceAtLeast(1))) }
            },
        )
        SheetHint("خطوط اصلی پررنگ‌اند و تقسیمات کم‌رنگ — شبکه‌ای که یک‌دست باشد یا خوانا نیست یا به درد چیدن نمی‌خورد")

        SheetSection("خط‌کش")
        SheetChips {
            SheetChip("نمایش خط‌کش", chosen = state.showRulers) {
                model.act { setRulersVisible(!state.showRulers) }
            }
        }
        SheetHint("راهنما را از روی خط‌کش به داخل بوم بکشید؛ برای حذف، دوباره روی خط‌کش رهایش کنید")

        SheetSection("راهنماها — ${guides.size} تا")
        SheetChips {
            SheetChip("افقی در وسط") {
                model.act { addGuide(Guide(vertical = false, position = state.document.canvas.height / 2f)) }
            }
            SheetChip("عمودی در وسط") {
                model.act { addGuide(Guide(vertical = true, position = state.document.canvas.width / 2f)) }
            }
            SheetChip(
                if (guides.any { it.locked }) "بازکردن قفل همه" else "قفل کردن همه",
                chosen = guides.isNotEmpty() && guides.all { it.locked },
                enabled = guides.isNotEmpty(),
            ) {
                model.act { setGuidesLocked(!guides.all { it.locked }) }
            }
            SheetChip(
                "پاک کردن",
                enabled = guides.any { !it.locked },
                tint = Ink.Danger,
            ) {
                model.act { clearGuides() }
            }
        }

        SheetSection("چیدمان راهنما")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetNumberField("ستون", columns, modifier = Modifier.weight(1f)) { columns = count(it) }
            SheetNumberField("ردیف", rows, modifier = Modifier.weight(1f)) { rows = count(it) }
            SheetNumberField("حاشیه", margin, modifier = Modifier.weight(1f)) { margin = count(it) }
        }
        SheetAction("ساختن چیدمان") {
            model.act {
                guideLayout(
                    columns = columns.toIntOrNull() ?: 1,
                    rows = rows.toIntOrNull() ?: 1,
                    margin = margin.toFloatOrNull() ?: 0f,
                )
            }
        }
        // Said plainly because running it twice with different numbers is the normal way it is used.
        SheetHint("هر بار جایگزین چیدمان قبلی می‌شود، نه اضافه بر آن — راهنماهای قفل‌شده می‌مانند")

        SheetSection("ناحیهٔ امن")
        SheetChips {
            SheetChip("بدون", chosen = state.safeZone == null) { model.act { setSafeZone(null) } }
            for (zone in SafeZone.ALL) {
                SheetChip(zone.name, chosen = state.safeZone == zone) { model.act { setSafeZone(zone) } }
            }
        }
        SheetHint("بیرونِ ناحیه تیره می‌شود — جایی که پلتفرم رویش نوار و دکمه می‌گذارد")
    }
}

/** A small count: three digits is more columns than any layout has and more margin than any canvas. */
private fun count(entry: String) = entry.filter { it.isDigit() }.take(4)

private const val MIN_SPACING = 4f
private const val MAX_SPACING = 500f
private const val MAX_SUBDIVISIONS = 10f
