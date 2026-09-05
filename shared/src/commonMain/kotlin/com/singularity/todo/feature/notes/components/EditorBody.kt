package com.singularity.todo.feature.notes.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import com.singularity.todo.feature.notes.EditorState
import com.singularity.todo.core.ui.TestTags

/**
 * Title field + rich text body editor. State owners are the parent — the body
 * only adapts the rich-text widget to the current [EditorState.Editing] snapshot.
 * After [EditorState.Editing] is set (once per session), the rich text widget
 * owns its content — no external [LaunchedEffect] overwrites it.
 *
 * Body changes are dispatched via a [LaunchedEffect] that observes the
 * [RichTextState.annotatedString]. This ensures every keystroke is reported
 * to the ViewModel, fixing the bug where the toolbar's onHtmlChange only
 * fired on formatting button clicks.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EditorBody(
    state: EditorState.Editing,
    onTitleChange: (id: String, title: String) -> Unit,
    onBodyChange: (id: String, html: String) -> Unit,
    onAiClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val richTextState = remember(state.id) { RichTextState() }
    var titleFieldValue by remember(state.id) { mutableStateOf(TextFieldValue(state.title)) }
    var lastDispatchedHtml by remember(state.id) { mutableStateOf("") }

    // Dispatch body changes to the ViewModel whenever the rich text state changes.
    // This is the key fix: EditorToolbar.onHtmlChange only fires on toolbar button
    // clicks, but this LaunchedEffect fires on every text mutation.
    LaunchedEffect(state.id, richTextState) {
        val html = richTextState.toHtml()
        if (html != lastDispatchedHtml) {
            lastDispatchedHtml = html
            onBodyChange(state.id, html)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = titleFieldValue,
            onValueChange = { newValue ->
                titleFieldValue = newValue
                onTitleChange(state.id, newValue.text)
            },
            placeholder = { Text("Title") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(TestTags.NOTE_EDITOR_TITLE_INPUT),
            singleLine = true,
        )
        HorizontalDivider()
        EditorToolbar(
            richTextState = richTextState,
            onHtmlChange = { onBodyChange(state.id, richTextState.toHtml()) },
            onAiClick = onAiClick,
        )
        RichTextEditor(
            state = richTextState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .testTag(TestTags.NOTE_EDITOR_BODY),
            colors = RichTextEditorDefaults.richTextEditorColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
            placeholder = { Text("Start writing...") },
        )
    }
}
