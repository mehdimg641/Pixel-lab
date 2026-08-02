package ir.pixellab.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.editor.SheetDetent
import kotlinx.coroutines.launch

/**
 * The two screens, and the one decision about which is showing.
 *
 * Kept here rather than in a navigation library because there are exactly two destinations and the
 * editor holds the document — a route that re-created the editor on every navigation would throw
 * away the user's work, which is the failure a navigation graph makes easy to write and hard to see.
 *
 * The back gesture goes home rather than out of the app. On a phone, leaving an editor by the system
 * back button is what everybody tries first, and an app that exits instead is one people stop
 * trusting with unsaved work.
 */
@Composable
fun PixelLabApp(model: EditorViewModel) {
    var editing by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf<QuickAction?>(null) }

    // Bumped whenever something may have changed the saved-project list, which is what makes the
    // home screen re-read it. A timestamp would work too and would re-read on every recomposition.
    var revision by remember { mutableIntStateOf(0) }
    val projects = rememberProjects(revision)
    val scope = rememberCoroutineScope()

    if (editing) {
        BackHandler {
            // The document stays in the view model, so this is genuinely "put it down", not "close
            // it" — coming back finds everything where it was, including the undo history.
            editing = false
            revision++
        }
        EditorScreen(
            model = model,
            entry = entry,
            onEntryHandled = { entry = null },
            onHome = {
                editing = false
                revision++
            },
        )
        return
    }

    HomeScreen(
        projects = projects,
        onNew = { template ->
            model.newFromTemplate(template)
            entry = null
            editing = true
        },
        onOpen = { file ->
            scope.launch {
                openProject(file).onSuccess {
                    model.openProject(it)
                    entry = null
                    editing = true
                }
                // A failure leaves the user on the home screen with the file still listed, which is
                // the truthful outcome: the project did not open, and the editor showing whatever
                // was in it before would read as though it had.
            }
        },
        onQuickAction = { action ->
            entry = action
            editing = true
        },
        onSettings = {
            entry = null
            editing = true
            model.act { openSheet(SheetContent.Settings, SheetDetent.FULL) }
        },
    )
}
