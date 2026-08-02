package ir.pixellab.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.AlignHorizontalLeft
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.editor.SheetDetent
import ir.pixellab.core.editor.Tool
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.launch

/**
 * The editor screen.
 *
 * Three layers of control, and the layout rule behind them is a physical one: the device is 6.7
 * inches, so a thumb cannot reach the top. Everything frequent lives in the bottom third; the top
 * bar holds only what is rare and expensive to hit by accident.
 *
 * @param entry what the user pressed on the home screen, or null if they came in another way. It is
 *   consumed once — [onEntryHandled] — so that returning to the editor later does not re-open a
 *   sheet the user has since closed.
 */
@Composable
fun EditorScreen(
    model: EditorViewModel,
    entry: QuickAction? = null,
    onEntryHandled: () -> Unit = {},
    onHome: (() -> Unit)? = null,
) {
    val state = model.state
    val bounds = model.bounds
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handle = remember { CanvasHandle() }
    var outcome by remember { mutableStateOf<FileOutcome?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<List<java.io.File>?>(null) }
    var editingText by remember { mutableStateOf<LayerId?>(null) }

    /**
     * The panel to open once an imported picture has actually landed.
     *
     * "Remove the background" needs a background to remove. Opening the selection panel over an
     * empty canvas and then asking for a photo is the order that makes a quick action feel like a
     * detour; this way the picker comes first and the tool is waiting when the image arrives.
     */
    var afterImport by remember { mutableStateOf<SheetContent?>(null) }

    // Registered once for the screen rather than inside the sheet: a launcher created inside a
    // conditionally composed subtree is unregistered the moment that subtree leaves, and the result
    // then arrives with nowhere to go.
    val picking = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val pending = afterImport
        afterImport = null
        if (uri != null) {
            scope.launch {
                loadImage(context, uri)
                    .onSuccess { (image, name) ->
                        model.placeImage(image, name)
                        if (pending != null) model.act { openSheet(pending, SheetDetent.FULL) }
                    }
                    .onFailure { outcome = FileOutcome.Refused(it.message ?: "تصویر خوانده نشد") }
            }
        }
    }

    // Its own launcher rather than a mode flag on the image one: the two want different MIME
    // filters, and a picker that offers a photo when the user asked for a curve preset is the kind
    // of thing that makes people give up on a feature rather than report it.
    val pickingPreset = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                loadCurvePreset(context, uri)
                    .onSuccess { (curves, name) -> model.addAdjustment(curves, name) }
                    .onFailure { outcome = FileOutcome.Refused(it.message ?: "پریست خوانده نشد") }
            }
        }
    }

    LaunchedEffect(entry) {
        val action = entry ?: return@LaunchedEffect
        when (action) {
            QuickAction.DIMENSIONAL -> model.act {
                setTool(Tool.TEXT)
                openSheet(SheetContent.Dimensional, SheetDetent.FULL)
            }
            QuickAction.TEXT -> model.act {
                setTool(Tool.TEXT)
                openSheet(SheetContent.FontPicker, SheetDetent.FULL)
            }
            QuickAction.PAINT -> {
                if (model.state.primaryLayer !is Layer.Image) model.addPaintLayer()
                model.act {
                    setTool(Tool.BRUSH)
                    openSheet(SheetContent.BrushSettings, SheetDetent.HALF)
                }
            }
            QuickAction.EFFECTS -> model.act {
                setTool(Tool.ADJUST)
                openSheet(SheetContent.Adjustments, SheetDetent.FULL)
            }
            // The three that need a photograph first. The tool is remembered and opens on arrival.
            QuickAction.PHOTO -> {
                afterImport = SheetContent.CanvasTools
                picking.launch(IMAGE_MIME)
            }
            QuickAction.CUTOUT -> {
                afterImport = SheetContent.PixelSelection
                picking.launch(IMAGE_MIME)
            }
            QuickAction.RETOUCH -> {
                afterImport = SheetContent.Retouch
                picking.launch(IMAGE_MIME)
            }
        }
        onEntryHandled()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink.Ground)) {
        val screenHeight = maxHeight

        EditorCanvas(
            state = state,
            bounds = bounds,
            fonts = model.fonts,
            assets = model.assets,
            assetGeneration = model.paint.generation,
            selection = SelectionOverlay(model.select.outline, model.select.draft),
            pen = PenOverlay(model.pen.path, model.pen.active),
            handle = handle,
            onGesture = model::onGesture,
            onSize = model::onScreenSize,
        )

        Rulers(state, model, Modifier.align(Alignment.TopStart))

        Column(Modifier.align(Alignment.TopCenter)) {
            TopBar(
                state = state,
                model = model,
                onHome = onHome,
                onSave = {
                    scope.launch { outcome = saveProject(context, model.currentProject()) }
                },
                onExport = { exporting = true },
                onOpen = { scope.launch { opening = Storage.listProjects(context) } },
            )
            outcome?.let {
                OutcomeBanner(it, Modifier.padding(top = Space.small, start = Space.medium, end = Space.medium)) {
                    outcome = null
                }
            }
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
                if (format == ir.pixellab.core.codec.Format.PDF) {
                    scope.launch {
                        outcome = exportPdf(context, handle, state.document, scale)
                    }
                    return@ExportDialog
                }
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
                    .clip(Corners.sheet)
                    .background(Ink.Chrome),
            ) {
                Column(Modifier.fillMaxSize()) {
                    SheetHeader(state, model)
                    when (val content = state.sheet.content) {
                        is SheetContent.EffectParameters ->
                            ParameterSheetBody(state, content, model, Modifier.fillMaxHeight())
                        is SheetContent.LayerList -> LayerPanel(state, model)
                        is SheetContent.FontPicker -> FontPickerBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.BrushSettings -> BrushSheetBody(model, Modifier.fillMaxHeight())
                        is SheetContent.PixelSelection -> SelectionSheetBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.Adjustments -> AdjustmentSheetBody(
                            state = state,
                            model = model,
                            onImportPreset = { pickingPreset.launch(PRESET_MIME) },
                            modifier = Modifier.fillMaxHeight(),
                        )
                        is SheetContent.Retouch -> RetouchSheetBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.Vector -> VectorSheetBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.LibraryPanel -> LibrarySheetBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.LayerParameters ->
                            LayerParametersSheetBody(state, content, model, Modifier.fillMaxHeight())
                        is SheetContent.CanvasTools -> CanvasSheetBody(
                            state = state,
                            model = model,
                            onPickImage = { picking.launch(IMAGE_MIME) },
                            modifier = Modifier.fillMaxHeight(),
                        )
                        is SheetContent.ShapeTools -> ShapeSheetBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.Guides -> GuideSheetBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.Typography -> TypeSheetBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.Settings -> SettingsSheetBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.Dimensional ->
                            DimensionalSheetBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.Arrange -> ArrangeSheetBody(
                            state = state,
                            model = model,
                            render = { document -> renderDocument(handle, document) },
                            modifier = Modifier.fillMaxHeight(),
                        )
                        SheetContent.StyleLibrary -> LibrarySheetBody(state, model, Modifier.fillMaxHeight())
                        // The sheet is open, so the content is never null; the branch is here
                        // because the type says it could be and a silent `else` would swallow a
                        // future case that genuinely needs a screen.
                        null -> Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/**
 * The top bar.
 *
 * Six icons and a title fitted across a 411dp phone was the single worst thing about the interface
 * this replaces: every one of them ended up too small to hit and too anonymous to read, so the bar
 * looked busy and did nothing well. What is left is what a person presses *while* editing — back,
 * undo, redo, export — and the five rare, expensive actions moved behind the overflow, where hitting
 * one by accident is no longer possible.
 */
@Composable
internal fun TopBar(
    state: EditorState,
    model: EditorViewModel,
    onHome: (() -> Unit)?,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .background(Ink.Chrome.copy(alpha = SCRIM))
            .systemBarsPadding()
            .padding(horizontal = Space.small, vertical = Space.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.hair),
    ) {
        if (onHome != null) {
            // Auto-mirrored, so it resolves to the right-pointing arrow that means "back" in a
            // right-to-left interface. The un-mirrored icon would point the way the user came from
            // in an English app and the way they are going in this one.
            BarIcon(Icons.AutoMirrored.Filled.ArrowBack, "خانه", onClick = onHome)
        }

        Text(
            state.document.name,
            style = MaterialTheme.typography.labelLarge,
            color = Ink.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = Space.small),
        )

        // Through the view model, not the editor: painted pixels are on the same stack, and an undo
        // that only knew about the document would skip every stroke.
        BarIcon(Icons.AutoMirrored.Filled.Undo, "برگشت", enabled = model.canUndo, onClick = model::undo)
        BarIcon(Icons.AutoMirrored.Filled.Redo, "جلو", enabled = model.canRedo, onClick = model::redo)

        Box {
            BarIcon(Icons.Filled.MoreVert, "بیشتر") { menu = true }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = Ink.ChromeRaised,
            ) {
                MenuAction("ذخیره", Icons.Filled.Save) { menu = false; onSave() }
                MenuAction("باز کردن", Icons.Filled.FolderOpen) { menu = false; onOpen() }
                MenuAction("اندازهٔ صفحه", Icons.Filled.FitScreen) {
                    menu = false
                    model.act { fitCanvas() }
                }
                // A template replaces the whole document, which is exactly the kind of expensive,
                // rare action this menu exists for.
                MenuAction("کتابخانه", Icons.Filled.Star) {
                    menu = false
                    model.act { openSheet(SheetContent.LibraryPanel, SheetDetent.FULL) }
                }
                MenuAction("تنظیمات", Icons.Filled.Settings) {
                    menu = false
                    model.act { openSheet(SheetContent.Settings, SheetDetent.FULL) }
                }
            }
        }

        ExportPill(onExport)
    }
}

@Composable
private fun MenuAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink.Text) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = Ink.TextMuted, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/**
 * Export, as the bar's one filled control.
 *
 * The gradient is the app's single one, and this is where it goes in the editor: getting the picture
 * out is what the whole screen is in service of, and it was previously a grey icon indistinguishable
 * from the five beside it.
 */
@Composable
private fun ExportPill(onExport: () -> Unit) {
    Row(
        Modifier
            .height(PILL)
            .clip(Corners.chip)
            .background(Ink.AccentGradient)
            .clickable(onClick = onExport)
            .padding(horizontal = Space.large)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.tight),
    ) {
        Icon(Icons.Filled.IosShare, contentDescription = null, tint = Ink.OnAccent, modifier = Modifier.size(16.dp))
        Text("خروجی", style = MaterialTheme.typography.labelLarge, color = Ink.OnAccent, maxLines = 1)
    }
}

/**
 * The contextual bar: what to do with what is selected.
 *
 * A floating card above the toolbar rather than another full-width strip. Two stacked bars of equal
 * weight is the arrangement that makes an editor feel walled in at the bottom; a card that is
 * visibly narrower than the canvas reads as *about the selection* rather than as more chrome.
 */
@Composable
internal fun ContextualBar(state: EditorState, model: EditorViewModel, onEditText: (LayerId) -> Unit) {
    val id = state.selection.primary ?: return
    val isText = state.selectedLayers.any { it.id == id && it is Layer.Text }
    Row(
        Modifier
            .padding(horizontal = Space.large, vertical = Space.small)
            .clip(Corners.chip)
            .background(Ink.ChromeRaised)
            .border(1.dp, Ink.Outline, Corners.chip)
            .padding(horizontal = Space.small, vertical = Space.small),
        horizontalArrangement = Arrangement.spacedBy(Space.hair),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isText) {
            BarAction(Icons.Filled.TextFields, "متن") { onEditText(id) }
        }
        BarAction(Icons.Filled.Tune, "لایه") {
            // Blend mode, both opacities, clipping and masking — the panel that used to be a
            // placeholder, and the one people open most often after moving something.
            model.act { openSheet(SheetContent.LayerParameters(id), SheetDetent.FULL) }
        }
        // The same glyph the home screen puts on "افکت", so the two places agree about what an
        // effect is. A plus would have meant "add" — which is true and says nothing about what.
        BarAction(Icons.Filled.AutoAwesome, "افکت") {
            // A stroke is the effect people reach for first, and it is immediately visible, so the
            // sheet that opens has something to show.
            model.act { addEffect(id, Effect.Stroke(8f, Fill.Solid(Color.WHITE))) }
        }
        BarAction(Icons.AutoMirrored.Filled.AlignHorizontalLeft, "چیدمان") {
            model.act { openSheet(SheetContent.Arrange, SheetDetent.FULL) }
        }
        BarAction(Icons.Filled.ContentCopy, "کپی") {
            // The id comes from the document rather than from a count: a count collides the first
            // time a layer is deleted, and two layers with one id is an editor that loses work.
            model.act { duplicateLayer(id, nextLayerId(id.value)) }
        }
        BarAction(Icons.Filled.VerticalAlignTop, "به جلو") { model.act { bringToFront(id) } }
        BarAction(Icons.Filled.Delete, "حذف", tint = Ink.Danger) { model.act { deleteLayer(id) } }
    }
}

/**
 * The persistent toolbar.
 *
 * Nine tools, and they scroll rather than being squeezed onto one screen width. Dividing 411dp by
 * nine gives each tool 45dp of everything — target, icon and label — which is below the platform's
 * touch minimum and far below what a Persian label needs. Scrolling costs discoverability of the
 * last two; cramming cost the usability of all nine.
 */
@Composable
internal fun Toolbar(state: EditorState, model: EditorViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink.Chrome)
            .systemBarsPadding()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.small, vertical = Space.small),
        horizontalArrangement = Arrangement.spacedBy(Space.tight),
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
        ToolButton(Tool.IMAGE, Icons.Filled.Image, "بوم", state, model) {
            model.act { openSheet(SheetContent.CanvasTools, SheetDetent.FULL) }
        }
        ToolButton(Tool.PEN, Icons.Filled.Draw, "قلم", state, model) {
            model.act { openSheet(SheetContent.Vector, SheetDetent.FULL) }
        }
        ToolButton(Tool.SHAPE, Icons.Filled.Category, "شکل", state, model) {
            model.act { openSheet(SheetContent.ShapeTools, SheetDetent.FULL) }
        }
        ToolButton(Tool.SELECT, Icons.Filled.Highlight, "انتخاب", state, model) {
            model.act { openSheet(SheetContent.PixelSelection, SheetDetent.PEEK) }
        }
        ToolButton(Tool.ADJUST, Icons.Filled.Tune, "تنظیم", state, model) {
            model.act { openSheet(SheetContent.Adjustments, SheetDetent.FULL) }
        }
    }
}

/**
 * The sheet's head: a grip that grows it, and a close.
 *
 * The divider under it is gone. On a card that is already a step lighter than the ground, a line
 * immediately below the grip is a third horizontal edge within twenty points of two others, and
 * removing it is most of why the sheet now reads as one surface rather than as stacked strips.
 */
@Composable
private fun SheetHeader(state: EditorState, model: EditorViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.medium, vertical = Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "بستن",
            style = MaterialTheme.typography.labelLarge,
            color = Ink.TextMuted,
            modifier = Modifier
                .clip(Corners.chip)
                .clickable { model.act { closeSheet() } }
                .padding(horizontal = Space.medium, vertical = Space.small)
                .semantics { role = Role.Button },
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
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
        }
        // Balances the close on the other side so the grip sits in the middle of the sheet rather
        // than in the middle of what is left over after the close.
        Box(Modifier.width(CLOSE_BALANCE))
    }
}

/**
 * One tool.
 *
 * The active one is a filled circle, not a tinted icon. A tint alone is the state that disappears in
 * a screenshot, at a glance, and for anyone with any degree of colour blindness — the filled shape
 * survives all three.
 */
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
            .width(TOOL)
            .clip(Corners.small)
            .clickable {
                model.act { setTool(tool) }
                onOpen()
            }
            .padding(vertical = Space.small)
            .semantics {
                role = Role.Tab
                selected = active
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.tight),
    ) {
        Box(
            Modifier
                .size(TOOL_CIRCLE)
                .clip(Corners.chip)
                .background(if (active) Ink.AccentSoft else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) Ink.Accent else Ink.TextMuted,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) Ink.Accent else Ink.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How much of the chrome colour the top bar carries. The canvas stays faintly visible behind it. */
private const val SCRIM = 0.92f

private val PILL = 36.dp
private val TOOL = 64.dp
private val TOOL_CIRCLE = 40.dp

/** The close label's width, mirrored on the other side so the grip is centred on the sheet. */
private val CLOSE_BALANCE = 56.dp

/** What the picker accepts. Every still image; video is deliberately not part of this app. */
private const val IMAGE_MIME = "image/*"

/**
 * Everything, because a `.acv` has no registered MIME type.
 *
 * Filtering on one Android does not recognise hides the very files the picker was opened for, which
 * looks to the user like the presets are not there at all. The reader checks the content instead,
 * and refuses anything that is not a preset.
 */
private const val PRESET_MIME = "*/*"

/**
 * Renders a document to pixels on the GL thread.
 *
 * The bridge merging and rasterising need: both replace layers with a picture, and the picture can
 * only come from the renderer that draws the canvas. Kept here rather than in the view model
 * because it needs the view, and a view model that holds a view holds the whole activity with it.
 */
private suspend fun renderDocument(
    handle: CanvasHandle,
    document: ir.pixellab.core.model.Document,
): ir.pixellab.core.codec.RasterImage? {
    val surface = handle.surface ?: return null
    val result: ir.pixellab.engine.android.ExportResult = suspendCoroutine { continuation ->
        surface.export(document, ir.pixellab.core.codec.Format.PNG, 1f) { continuation.resume(it) }
    }
    val success = result as? ir.pixellab.engine.android.ExportResult.Success ?: return null
    return runCatching { ir.pixellab.core.codec.Codecs.decode(success.bytes) }.getOrNull()
}
