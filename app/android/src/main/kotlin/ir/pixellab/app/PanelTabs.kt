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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.editor.SheetDetent
import ir.pixellab.core.model.Layer

/**
 * The five tabs along the bottom of the work panel.
 *
 * ### Why these five and not the five that were there
 *
 * The dock used to name five *kinds of work* — «عکس · متن · سه‌بعدی · لایه · خروجی» — and each one
 * swapped a contextual ribbon of tools above it. That is a defensible design and it is not the one
 * in the brief. The brief's panel names five **places to look**, always the same five, each holding
 * a body rather than a row of buttons that open other things. The difference is not cosmetic: a
 * ribbon is a menu that changes under you, and a tabbed panel is a place you learn.
 *
 * Fixed at five and in this order, for the reason the dock was: this is the one part of the
 * interface somebody builds muscle memory for, so it may not reorder itself, grow with context, or
 * hide an entry that happens to be unavailable.
 *
 * «خروجی» is not among them — in the brief it is an accent button in the header, where a thing you
 * do *once, at the end* belongs, rather than a permanent fifth of the panel.
 */
enum class PanelTab(val persianLabel: String, val icon: ImageVector) {
    LAYERS("لایه‌ها", Icons.Outlined.Layers),
    ADJUST("تنظیم", Icons.Outlined.Tune),
    FILTERS("فیلترها", Icons.Outlined.FilterVintage),
    AI("هوش مصنوعی", Icons.Outlined.AutoAwesome),
    TEXT("متن", Icons.Outlined.TextFields),
}

/**
 * The tab row itself: five entries, equal width, along the bottom of the panel.
 *
 * Equal width rather than scrolling. Five Persian words fit across a 411dp phone at 82dp each, and
 * a tab strip that scrolls is one where the fifth tab does not exist until you discover it.
 */
@Composable
fun PanelTabRow(current: PanelTab, onPick: (PanelTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Frame.dock)
            .background(Ink.Chrome),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in PanelTab.entries) {
            val chosen = tab == current
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = Space.touch)
                    .clickable(onClickLabel = tab.persianLabel) { onPick(tab) }
                    .semantics {
                        contentDescription = tab.persianLabel
                        role = Role.Tab
                        selected = chosen
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    tint = if (chosen) Ink.Accent else Ink.TextMuted,
                    modifier = Modifier.size(Frame.icon),
                )
                Text(
                    tab.persianLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (chosen) Ink.Accent else Ink.TextMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(top = Space.tight),
                )
            }
        }
    }
}

/**
 * What each tab shows.
 *
 * Every body here is one this application already had — they were sheets, opened from a ribbon
 * button, one at a time, over the canvas. Nothing about them is rewritten; only where they live.
 * That is the whole point of the change: the panels were never the problem.
 */
@Composable
fun PanelBody(
    tab: PanelTab,
    state: EditorState,
    model: EditorViewModel,
    onOpenTextStudio: (ir.pixellab.core.model.LayerId) -> Unit,
    onOpenRetouch: () -> Unit,
    onOpenBrush: () -> Unit,
    onImportPreset: () -> Unit,
    onImportLut: () -> Unit,
    render: suspend (ir.pixellab.core.model.Document) -> ir.pixellab.core.codec.RasterImage?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        when (tab) {
            PanelTab.LAYERS -> LayersTab(state, model)
            PanelTab.ADJUST -> AdjustmentSheetBody(
                state = state,
                model = model,
                onImportPreset = onImportPreset,
                onImportLut = onImportLut,
                render = render,
            )
            PanelTab.FILTERS -> LibrarySheetBody(state, model)
            PanelTab.AI -> AiTab(state, model, onOpenRetouch, onOpenBrush)
            PanelTab.TEXT -> TextTab(state, model, onOpenTextStudio)
        }
    }
}

// ---- لایه‌ها ------------------------------------------------------------------------------------

/**
 * The stack, with the four order buttons and the merge verbs the brief puts above it.
 *
 * `LayerPanel` already draws the rows, the visibility toggles and the structure bar; what the brief
 * adds is the row of merge operations, which used to be reachable only from a sheet nobody could
 * find. They are chips rather than a menu because the brief's panel has no menus — and because a
 * merge is a thing you do to what is already selected, which is what a chip means here.
 */
@Composable
private fun LayersTab(state: EditorState, model: EditorViewModel) {
    // One scroller for the whole tab, which it could not have while `LayerPanel` ended in a
    // `LazyColumn` — a lazy list inside a scrolling column is measured with an infinite height and
    // Compose throws. The panel is 208dp in Console, and the fixed part above the stack is taller
    // than that, so "only the stack scrolls" meant the stack was clipped to nothing. It is a plain
    // column now and everything here scrolls together.
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter, vertical = Space.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "لایه‌ها · ${Digits.technical(state.document.layers.size)}",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.TextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
                val id = state.selection.primary
                PanelIcon(Icons.Outlined.KeyboardArrowUp, "بالا", enabled = id != null) {
                    id?.let { model.act { raiseLayer(it) } }
                }
                PanelIcon(Icons.Outlined.KeyboardArrowDown, "پایین", enabled = id != null) {
                    id?.let { model.act { lowerLayer(it) } }
                }
                PanelIcon(Icons.Outlined.Add, "افزودن") {
                    model.act { openSheet(SheetContent.CanvasTools, SheetDetent.HALF) }
                }
                PanelIcon(Icons.Outlined.Delete, "حذف", enabled = id != null, tint = Ink.Danger) {
                    id?.let { model.act { deleteLayer(it) } }
                }
            }
        }

        // **The stack, then the verbs.** This had it the other way round — four rows of buttons and
        // bars above the first layer — and in a 208dp Console panel that meant a tab called
        // «لایه‌ها» in which no layer was visible without scrolling. Photoshop has put the list at
        // the top of this panel and the verbs along its foot since 3.0, and the reason is the one
        // above: the panel is named after the list, so the list is what it opens on.
        LayerStack(state, model)

        LayerStructureBar(state, model)
        LayerOrderBar(state, model)

        // «چیدمان» and «راهنما» ride here rather than in a ribbon: both act on the stack, and the
        // brief has exactly one place for things that act on the stack.
        SheetChips {
            SheetChip("چیدمان") { model.act { openSheet(SheetContent.Arrange, SheetDetent.HALF) } }
            SheetChip("راهنما") { model.act { openSheet(SheetContent.Guides, SheetDetent.HALF) } }
            SheetChip("بوم") { model.act { openSheet(SheetContent.CanvasTools, SheetDetent.HALF) } }
        }
    }
}

/** A square icon button sized to the touch minimum. The panel header's vocabulary. */
@Composable
private fun PanelIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = Ink.Text,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(Space.touch)
            .clip(Corners.button)
            .background(Ink.ChromeRaised)
            .clickable(enabled = enabled, onClick = onClick, onClickLabel = label)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) tint else Ink.TextDisabled,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ---- هوش مصنوعی ---------------------------------------------------------------------------------

/**
 * The four on-device operations, as the brief's four-column grid.
 *
 * The hint underneath is not decoration. Every one of these runs on the phone — `U²Net` for the
 * subject, the classical path when no model is installed, MediaPipe for the face — and a user
 * handing a photograph to something called «هوش مصنوعی» is entitled to know before they tap
 * whether it leaves the device. It does not.
 */
@Composable
private fun AiTab(
    state: EditorState,
    model: EditorViewModel,
    onOpenRetouch: () -> Unit,
    onOpenBrush: () -> Unit,
) {
    val actions = LocalEditorActions.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.small),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ابزار هوشمند", style = MaterialTheme.typography.labelLarge, color = Ink.Text)
            Text("روی دستگاه", style = MaterialTheme.typography.labelSmall, color = Ink.TextMuted)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
        ) {
            AiTile(Icons.Outlined.Image, "جدا کردن سوژه", Modifier.weight(1f)) {
                model.act { openSheet(SheetContent.PixelSelection, SheetDetent.HALF) }
            }
            // Both open the retouch studio, on its two halves. They are separate tiles because
            // they answer separate questions — «this photograph has a face in it» and «this one
            // does not» — and the studio's own tabs are where that split lives.
            AiTile(Icons.Outlined.Face, "پرتره", Modifier.weight(1f), onClick = onOpenRetouch)
            AiTile(Icons.Outlined.AutoFixHigh, "ترمیم", Modifier.weight(1f), onClick = onOpenRetouch)
            AiTile(Icons.Outlined.ZoomOutMap, "افزودن عکس", Modifier.weight(1f), onClick = actions.pickImage)
        }
        // The brush is not an AI tool and does not belong in that grid. It is here because this is
        // the tab a hand is already in when it wants one, and because the studio it opens had no
        // entry point at all outside Console's rail.
        SheetChips {
            SheetChip("استودیو قلم") {
                if (state.primaryLayer !is Layer.Image) model.addPaintLayer()
                onOpenBrush()
            }
        }
        SheetHint("همه روی همین گوشی اجرا می‌شود — هیچ عکسی جایی فرستاده نمی‌شود")
        if (!state.hasSelection) SheetHint("برای بیشترشان اول یک لایه انتخاب کنید")
    }
}

/** One square in the four-column grid: an icon over a Persian word. */
@Composable
private fun AiTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .heightIn(min = 72.dp)
            .clip(Corners.card)
            .background(Ink.ChromeRaised)
            .border(1.dp, Ink.Divider, Corners.card)
            .clickable(onClick = onClick, onClickLabel = label)
            .padding(vertical = Space.small, horizontal = Space.tight)
            .semantics { role = Role.Button },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        Icon(icon, contentDescription = null, tint = Ink.Text, modifier = Modifier.size(22.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Ink.TextMuted,
            maxLines = 2,
        )
    }
}

// ---- متن ----------------------------------------------------------------------------------------

/**
 * The typeface row and the way into the studio.
 *
 * The brief's text tab is a font strip, a size slider and a preview — a place to make a quick
 * change without leaving the canvas. Everything deeper is a *screen*, and that split is the reason
 * the studio can afford fourteen sections: it is not competing with the canvas for room.
 */
@Composable
private fun TextTab(
    state: EditorState,
    model: EditorViewModel,
    onOpenTextStudio: (ir.pixellab.core.model.LayerId) -> Unit,
) {
    val actions = LocalEditorActions.current
    val layer = state.primaryLayer as? Layer.Text
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (layer == null) {
            MissingSubject(
                message = "لایهٔ متنی انتخاب نشده",
                action = "افزودن متن",
                onAct = actions.addText,
            )
            return@Column
        }

        SheetChips {
            SheetChip("استودیو متن", chosen = true) { onOpenTextStudio(layer.id) }
            SheetChip("فونت") { model.act { openSheet(SheetContent.FontPicker, SheetDetent.FULL) } }
            SheetChip("نویسه و بند") { model.act { openSheet(SheetContent.Typography, SheetDetent.FULL) } }
        }

        // The sample is the layer's own words in the layer's own size, which is the only preview
        // worth having — a fixed pangram tells you nothing about the line you are actually setting.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter, vertical = Space.small)
                .clip(Corners.card)
                .background(Ink.ChromeRaised)
                .padding(Space.medium),
        ) {
            Text(
                layer.spec.text.take(PREVIEW_CHARS),
                style = MaterialTheme.typography.headlineSmall,
                color = Ink.Text,
                maxLines = 2,
            )
        }
        SheetHint("اندازه ${Digits.technical(layer.spec.size.toInt())} · ${layer.spec.font.family}")
    }
}

/** Two lines of a headline is enough to judge a face by; more is a paragraph nobody reads here. */
private const val PREVIEW_CHARS = 48

// ---- the strip that carries a scrolling row of chips, shared by three tabs ----------------------

/** A horizontally scrolling row, for the tabs whose content is a list of chips. */
@Composable
internal fun ScrollingRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.small),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Reserved width for a read-out beside a slider, so a row of them lines up. */
internal val ReadoutWidth = 44.dp
