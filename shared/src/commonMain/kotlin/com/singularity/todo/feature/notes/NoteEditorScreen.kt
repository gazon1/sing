package com.singularity.todo.feature.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteId: String?,
    onBack: () -> Unit,
    viewModel: NotesViewModel = koinInject()
) {
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var aiResultText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(noteId) {
        if (noteId != null) {
            viewModel.openEditor(noteId)
        } else {
            viewModel.createNote()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.aiResult.collectLatest { result ->
            aiResultText = when (result) {
                is NoteAiResult.Improved -> "Note improved!\n\nTitle: ${result.title}"
                is NoteAiResult.Error -> "Error: ${result.message}"
            }
        }
    }

    aiResultText?.let { result ->
        AlertDialog(
            onDismissRequest = { aiResultText = null },
            title = { Text("AI Result") },
            text = { Text(result) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { aiResultText = null }) {
                    Text("OK")
                }
            }
        )
    }

    NoteEditorScreenContent(
        editorState = editorState,
        onTitleChange = { id, title -> viewModel.editTitle(id, title) },
        onBodyChange = { id, html -> viewModel.editBody(id, html) },
        onBack = {
            viewModel.closeEditor()
            onBack()
        },
        onAiClick = { viewModel.improveNote() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreenContent(
    editorState: EditorState,
    onTitleChange: (id: String, title: String) -> Unit,
    onBodyChange: (id: String, html: String) -> Unit,
    onBack: () -> Unit,
    onAiClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (editorState) {
            EditorState.Empty -> CircularProgressIndicator(
                modifier = Modifier.padding(padding)
            )

            is EditorState.Saving -> CircularProgressIndicator(
                modifier = Modifier.padding(padding)
            )

            is EditorState.Error -> Text(
                text = "Error: ${editorState.message}",
                modifier = Modifier.padding(padding),
                color = MaterialTheme.colorScheme.error
            )

            is EditorState.Editing -> EditorBody(
                state = editorState,
                modifier = Modifier.padding(padding),
                onTitleChange = onTitleChange,
                onBodyChange = onBodyChange,
                onAiClick = onAiClick
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorBody(
    state: EditorState.Editing,
    modifier: Modifier = Modifier,
    onTitleChange: (id: String, title: String) -> Unit,
    onBodyChange: (id: String, html: String) -> Unit,
    onAiClick: () -> Unit
) {
    val richTextState = remember { RichTextState() }
    var titleFieldValue by remember(state.id) {
        mutableStateOf(TextFieldValue(state.title))
    }

    LaunchedEffect(state.html) {
        val currentHtml = richTextState.toHtml()
        if (currentHtml != state.html) {
            richTextState.setHtml(state.html)
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true
        )

        HorizontalDivider()

        EditorToolbar(
            richTextState = richTextState,
            onHtmlChange = { onBodyChange(state.id, richTextState.toHtml()) },
            onAiClick = onAiClick
        )

        RichTextEditor(
            state = richTextState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            colors = RichTextEditorDefaults.richTextEditorColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            placeholder = { Text("Start writing...") }
        )
    }
}

@Composable
private fun EditorToolbar(
    richTextState: RichTextState,
    onHtmlChange: () -> Unit,
    onAiClick: () -> Unit
) {
    val toolbarActions = listOf(
        ToolbarButton(EditorAction.Bold, Icons.Filled.FormatBold, "Bold") {
            richTextState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)); onHtmlChange()
        },
        ToolbarButton(EditorAction.Italic, Icons.Filled.FormatItalic, "Italic") {
            richTextState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)); onHtmlChange()
        },
        ToolbarButton(EditorAction.Underline, Icons.Filled.FormatUnderlined, "Underline") {
            richTextState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)); onHtmlChange()
        },
        ToolbarButton(EditorAction.Strike, Icons.Filled.FormatStrikethrough, "Strike") {
            richTextState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)); onHtmlChange()
        },
        ToolbarButton(EditorAction.H1, Icons.Filled.Title, "Heading 1") {
            richTextState.setHeadingStyle(HeadingStyle.H1); onHtmlChange()
        },
        ToolbarButton(EditorAction.Bullet, Icons.Filled.FormatListBulleted, "Bullet list") {
            richTextState.toggleUnorderedList(); onHtmlChange()
        },
        ToolbarButton(EditorAction.Ordered, Icons.Filled.FormatListNumbered, "Numbered list") {
            richTextState.toggleOrderedList(); onHtmlChange()
        },
        ToolbarButton(EditorAction.Code, Icons.Filled.Code, "Code") {
            richTextState.toggleCodeSpan(); onHtmlChange()
        }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        toolbarActions.forEach { button ->
            val isActive = isActionActive(richTextState, button.action)
            IconButton(
                onClick = button.onClick,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (isActive)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(button.icon, button.label)
            }
        }
        // AI improve button at the end
        IconButton(onClick = onAiClick) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = "AI improve",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private data class ToolbarButton(
    val action: EditorAction,
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

private fun isActionActive(state: RichTextState, action: EditorAction): Boolean {
    return when (action) {
        EditorAction.Bold -> state.currentSpanStyle.fontWeight?.let { it >= FontWeight.Bold } ?: false
        EditorAction.Italic -> state.currentSpanStyle.fontStyle == FontStyle.Italic
        EditorAction.Underline -> state.currentSpanStyle.textDecoration?.contains(TextDecoration.Underline) ?: false
        EditorAction.Strike -> state.currentSpanStyle.textDecoration?.contains(TextDecoration.LineThrough) ?: false
        EditorAction.H1 -> state.currentHeadingStyle == HeadingStyle.H1
        EditorAction.H2 -> state.currentHeadingStyle == HeadingStyle.H2
        EditorAction.H3 -> state.currentHeadingStyle == HeadingStyle.H3
        EditorAction.Bullet -> state.isUnorderedList
        EditorAction.Ordered -> state.isOrderedList
        EditorAction.Quote -> false // Not supported by this library
        EditorAction.Code -> state.isCodeSpan
        EditorAction.Link -> state.isLink
        else -> false
    }
}
