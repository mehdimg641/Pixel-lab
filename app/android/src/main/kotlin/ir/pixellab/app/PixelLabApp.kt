package ir.pixellab.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.editor.SheetDetent
import ir.pixellab.core.editor.TextSection
import ir.pixellab.core.model.LayerId
import kotlinx.coroutines.launch

/**
 * Where in the application you are.
 *
 * ### Six, not seven
 *
 * The brief lists «خروجی» beside the studios, and it is not one of these. Its own rule decides it:
 * a sheet is for work that has a *flow* — start it, answer three questions, it ends — and choosing a
 * format, a scale and a quality and pressing go is exactly that. `ExportSheet` stays a sheet, opened
 * by the accent button in the editor's header, which is also where the brief draws it.
 *
 * ### Why an enum and not a navigation library
 *
 * The document lives in `EditorViewModel` and every destination here reads it. A route that
 * re-created the editor on arrival would throw away the user's work, which is the failure a
 * navigation graph makes easy to write and hard to see. Six destinations and one `when` is not a
 * shortcut; it is the shape that cannot lose a document.
 */
enum class Destination {
    GALLERY,
    EDITOR,
    TEMPLATES,
    BRUSH,
    RETOUCH,
    TEXT_STUDIO,
}

/**
 * The screens, and the one decision about which is showing.
 *
 * ### Back
 *
 * From a studio, back goes to the **editor**, not home. Somebody who stepped into the text studio
 * from a headline they are setting wants to land back on that headline; sending them to the gallery
 * would make the way out of a room the way out of the building.
 *
 * From the editor and the templates page, back goes home — and out of the editor it is genuinely
 * "put it down" rather than "close it", because the document stays in the view model with its undo
 * history intact.
 */
@Composable
fun PixelLabApp(model: EditorViewModel) {
    var where by remember { mutableStateOf(Destination.GALLERY) }
    var entry by remember { mutableStateOf<QuickAction?>(null) }
    // Which headline the text studio is set on, and at which section. Held here rather than in the
    // destination enum so that returning to the studio a second time is a plain navigation rather
    // than a differently-shaped destination.
    var studioLayer by remember { mutableStateOf<LayerId?>(null) }
    var studioSection by remember { mutableStateOf(TextSection.CONTENT) }

    // Bumped whenever something may have changed the saved-project list, which is what makes the
    // home screen re-read it. A timestamp would work too and would re-read on every recomposition.
    var revision by remember { mutableIntStateOf(0) }
    val projects = rememberProjects(revision)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Registered once for the whole application rather than inside whichever screen wants it. A
    // launcher created in a conditionally composed subtree is unregistered the moment that subtree
    // leaves, and the result then arrives with nowhere to go — which is what happens when a studio
    // asks for a photograph and the picker outlives the screen that opened it.
    val picking = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                loadImage(context, uri).onSuccess { (image, name) -> model.placeImage(image, name) }
            }
        }
    }
    val actions = remember(picking) {
        EditorActions(
            pickImage = { picking.launch(IMAGE_MIME) },
            addText = { model.addTextLayer() },
        )
    }

    val toEditor = { where = Destination.EDITOR }

    androidx.compose.runtime.CompositionLocalProvider(LocalEditorActions provides actions) {
        when (where) {
            Destination.EDITOR -> {
                BackHandler {
                    // A panel one level down closes first. It is docked rather than modal now, so
                    // nothing about it *looks* like something back would dismiss — which is exactly
                    // why back has to do it: the alternative is that a user in the font picker
                    // presses back and lands in the gallery.
                    if (model.state.sheet.isOpen) {
                        model.cancelSheet()
                    } else {
                        where = Destination.GALLERY
                        revision++
                    }
                }
                EditorScreen(
                    model = model,
                    entry = entry,
                    onEntryHandled = { entry = null },
                    onHome = {
                        where = Destination.GALLERY
                        revision++
                    },
                    onNavigate = { destination, layer, section ->
                        if (destination == Destination.TEXT_STUDIO) {
                            studioLayer = layer
                            studioSection = section ?: TextSection.CONTENT
                        }
                        where = destination
                    },
                )
            }

            Destination.TEMPLATES -> {
                BackHandler { where = Destination.GALLERY }
                TemplatesScreen(
                    onNew = { template ->
                        model.newFromTemplate(template)
                        entry = null
                        where = Destination.EDITOR
                    },
                    onBack = { where = Destination.GALLERY },
                )
            }

            Destination.BRUSH -> {
                BackHandler(onBack = toEditor)
                BrushStudioScreen(
                    model = model,
                    onBack = toEditor,
                    onPickImage = { picking.launch(IMAGE_MIME) },
                )
            }

            Destination.RETOUCH -> {
                BackHandler(onBack = toEditor)
                RetouchStudioScreen(model = model, onBack = toEditor)
            }

            Destination.TEXT_STUDIO -> {
                BackHandler(onBack = toEditor)
                val layer = studioLayer
                if (layer == null) {
                    // Nothing to set. Arriving here without a headline is a routing mistake rather
                    // than a state a user can reach, so it corrects itself instead of drawing an
                    // empty room.
                    where = Destination.EDITOR
                } else {
                    TextStudioScreen(
                        model = model,
                        layer = layer,
                        initialSection = studioSection,
                        onDone = toEditor,
                    )
                }
            }

            Destination.GALLERY -> HomeScreen(
                projects = projects,
                onNew = { template ->
                    model.newFromTemplate(template)
                    entry = null
                    where = Destination.EDITOR
                },
                onOpen = { file ->
                    scope.launch {
                        openProject(file).onSuccess {
                            model.openProject(it)
                            entry = null
                            where = Destination.EDITOR
                        }
                        // A failure leaves the user on the home screen with the file still listed,
                        // which is the truthful outcome: the project did not open, and the editor
                        // showing whatever was in it before would read as though it had.
                    }
                },
                onQuickAction = { action ->
                    entry = action
                    where = Destination.EDITOR
                },
                onAllTemplates = { where = Destination.TEMPLATES },
                onSettings = {
                    entry = null
                    where = Destination.EDITOR
                    model.act { openSheet(SheetContent.Settings, SheetDetent.FULL) }
                },
            )
        }
    }
}
