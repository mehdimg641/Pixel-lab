package ir.pixellab.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.automirrored.outlined.AlignHorizontalLeft
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Deblur
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Grid4x4
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * The layout is the specification's, §۱۳.۵, and its three fixed bars are not arbitrary:
 *
 * ```
 * نوار تاریخچه   ۵۶dp   ← where you have been
 * بوم            انعطاف‌پذیر
 * ریبون بافتار    ۸۸dp   ← what you can do to what is selected
 * داک اصلی       ۶۴dp   ← what kind of work you are doing
 * ```
 *
 * The dock names five *kinds of work* rather than nine tools. Nine tools across a 411dp phone gives
 * each 45dp of everything — target, icon and Persian label — which is under the platform's touch
 * minimum, and the version this replaces solved that by making the row scroll, which hides tools
 * instead. Five entries fit at 82dp each with nothing hidden, and the tools themselves move up into
 * the ribbon, where they change with what is actually selected.
 *
 * @param entry what the user pressed on the home screen, or null if they came in another way. It is
 *   consumed once — [onEntryHandled] — so that returning later does not re-open a sheet the user has
 *   since closed.
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
    var dock by remember { mutableStateOf(Dock.PHOTO) }

    // The ribbon's own state: which cluster is chosen and at what granularity. Beside the editor
    // rather than inside the document, because a chosen chip is not part of the artwork.
    val ribbon = remember { RibbonState() }

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

    // A collage is picked several photographs at a time, which is a different contract rather than
    // the same one used repeatedly: making someone return to the gallery eleven times to fill a
    // nine-cell layout is the difference between a feature people use and one they try once.
    val pickingCollage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                for (uri in uris) {
                    loadImage(context, uri)
                        .onSuccess { (image, name) -> model.addCollagePhoto(image, name) }
                        .onFailure { outcome = FileOutcome.Refused(it.message ?: "تصویر خوانده نشد") }
                }
            }
        }
    }

    // Which saved grade the batch picker was opened for. Held beside the launcher because the
    // choice is made before the picker opens and the result arrives long afterwards.
    var batchLook by remember { mutableStateOf<ir.pixellab.core.editor.Look?>(null) }
    val batch = remember { BatchRun() }

    val pickingBatch = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        val look = batchLook
        batchLook = null
        if (look != null && uris.isNotEmpty()) {
            scope.launch {
                val result = batch.run(context, handle, look, uris, IMAGE_EXPORT_FORMAT, model.assets)
                outcome = if (result.failed.isEmpty()) {
                    FileOutcome.Exported("${result.written} عکس با «${look.name}»", 0, 0)
                } else {
                    // Named rather than counted: "three failed" leaves the user to work out which
                    // three, and the answer is not in the gallery because they are not there.
                    FileOutcome.Refused(
                        "${result.written} از ${result.total} نوشته شد — این‌ها نشد: " +
                            result.failed.joinToString("، "),
                    )
                }
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

    // A third launcher, for the same reason there is a second: a picker offering a photograph when
    // the user asked for a grading table is how a feature gets abandoned rather than reported.
    val pickingLut = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                loadColorLookup(context, uri)
                    .onSuccess { (strip, name) ->
                        val selected = state.primaryLayer as? Layer.AdjustmentLayer
                        // Replace the table on the layer the user is already editing; only start a
                        // new one when they are not on a Color Lookup, or importing from the
                        // catalogue would silently leave the layer they were adjusting untouched.
                        if (selected?.adjustment is ir.pixellab.core.model.Adjustment.ColorLookup) {
                            model.setColorLookup(selected.id, strip, name)
                        } else {
                            model.addColorLookup(strip, name)
                        }
                    }
                    .onFailure { outcome = FileOutcome.Refused(it.message ?: "جدول رنگ خوانده نشد") }
            }
        }
    }

    LaunchedEffect(entry) {
        val action = entry ?: return@LaunchedEffect
        dock = action.dock
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

    // Every panel's empty state can reach these. A sheet that says "select an image layer" and
    // offers no way to get one is the shape of bug that makes a whole application feel broken.
    val actions = EditorActions(
        pickImage = { picking.launch(IMAGE_MIME) },
        addText = { model.addTextLayer()?.let { editingText = it } },
        applyLookToPhotos = { look ->
            batchLook = look
            pickingBatch.launch(IMAGE_MIME)
        },
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink.Ground)) {
        val screenHeight = maxHeight

        // The canvas fills the screen and the bars are drawn over it, so the editor has to be told
        // how much of it they cover — otherwise the artboard is centred behind them and sits low,
        // with its bottom edge under the ribbon. In dp here and in pixels there, because only this
        // side knows the density.
        val density = LocalDensity.current
        LaunchedEffect(density) {
            with(density) {
                model.onChromeInsets(
                    top = Frame.history.toPx(),
                    bottom = (Frame.ribbon + Frame.dock).toPx(),
                )
            }
        }

        EditorCanvas(
            state = state,
            bounds = bounds,
            fonts = model.fonts,
            assets = model.assets,
            assetGeneration = model.paint.generation,
            selection = SelectionOverlay(
                model.select.outline,
                model.select.draft,
                mask = if (model.quickMask) model.select.selection else null,
            ),
            pen = PenOverlay(model.pen.path, model.pen.active),
            handle = handle,
            onGesture = model::onGesture,
            onSize = model::onScreenSize,
        )

        Rulers(state, model, Modifier.align(Alignment.TopStart))

        Column(Modifier.align(Alignment.TopCenter)) {
            TopBar(state = state, model = model, onHome = onHome)
            batch.progress?.let {
                Text(
                    "در حال اعمال روی عکس ${it.done + 1} از ${it.total} — ${it.current}",
                    color = Ink.TextMuted,
                    modifier = Modifier.padding(horizontal = Space.medium, vertical = Space.small),
                )
            }
            outcome?.let {
                OutcomeBanner(it, Modifier.padding(horizontal = Space.medium, vertical = Space.small)) {
                    outcome = null
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The selection card floats above the ribbon rather than replacing it, because the two
            // answer different questions: the ribbon is "what can I do here", the card is "what do
            // I do to *this*". Merging them means the ribbon's contents change under the user
            // every time they tap the canvas.
            AnimatedVisibility(
                visible = state.hasSelection && !state.sheet.isOpen,
                enter = slideInVertically(tween(Motion.STANDARD, easing = Motion.ease)) { it },
                exit = slideOutVertically(tween(Motion.STANDARD, easing = Motion.ease)) { it },
            ) {
                SelectionCard(state, model, onEditText = { editingText = it })
            }
            val editing = state.primaryLayer as? Layer.Text
            if (editing != null && dock in TYPESETTING) {
                GlyphRibbonPanel(
                    text = editing.spec.text,
                    state = ribbon,
                    onStretch = { cluster, amount ->
                        model.setText(editing.id, stretched(editing.spec.text, cluster, amount))
                    },
                )
            }
            Ribbon(
                dock = dock,
                state = state,
                model = model,
                onPickImage = { picking.launch(IMAGE_MIME) },
                // Straight into the keyboard. A text layer that arrives carrying "متن نمونه" and
                // no way to replace it without hunting for an edit button is not a text tool.
                onAddText = { model.addTextLayer()?.let { editingText = it } },
                onEditText = { editingText = it },
                onExport = { exporting = true },
                onSave = { scope.launch { outcome = saveProject(context, model.currentProject()) } },
                onOpen = { scope.launch { opening = Storage.listProjects(context) } },
            )
            MainDock(dock, state, model) { chosen ->
                dock = chosen
                // Choosing the text tool *makes* text, the way it does in every editor: a caret
                // appears and the keyboard opens. Before this it only changed which row of buttons
                // was showing, so the tool named after typing was the one tool that could not be
                // used to type — the user had to find the font picker and tap a typeface first,
                // which is a typographic decision demanded before a single letter.
                if (chosen == Dock.TEXT && state.primaryLayer !is Layer.Text) {
                    model.addTextLayer()?.let { editingText = it }
                }
            }
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
            enter = slideInVertically(tween(Motion.SHEET, easing = Motion.ease)) { it },
            exit = slideOutVertically(tween(Motion.SHEET, easing = Motion.ease)) { it },
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
                    CompositionLocalProvider(LocalEditorActions provides actions) {
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
                            onImportLut = { pickingLut.launch(PRESET_MIME) },
                            render = { document -> renderDocument(handle, document) },
                            modifier = Modifier.fillMaxHeight(),
                        )
                        is SheetContent.Retouch -> RetouchSheetBody(state, model, Modifier.fillMaxHeight())
                        is SheetContent.Portrait -> PortraitSheetBody(state, model, Modifier.fillMaxHeight())
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
                        is SheetContent.Collage -> CollageSheetBody(
                            model = model,
                            onPickPhotos = { pickingCollage.launch(IMAGE_MIME) },
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
}

/**
 * The five kinds of work. Specification §۱۳.۵: `عکس · متن · سه‌بعدی · لایه · خروجی`.
 *
 * Fixed at five and in this order. The dock is the one part of the interface a user builds muscle
 * memory for, so it may not reorder itself, grow with context, or hide an entry that happens to be
 * unavailable — everything conditional belongs in the ribbon above it.
 */
enum class Dock(val icon: ImageVector, val label: String) {
    PHOTO(Icons.Outlined.Image, "عکس"),
    TEXT(Icons.Outlined.TextFields, "متن"),
    DIMENSIONAL(Icons.Outlined.ViewInAr, "سه‌بعدی"),
    LAYERS(Icons.Outlined.Layers, "لایه"),
    EXPORT(Icons.Outlined.IosShare, "خروجی"),
}

/**
 * The history strip, along the top. Specification §۱۳.۵ and interaction idea ۷.
 *
 * One chip per step, the current position filled. Tapping a chip moves the document to that point,
 * which is what makes this a history *strip* rather than a pair of arrows: undo and redo answer
 * "one more" while this answers "back to before I started the shadow", and on a phone the second
 * question is the one people actually have.
 *
 * The chips are marks rather than thumbnails. A thumbnail per step would mean rendering the document
 * once per history entry, which on a 4000-pixel canvas is seconds of work for a 40dp picture — and a
 * strip of *fake* thumbnails would be worse than none.
 */
@Composable
internal fun TopBar(
    state: EditorState,
    model: EditorViewModel,
    onHome: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Ink.Chrome.copy(alpha = SCRIM))
            .systemBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(Frame.history).padding(horizontal = Space.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.tight),
        ) {
            if (onHome != null) {
                // Auto-mirrored, so it resolves to the right-pointing arrow that means "back" in a
                // right-to-left interface — specification §۱۳.۳, which requires every directional
                // icon to mirror.
                BarIcon(Icons.AutoMirrored.Outlined.ArrowBack, "خانه", onClick = onHome)
            }

            Text(
                state.document.name,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = Ink.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.small),
            )

            HistoryStrip(model, Modifier.weight(1f))

            // Through the view model, not the editor: painted pixels are on the same stack, and an
            // undo that only knew about the document would skip every stroke.
            BarIcon(Icons.AutoMirrored.Outlined.Undo, "برگشت", enabled = model.canUndo, onClick = model::undo)
            BarIcon(Icons.AutoMirrored.Outlined.Redo, "جلو", enabled = model.canRedo, onClick = model::redo)
            // Next to undo because it answers the neighbouring question — "was that better?" — and
            // because on a phone the only place a compare gets used is the place a thumb already is.
            HeldBarIcon(
                Icons.Outlined.Compare,
                "مقایسه با اول کار",
                enabled = model.historyPosition > 0,
                held = model.comparing,
                onPress = model::beginCompare,
                onRelease = model::endCompare,
            )
            BarIcon(Icons.Outlined.FitScreen, "اندازهٔ صفحه") { model.act { fitCanvas() } }
            BarIcon(Icons.Outlined.Settings, "تنظیمات") {
                model.act { openSheet(SheetContent.Settings, SheetDetent.FULL) }
            }
        }
    }
}

// Internal rather than private so TouchTargetTest can measure it on its own. The strip is empty
// until the user has done something, so it is invisible to a test that renders a fresh editor —
// and it held the smallest target in the application.
@Composable
internal fun HistoryStrip(model: EditorViewModel, modifier: Modifier = Modifier) {
    val length = model.historyLength
    if (length == 0) {
        Box(modifier)
        return
    }
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        // No gap. The targets are adjacent on purpose: a gap between two 24dp targets is space the
        // finger can land in that belongs to neither of them, and WCAG's dense-control allowance
        // only holds while nothing eats into the target. The *marks* are still visibly separated,
        // because each one is a 6dp dot centred in its own target.
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Zero is the document as it was opened, so there are length + 1 positions to stand at.
        for (position in 0..length) {
            val here = position == model.historyPosition
            Box(
                Modifier
                    // The target, which is not the graphic. This was the graphic: a 6dp square
                    // carrying the click, which is a control no finger can hit — the single worst
                    // touch target in the application, and one that costs an accidental jump
                    // through history when the neighbouring dot answers instead.
                    .size(width = MARK_TARGET, height = Space.touch)
                    .clickable(onClickLabel = "گام ${Digits.prose(position)}") { model.jumpTo(position) }
                    .semantics {
                        role = Role.Button
                        selected = here
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = if (here) MARK_WIDE else MARK, height = MARK)
                        .clip(Corners.chip)
                        .background(if (here) Ink.Accent else Ink.Outline),
                )
            }
        }
    }
}

/**
 * The contextual ribbon. Eighty-eight points, directly under the canvas.
 *
 * What it holds changes with the dock, and that is the whole reason the dock could shrink to five
 * entries: every tool the old nine-entry toolbar carried is still one tap away, but it is now
 * grouped with the work it belongs to instead of competing with tools from four other jobs.
 */
@Composable
internal fun Ribbon(
    dock: Dock,
    state: EditorState,
    model: EditorViewModel,
    onPickImage: () -> Unit,
    onAddText: () -> Unit,
    onEditText: (LayerId) -> Unit,
    onExport: () -> Unit,
    onSave: () -> Unit,
    onOpen: () -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(Frame.ribbon)
            .background(Ink.Chrome)
            // A fade at whichever end still has something behind it.
            //
            // The strip scrolls and gave no sign of it, so the last entries were simply sliced off
            // by the edge of the screen — a user photographed «خط د…» cut in half and reasonably
            // read it as broken layout rather than as more controls. Drawn rather than reserved as
            // space: an arrow or a gutter would cost width on the axis that is already short.
            //
            // `drawWithContent`, so the fade lands *over* the chips instead of under them, and
            // driven by the scroll position, so it disappears at each end rather than implying
            // there is always more.
            .drawWithContent {
                drawContent()
                val fade = FADE.toPx()
                if (scroll.value > 0) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Ink.Chrome, Color.Transparent),
                            endX = fade,
                        ),
                        size = androidx.compose.ui.geometry.Size(fade, size.height),
                    )
                }
                if (scroll.value < scroll.maxValue) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Ink.Chrome),
                            startX = size.width - fade,
                            endX = size.width,
                        ),
                        topLeft = Offset(size.width - fade, 0f),
                        size = androidx.compose.ui.geometry.Size(fade, size.height),
                    )
                }
            }
            .horizontalScroll(scroll)
            .padding(horizontal = Space.small),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // **What is selected wins over what the dock says.**
        //
        // The dock names a *kind of work* and that is the right thing for it to name — until
        // something is selected, at which point the only question anybody has is "what can I do to
        // this?". Keying the whole ribbon to the dock meant tapping a piece of text and being shown
        // the photograph tools, with its own font, colour and shadow three panels away behind a
        // menu that gave no sign of holding them. That is the difference between an editor and a
        // puzzle, and it is what made the simplest job in the application the hardest one.
        val selected = state.primaryLayer
        if (selected is Layer.Text) {
            TextRibbon(selected.id, state, model, onEditText)
            return@Row
        }

        when (dock) {
            Dock.PHOTO -> {
                RibbonAction(Icons.Outlined.Image, "افزودن", Tool.IMAGE, state, model, onPickImage)
                RibbonSheet(Icons.Outlined.GridView, "کلاژ", Tool.IMAGE, SheetContent.Collage, state, model)
                RibbonSheet(Icons.Outlined.Crop, "بوم", Tool.IMAGE, SheetContent.CanvasTools, state, model)
                RibbonSheet(Icons.Outlined.Highlight, "انتخاب", Tool.SELECT, SheetContent.PixelSelection, state, model, SheetDetent.PEEK)
                RibbonSheet(Icons.Outlined.AutoFixHigh, "ترمیم", Tool.RETOUCH, SheetContent.Retouch, state, model)
                RibbonSheet(Icons.Outlined.Face, "پرتره", Tool.RETOUCH, SheetContent.Portrait, state, model)
                RibbonSheet(Icons.Outlined.Tune, "تنظیم", Tool.ADJUST, SheetContent.Adjustments, state, model)
                RibbonAction(Icons.Outlined.Brush, "قلم‌مو", Tool.BRUSH, state, model) {
                    // A brush needs somewhere to paint. Creating the layer on the first press
                    // rather than asking for one is the difference between a tool that works and a
                    // tool that reports that it cannot.
                    if (state.primaryLayer !is Layer.Image) model.addPaintLayer()
                    model.act { openSheet(SheetContent.BrushSettings, SheetDetent.HALF) }
                }
            }

            Dock.TEXT -> {
                // First, and it did not exist. Writing a word required opening the font picker and
                // tapping a face — a typographic decision demanded before a single letter could be
                // typed, in the one dock named after typing.
                RibbonAction(Icons.Outlined.TextFields, "افزودن متن", Tool.TEXT, state, model, onAddText)
                RibbonSheet(Icons.Outlined.FontDownload, "فونت", Tool.TEXT, SheetContent.FontPicker, state, model)
                RibbonSheet(Icons.Outlined.TextFields, "تایپوگرافی", Tool.TEXT, SheetContent.Typography, state, model)
                RibbonSheet(Icons.Outlined.Category, "شکل", Tool.SHAPE, SheetContent.ShapeTools, state, model)
                RibbonSheet(Icons.Outlined.Draw, "قلم", Tool.PEN, SheetContent.Vector, state, model)
                RibbonSheet(Icons.Outlined.Star, "سبک", Tool.TEXT, SheetContent.StyleLibrary, state, model)
            }

            Dock.DIMENSIONAL -> {
                RibbonSheet(Icons.Outlined.ViewInAr, "صحنه", Tool.TEXT, SheetContent.Dimensional, state, model)
                RibbonSheet(Icons.Outlined.Star, "لوک", Tool.TEXT, SheetContent.LibraryPanel, state, model)
            }

            Dock.LAYERS -> {
                RibbonSheet(Icons.Outlined.Layers, "لایه‌ها", Tool.LAYERS, SheetContent.LayerList, state, model, SheetDetent.HALF)
                RibbonSheet(Icons.AutoMirrored.Outlined.AlignHorizontalLeft, "چیدمان", Tool.LAYERS, SheetContent.Arrange, state, model)
                RibbonSheet(Icons.Outlined.Grid4x4, "راهنما", Tool.LAYERS, SheetContent.Guides, state, model)
            }

            Dock.EXPORT -> {
                BarAction(Icons.Outlined.IosShare, "خروجی", onClick = onExport)
                BarAction(Icons.Outlined.Save, "ذخیره", onClick = onSave)
                BarAction(Icons.Outlined.FolderOpen, "باز کردن", onClick = onOpen)
                RibbonSheet(Icons.Outlined.Star, "کتابخانه", Tool.LAYERS, SheetContent.LibraryPanel, state, model)
            }
        }
    }
}

/**
 * Everything you can do to a piece of text, in the order you reach for it.
 *
 * Ordered by how often a hand goes there rather than by how the model is shaped: the words first,
 * then the face, then the colour, then the things that make it a title. Each effect is **one press**
 * that applies a usable version and opens its own numbers — a chip that only opened a panel would
 * make the user press twice to find out what it even does, and a chip that applied without opening
 * anything would leave them with no way to change it.
 *
 * The defaults are chosen to be *visible at a glance and immediately adjustable*, not to be subtle.
 * A drop shadow at two per cent is indistinguishable from a broken button.
 */
@Composable
private fun TextRibbon(
    id: LayerId,
    state: EditorState,
    model: EditorViewModel,
    onEditText: (LayerId) -> Unit,
) {
    BarAction(Icons.Outlined.TextFields, "ویرایش") { onEditText(id) }
    RibbonSheet(Icons.Outlined.FontDownload, "فونت", Tool.TEXT, SheetContent.FontPicker, state, model)
    RibbonSheet(Icons.Outlined.Tune, "رنگ و اندازه", Tool.TEXT, SheetContent.Typography, state, model)

    val white = ir.pixellab.core.model.Color.WHITE
    val black = ir.pixellab.core.model.Color.BLACK

    BarAction(Icons.Outlined.Layers, "سایه") {
        model.act {
            addEffect(id, Effect.DropShadow(color = black, angle = 135f, distance = 12f, blur = 18f))
        }
    }
    BarAction(Icons.Outlined.FormatColorFill, "پوشش رنگ") {
        model.act { addEffect(id, Effect.Overlay(fill = Fill.Solid(ir.pixellab.core.model.Color(0.98f, 0.72f, 0.15f)))) }
    }
    BarAction(Icons.Outlined.BorderColor, "خط دور") {
        model.act { addEffect(id, Effect.Stroke(width = 10f, fill = Fill.Solid(white))) }
    }
    BarAction(Icons.Outlined.Lightbulb, "درخشش") {
        model.act {
            addEffect(
                id,
                Effect.OuterGlow(fill = Fill.Solid(ir.pixellab.core.model.Color(1f, 0.85f, 0.4f)), blur = 28f),
            )
        }
    }
    BarAction(Icons.Outlined.Deblur, "برجسته") {
        model.act { addEffect(id, Effect.Bevel(depth = 120f, size = 14f)) }
    }
    RibbonSheet(Icons.Outlined.ViewInAr, "سه‌بعدی", Tool.TEXT, SheetContent.Dimensional, state, model)
    RibbonSheet(Icons.Outlined.Star, "سبک آماده", Tool.TEXT, SheetContent.StyleLibrary, state, model)
}

/** A ribbon entry that opens a panel and takes its tool. */
@Composable
private fun RibbonSheet(
    icon: ImageVector,
    label: String,
    tool: Tool,
    content: SheetContent,
    state: EditorState,
    model: EditorViewModel,
    detent: SheetDetent = SheetDetent.FULL,
) {
    BarAction(icon, label, selected = state.tool == tool && state.sheet.content == content) {
        model.act {
            setTool(tool)
            openSheet(content, detent)
        }
    }
}

/** A ribbon entry that does something rather than opening a panel. */
@Composable
private fun RibbonAction(
    icon: ImageVector,
    label: String,
    tool: Tool,
    state: EditorState,
    model: EditorViewModel,
    onClick: () -> Unit,
) {
    BarAction(icon, label, selected = state.tool == tool) {
        model.act { setTool(tool) }
        onClick()
    }
}

/**
 * The main dock. Five entries, sixty-four points, no scrolling.
 *
 * The active entry is a filled pill rather than a tinted icon. A tint alone is the state that
 * disappears in a screenshot, at a glance, and for anyone with any degree of colour blindness — a
 * filled shape survives all three.
 */
@Composable
internal fun MainDock(
    dock: Dock,
    state: EditorState,
    model: EditorViewModel,
    onDock: (Dock) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink.Chrome)
            .systemBarsPadding()
            .height(Frame.dock),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dock.entries.forEach { entry ->
            DockButton(entry, active = entry == dock) {
                onDock(entry)
                // Switching the kind of work also switches the tool, so that the canvas gesture
                // matches the ribbon the user is now looking at. Without this, tapping «متن» leaves
                // a brush armed and the first touch paints on the artwork.
                model.act { setTool(entry.defaultTool(state)) }
            }
        }
    }
}

/**
 * The docks where a Persian cluster is the thing being worked on.
 *
 * Text and 3D. On the photo dock a selected text layer is incidental — the user is retouching a
 * photograph the caption happens to sit on — and taking the ribbon's eighty-eight points there would
 * push the retouch tools off the screen for a caption nobody is editing.
 */
private val TYPESETTING = setOf(Dock.TEXT, Dock.DIMENSIONAL)

/**
 * Applies a stretch, in either direction.
 *
 * A negative amount is a drag back towards the start, which has to *remove* kashida rather than add
 * a negative number of them — so it re-stretches the cluster from its unstretched form, which is
 * also what makes the gesture exactly reversible instead of accumulating rounding.
 */
private fun stretched(
    text: String,
    cluster: ir.pixellab.core.text.TextCluster,
    amount: Int,
): String {
    val plain = ir.pixellab.core.text.Clusters.resetElongation(text, cluster)
    val already = cluster.text.count { it == ir.pixellab.core.text.ArabicJoining.TATWEEL }
    val wanted = (already + amount).coerceAtLeast(0)
    if (wanted == 0) return plain
    // Re-split, because removing the kashida moved every offset after this cluster.
    val target = ir.pixellab.core.text.Clusters.of(plain)
        .firstOrNull { it.start <= cluster.start && it.text.isNotBlank() && it.joined }
        ?: return plain
    return ir.pixellab.core.text.Clusters.elongate(plain, target, wanted)
}

/** The tool a dock entry arms. Chosen so the first canvas touch after a switch does the obvious. */
private fun Dock.defaultTool(state: EditorState): Tool = when (this) {
    Dock.PHOTO -> if (state.tool in PHOTO_TOOLS) state.tool else Tool.IMAGE
    Dock.TEXT -> Tool.TEXT
    Dock.DIMENSIONAL -> Tool.TEXT
    Dock.LAYERS -> Tool.LAYERS
    Dock.EXPORT -> state.tool
}

private val PHOTO_TOOLS = setOf(Tool.IMAGE, Tool.SELECT, Tool.RETOUCH, Tool.ADJUST, Tool.BRUSH)

@Composable
private fun DockButton(entry: Dock, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(DOCK_ENTRY)
            .clip(Corners.card)
            .clickable(onClick = onClick, onClickLabel = entry.label)
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
                .size(width = DOCK_PILL, height = DOCK_PILL_HEIGHT)
                .clip(Corners.chip)
                .background(if (active) Ink.AccentSoft else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                entry.icon,
                contentDescription = null,
                tint = if (active) Ink.Accent else Ink.TextMuted,
                modifier = Modifier.size(Frame.icon),
            )
        }
        Text(
            entry.label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = if (active) Ink.Accent else Ink.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What to do with what is selected.
 *
 * A floating card above the ribbon rather than a third full-width strip. Three stacked bars of equal
 * weight is the arrangement that makes an editor feel walled in at the bottom; a card that is
 * visibly narrower than the canvas reads as *about the selection* rather than as more chrome.
 */
@Composable
internal fun SelectionCard(state: EditorState, model: EditorViewModel, onEditText: (LayerId) -> Unit) {
    val id = state.selection.primary ?: return
    val isText = state.selectedLayers.any { it.id == id && it is Layer.Text }
    Row(
        Modifier
            .padding(horizontal = Space.large, vertical = Space.small)
            .clip(Corners.chip)
            .background(Ink.ChromeRaised)
            .border(1.dp, Ink.Outline, Corners.chip)
            .padding(horizontal = Space.small, vertical = Space.small),
        horizontalArrangement = Arrangement.spacedBy(Space.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isText) {
            BarAction(Icons.Outlined.TextFields, "متن") { onEditText(id) }
        }
        BarAction(Icons.Outlined.Tune, "لایه") {
            // Blend mode, both opacities, clipping and masking — the panel people open most often
            // after moving something.
            model.act { openSheet(SheetContent.LayerParameters(id), SheetDetent.FULL) }
        }
        // The same glyph the home screen puts on «افکت», so the two places agree about what an
        // effect is. A plus would have meant "add" — true, and silent about what.
        BarAction(Icons.Outlined.AutoAwesome, "افکت") {
            // A stroke is the effect people reach for first, and it is immediately visible, so the
            // sheet that opens has something to show.
            model.act {
                addEffect(id, Effect.Stroke(8f, Fill.Solid(ir.pixellab.core.model.Color.WHITE)))
            }
        }
        BarAction(Icons.AutoMirrored.Outlined.AlignHorizontalLeft, "چیدمان") {
            model.act { openSheet(SheetContent.Arrange, SheetDetent.FULL) }
        }
        BarAction(Icons.Outlined.ContentCopy, "کپی") {
            // The id comes from the document rather than from a count: a count collides the first
            // time a layer is deleted, and two layers with one id is an editor that loses work.
            model.act { duplicateLayer(id, nextLayerId(id.value)) }
        }
        BarAction(Icons.Outlined.VerticalAlignTop, "به جلو") { model.act { bringToFront(id) } }
        BarAction(Icons.Outlined.Delete, "حذف", tint = Ink.Danger) { model.act { deleteLayer(id) } }
    }
}

/**
 * The sheet's head: cancel, what this sheet is, and confirm — with a grip between them.
 *
 * **A tool is a transaction.** Every sheet here writes its change the moment a control moves, and
 * until now the only way back was undo — so a user who opened a panel, moved four sliders and
 * thought better of it had to press undo four times and count. Every one of the eight reference apps
 * frames a tool as ✕ and ✓ with a live preview between them, and that framing is most of what makes
 * them read as easy. It costs no algorithm at all.
 *
 * The title is not decoration either. A sheet that says what it is can be opened from anywhere —
 * ribbon, quick action, another sheet — and still be read without working out how you got there.
 *
 * No divider under it. On a card already a step lighter than the ground, a line immediately below
 * the grip is a third horizontal edge within twenty points of two others.
 */
@Composable
private fun SheetHeader(state: EditorState, model: EditorViewModel) {
    // A new sheet is a new transaction. Keyed on the content so re-opening the same panel for a
    // different layer starts again rather than reverting to some earlier layer's baseline.
    LaunchedEffect(state.sheet.content) { model.noteSheetOpened() }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.medium, vertical = Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(Icons.Outlined.Close, "انصراف", tint = Ink.TextMuted) { model.cancelSheet() }

        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
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
            state.sheet.content?.let { content ->
                Text(
                    sheetTitle(content),
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    color = Ink.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Amber once there is something to keep, muted before that — so the tick reads as the
        // outcome of the work rather than as a second, differently-shaped close button.
        BarIcon(
            Icons.Outlined.Check,
            "تأیید",
            tint = if (model.sheetHasChanges) Ink.Accent else Ink.TextMuted,
        ) { model.confirmSheet() }
    }
}

/** What each sheet calls itself. Read straight off the header, so it has to match the ribbon. */
private fun sheetTitle(content: SheetContent): String = when (content) {
    is SheetContent.EffectParameters -> "افکت"
    is SheetContent.LayerParameters -> "لایه"
    SheetContent.LayerList -> "لایه‌ها"
    SheetContent.StyleLibrary -> "سبک"
    SheetContent.FontPicker -> "فونت"
    SheetContent.BrushSettings -> "قلم‌مو"
    SheetContent.PixelSelection -> "انتخاب"
    SheetContent.Adjustments -> "تنظیم"
    SheetContent.Retouch -> "ترمیم"
    SheetContent.Portrait -> "پرتره"
    SheetContent.Vector -> "قلم و مسیر"
    SheetContent.LibraryPanel -> "لوک"
    SheetContent.Collage -> "کلاژ"
    SheetContent.CanvasTools -> "بوم"
    SheetContent.ShapeTools -> "شکل"
    SheetContent.Arrange -> "چیدمان"
    SheetContent.Guides -> "شبکه و راهنما"
    SheetContent.Typography -> "تایپوگرافی"
    SheetContent.Settings -> "تنظیمات"
    SheetContent.Dimensional -> "صحنهٔ سه‌بعدی"
}

/** How much of the chrome colour the top bar carries. The canvas stays faintly visible behind it. */
private const val SCRIM = 0.92f

/** 411dp ÷ 5 with the dock's own padding taken off. */
private val DOCK_ENTRY = 72.dp
private val DOCK_PILL = 48.dp
private val DOCK_PILL_HEIGHT = 28.dp

/** How much of the ribbon's edge the fade covers. Wide enough to read as a fade, not a border. */
private val FADE = 28.dp

/** History marks. Small: there can be two hundred of them and they are a strip, not a control. */
private val MARK = 6.dp
private val MARK_WIDE = 18.dp

/**
 * What the finger gets for one history step.
 *
 * The one control in the application that cannot have the full [Space.touch] on both axes: the
 * count is unbounded — two hundred steps at 48dp is nine metres of scroller — so the strip takes
 * WCAG 2.2's allowance for dense repeated controls instead. 2.5.8 AA asks 24dp *provided the
 * targets do not encroach on one another*, which is why the row's gap is zero, and the full 48dp
 * is still spent on the axis that is free.
 *
 * The honest statement of the remaining weakness: a row of unlabelled dots tells you where you are
 * but not what each step *was*. Photoshop names them. Replacing this with a named list is a real
 * improvement and a separate piece of work; making it hittable is not.
 */
private val MARK_TARGET = 24.dp

/** What the picker accepts. Every still image; video is deliberately not part of this app. */
private const val IMAGE_MIME = "image/*"

/**
 * What a batch writes.
 *
 * PNG rather than the format the source happened to be in: a batch re-encodes, and re-encoding a
 * JPEG loses a little every time, which over a folder someone runs twice is visible. The user who
 * wants JPEG has the export dialog for the one picture they care about.
 */
private val IMAGE_EXPORT_FORMAT = ir.pixellab.core.codec.Format.PNG

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
