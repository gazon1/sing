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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import com.singularity.todo.feature.notes.EditorState

/**
 * Title field + rich text body editor. State owners are the parent — the body
 * only adapts the rich-text widget to the current [EditorState.Editing] snapshot.
 */
@OptIn(com.mohamedrejeb.richeditor.ui.material3.ExperimentalRichTextEditorApi::class)
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

    // Sync external HTML → rich text widget only when they diverge (avoids loops).
    LaunchedEffect(state.html) {
        val currentHtml = richTextState.toHtml()
        if (currentHtml != state.html) richTextState.setHtml(state.html)
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            colors = RichTextEditorDefaults.richTextEditorColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
            placeholder = { Text("Start writing...") },
        )
    }
}
