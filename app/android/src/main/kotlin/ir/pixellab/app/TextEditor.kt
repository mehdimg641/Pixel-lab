package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The text editor.
 *
 * A dialog rather than editing in place on the canvas. Editing in place is what the user is used
 * to, but it needs a caret, a selection model and a soft keyboard that does not cover the words —
 * three things that are their own piece of work. A dialog that always shows the whole string and
 * always has room for the keyboard is the version that is right rather than nearly right.
 *
 * Multi-line, because the target work is cover titles, which are two and three lines far more often
 * than one.
 */
@Composable
fun TextEditDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.ChromeRaised,
        title = { Text("ویرایش متن", color = Ink.Text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) { Text("تأیید", color = Ink.Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف", color = Ink.TextMuted) }
        },
        text = {
            Column {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    cursorBrush = SolidColor(Ink.Accent),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = Ink.Text,
                        fontSize = 20.sp,
                        // Content direction is resolved from the text itself, not from the
                        // interface. A Persian title and a Latin brand name go in the same field,
                        // and forcing either direction puts the punctuation on the wrong end.
                        textDirection = TextDirection.Content,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Ink.Chrome)
                        .padding(12.dp)
                        .focusRequester(focus),
                )
                Text(
                    "برای رفتن به خط بعد از کلید Enter استفاده کنید",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
    )
}
