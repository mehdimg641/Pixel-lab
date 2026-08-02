package ir.pixellab.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.Editor
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.editor.SheetDetent
import ir.pixellab.core.editor.Tool
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import kotlinx.coroutines.launch

/**
 * The editor screen.
 *
 * Three layers of control, and the layout rule behind them is a physical one: the device is 6.7
 * inches, so a thumb cannot reach the top. Everything frequent lives in the bottom third; the top
 * bar holds only what is rare and expensive to hit by accident.
 */
@Composable
fun EditorScreen(model: EditorViewModel) {
    val state = model.state
    val bounds = model.bounds
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handle = remember { CanvasHandle() }
    var outcome by remember { mutableStateOf<FileOutcome?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<List<java.io.File>?>(null) }
    var editingText by remember { mutableStateOf<LayerId?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink.Chrome)) {
        val screenHeight = maxHeight

        EditorCanvas(
            state = state,
            bounds = bounds,
            fonts = model.fonts,
            assets = model.assets,
            assetGeneration = model.paint.generation,
            selection = SelectionOverlay(model.select.outline, model.select.draft),
            handle = handle,
            onGesture = model::onGesture,
            onSize = model::onScreenSize,
        )

        Column(Modifier.align(Alignment.TopCenter)) {
            TopBar(
                state = state,
                model = model,
                onSave = {
                    scope.launch { outcome = saveProject(context, model.currentProject()) }
                },
                onExport = { exporting = true },
                onOpen = { scope.launch { opening = Storage.listProjects(context) } },
            )
            outcome?.let { OutcomeBanner(it, Modifier.padding(top = 6.dp)) { outcome = null } }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = state.hasSelection && !state.sheet.isOpen,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                ContextualBar(state, model, onEditText = { editingText = it })
            }
            Toolbar(state, model)
        }

        opening?.let { projects ->
            OpenDialog(projects, onDismiss = { opening = null }) { file ->
                opening = null
                scope.launch {
                    openProject(file)
                        .onSuccess { model.openProject(it) }
                        .onFailure { outcome = FileOutcome.Refused(it.message ?: "باز نشد") }
                }
            }
        }

        editingText?.let { id ->
            val layer = state.selectedLayers.firstOrNull { it.id == id } as? Layer.Text
            if (layer == null) {
                editingText = null
            } else {
                TextEditDialog(layer.spec.text, onDismiss = { editingText = null }) { updated ->
                    editingText = null
                    model.setText(id, updated)
                }
            }
        }

        if (exporting) {
            ExportDialog(onDismiss = { exporting = false }) { format, scale ->
                exporting = false
                scope.launch { outcome = exportImage(context, handle, state.document, format, scale) }
            }
        }

        // The sheet sits above everything, and the canvas has already panned out from under it.
        AnimatedVisibility(
            visible = state.sheet.isOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(screenHeight * state.sheet.detent.screenFraction)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(Ink.ChromeRaised),
            ) {
                Column(Modifier.fillMaxSize()) {
                    SheetHeader(state, model)
                    SheetDivider()
                    when (val content = state.sheet.content) {
                        is SheetContent.EffectParameters ->
                            ParameterSheetBody(state, content, model::act, Modifier.fillMaxHeight())
                        is SheetContent.LayerList -> LayerPanel(state, model)
                        is SheetContent.FontPicker -> FontPickerBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.BrushSettings -> BrushSheetBody(model, Modifier.fillMaxHeight())
                        is SheetContent.PixelSelection -> SelectionSheetBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.Adjustments -> AdjustmentSheetBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.Retouch -> RetouchSheetBody(state, model, Modifier.fillMaxHeight())
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("به‌زودی", color = Ink.TextMuted)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The top bar.
 *
 * Deliberately sparse and deliberately out of thumb reach: these are the actions whose accidental
 * press is expensive. Undo and redo are here as a discoverable fallback — the fast path is the
 * two- and three-finger tap on the canvas.
 */
@Composable
private fun TopBar(
    state: EditorState,
    model: EditorViewModel,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Ink.Chrome.copy(alpha = 0.92f))
            .systemBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row {
            // Through the view model, not the editor: painted pixels are on the same stack, and
            // an undo that only knew about the document would skip every stroke.
            BarButton(Icons.AutoMirrored.Filled.Undo, "برگشت", enabled = model.canUndo, onClick = model::undo)
            BarButton(Icons.AutoMirrored.Filled.Redo, "جلو", enabled = model.canRedo, onClick = model::redo)
        }
        Text(
            state.document.name,
            style = MaterialTheme.typography.labelLarge,
            color = Ink.TextMuted,
        )
        Row {
            BarButton(Icons.Filled.FitScreen, "اندازهٔ صفحه") { model.act { fitCanvas() } }
            BarButton(Icons.Filled.FolderOpen, "باز کردن", onClick = onOpen)
            BarButton(Icons.Filled.Save, "ذخیره", onClick = onSave)
            BarButton(Icons.Filled.IosShare, "خروجی", onClick = onExport)
        }
    }
}

/**
 * The contextual bar: what to do with what is selected.
 *
 * The five actions match the muscle memory of the app the user already works in. Familiarity is
 * worth keeping even where a different arrangement might read better on paper.
 */
@Composable
private fun ContextualBar(state: EditorState, model: EditorViewModel, onEditText: (LayerId) -> Unit) {
    val id = state.selection.primary ?: return
    val isText = state.selectedLayers.any { it.id == id && it is Layer.Text }
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.ChromeRaised)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isText) {
            BarButton(Icons.Filled.TextFields, "متن") { onEditText(id) }
        }
        BarButton(Icons.Filled.Star, "استایل") {
            model.act { openSheet(SheetContent.LayerParameters(id), SheetDetent.HALF) }
        }
        BarButton(Icons.Filled.Add, "افکت") {
            // A stroke is the effect people reach for first, and it is immediately visible, so the
            // sheet that opens has something to show.
            model.act { addEffect(id, Effect.Stroke(8f, Fill.Solid(Color.WHITE))) }
        }
        BarButton(Icons.Filled.ContentCopy, "کپی") {
            // The id comes from the document rather than from a count: a count collides the first
            // time a layer is deleted, and two layers with one id is an editor that loses work.
            model.act { duplicateLayer(id, nextLayerId(id.value)) }
        }
        BarButton(Icons.Filled.VerticalAlignTop, "به جلو") { model.act { bringToFront(id) } }
        BarButton(Icons.Filled.Delete, "حذف", tint = Ink.Danger) { model.act { deleteLayer(id) } }
    }
}

/** The persistent toolbar. Five entries, always inside the thumb's arc. */
@Composable
private fun Toolbar(state: EditorState, model: EditorViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink.ChromeRaised)
            .systemBarsPadding()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(Tool.LAYERS, Icons.Filled.Layers, "لایه‌ها", state, model) {
            model.act { openSheet(SheetContent.LayerList, SheetDetent.HALF) }
        }
        ToolButton(Tool.TEXT, Icons.Filled.TextFields, "متن", state, model) {
            model.act { openSheet(SheetContent.FontPicker, SheetDetent.FULL) }
        }
        ToolButton(Tool.BRUSH, Icons.Filled.Brush, "قلم‌مو", state, model) {
            // A brush needs somewhere to paint. Creating the layer on the first press rather than
            // asking for one is the difference between a tool that works and a tool that reports
            // that it cannot.
            if (state.primaryLayer !is Layer.Image) model.addPaintLayer()
            model.act { openSheet(SheetContent.BrushSettings, SheetDetent.HALF) }
        }
        ToolButton(Tool.RETOUCH, Icons.Filled.AutoFixHigh, "ترمیم", state, model) {
            model.act { openSheet(SheetContent.Retouch, SheetDetent.FULL) }
        }
        ToolButton(Tool.IMAGE, Icons.Filled.Image, "تصویر", state, model) {}
        ToolButton(Tool.SHAPE, Icons.Filled.Star, "شکل", state, model) {}
        ToolButton(Tool.SELECT, Icons.Filled.Highlight, "انتخاب", state, model) {
            model.act { openSheet(SheetContent.PixelSelection, SheetDetent.PEEK) }
        }
        ToolButton(Tool.ADJUST, Icons.Filled.Tune, "تنظیم", state, model) {
            model.act { openSheet(SheetContent.Adjustments, SheetDetent.FULL) }
        }
    }
}

@Composable
private fun SheetHeader(state: EditorState, model: EditorViewModel) {
    Column {
        SheetGrip(
            Modifier.clickable {
                // Tapping the grip walks the detents, so the sheet can be grown without a drag.
                model.act {
                    setSheetDetent(
                        when (state.sheet.detent) {
                            SheetDetent.PEEK -> SheetDetent.HALF
                            SheetDetent.HALF -> SheetDetent.FULL
                            else -> SheetDetent.PEEK
                        },
                    )
                }
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                "بستن",
                style = MaterialTheme.typography.labelLarge,
                color = Ink.TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { model.act { closeSheet() } }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ToolButton(
    tool: Tool,
    icon: ImageVector,
    label: String,
    state: EditorState,
    model: EditorViewModel,
    onOpen: () -> Unit,
) {
    val active = state.tool == tool
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                model.act { setTool(tool) }
                onOpen()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (active) Ink.Accent else Ink.TextMuted)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) Ink.Accent else Ink.TextMuted,
        )
    }
}

@Composable
private fun BarButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = Ink.Text,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            // 48dp of target for a 20dp icon; the platform minimum is not a suggestion on a canvas
            // where a mis-tap costs an undo.
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (enabled) tint else Ink.Divider)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) Ink.TextMuted else Ink.Divider,
        )
    }
}
