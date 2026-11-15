package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichText
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.text.StaticText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.rich.EditorSession
import com.singularity.todo.core.ui.rich.EditorToolbar
import com.singularity.todo.core.ui.rich.InternalLinkPickerSheet
import com.singularity.todo.core.ui.rich.LinkResult
import com.singularity.todo.core.ui.rich.LinkUrlDialog
import com.singularity.todo.core.ui.rich.urlFor
import com.singularity.todo.core.ui.text.SelectableTextGroup
import com.singularity.todo.core.ui.text.rememberAppUriHandler
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.feature.tasks.presentation.theme.placeholderTextColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.task_description_placeholder

/**
 * The task description, edited and rendered by the Compose Rich Editor, for the
 * detail's owned source.
 *
 * ## The source is HTML
 *
 * [source] carries the canonical HTML — the task's rich description, or its
 * legacy plain text escaped so it is read literally. The field never sees the
 * plain projection, which stays in `Task.description` for search and prompts.
 *
 * ## Document style
 *
 * In the detail the description is text until it is tapped, like the title.
 * That is what makes links work: the read-only `RichText` hands a tap on a link
 * to the app's URI handler, while the editable surface has no link tap. Create
 * uses the static overload and is always in edit mode.
 *
 * ## Why the kind is in the parameter
 *
 * The two overloads are the two answers to "who may rewrite the value?". The
 * shared implementation takes the text and the revision, not the sealed parent,
 * so the kind is decided at the call site, at compile time.
 *
 * @param taskId the identity the editor state is keyed to.
 * @param source the description as the owner stored it.
 * @param onDescriptionChange called with the editor's HTML when it changes.
 * @param alwaysEditable whether the field starts in edit mode.
 * @param onSearchInternalLinks the note/task search behind the internal-link
 *   picker. Null hides the internal-link action.
 */
@Composable
fun TaskDescriptionField(
    taskId: String,
    source: OwnedText,
    onDescriptionChange: (String) -> Unit,
    alwaysEditable: Boolean,
    modifier: Modifier = Modifier,
    onSearchInternalLinks: (suspend (String) -> List<LinkResult>)? = null,
) {
    TaskDescriptionField(
        taskId = taskId,
        text = source.text,
        revision = source.revision,
        onDescriptionChange = onDescriptionChange,
        alwaysEditable = alwaysEditable,
        modifier = modifier,
        onSearchInternalLinks = onSearchInternalLinks,
    )
}

/**
 * The task description for the create screen's static source.
 *
 * The upstream loads the text once — from the empty draft or a restored one —
 * and the field owns it afterwards. See the owned overload for the source and
 * document-style contracts.
 */
@Composable
fun TaskDescriptionField(
    taskId: String,
    source: StaticText,
    onDescriptionChange: (String) -> Unit,
    alwaysEditable: Boolean,
    modifier: Modifier = Modifier,
    onSearchInternalLinks: (suspend (String) -> List<LinkResult>)? = null,
) {
    TaskDescriptionField(
        taskId = taskId,
        text = source.text,
        revision = 0L,
        onDescriptionChange = onDescriptionChange,
        alwaysEditable = alwaysEditable,
        modifier = modifier,
        onSearchInternalLinks = onSearchInternalLinks,
    )
}

@Composable
private fun TaskDescriptionField(
    taskId: String,
    text: String,
    revision: Long,
    onDescriptionChange: (String) -> Unit,
    alwaysEditable: Boolean,
    modifier: Modifier,
    onSearchInternalLinks: (suspend (String) -> List<LinkResult>)?,
) {
    var hasUserEdited by remember(taskId) { mutableStateOf(false) }
    var seededText by remember(taskId) { mutableStateOf(text) }
    val session =
        rememberTaskDescriptionSession(taskId, text) { html ->
            hasUserEdited = true
            onDescriptionChange(html)
        }

    // A static source has no revision to say the value changed: a draft is
    // restored asynchronously, and the field can compose before the store hands
    // the text back. Until the user types, a source the field has not seeded yet
    // is the truth; after that the editor owns the content and the source is its
    // own echo. An owned source never needs this before its first edit, and its
    // rewrites arrive through the revision below.
    LaunchedEffect(taskId, text) {
        if (revision == 0L && !hasUserEdited && text != seededText) {
            session.replaceContent(text)
            seededText = text
        }
    }

    // An owned source says a replacement happened by moving its revision. A
    // keystroke never moves it, so the echo of our own save does not reset the
    // content the user is typing into.
    LaunchedEffect(taskId, revision) {
        if (revision == 0L) return@LaunchedEffect
        session.replaceContent(text)
    }

    // Dispatch every edit. The state object is the same instance for the whole
    // session, so the effect has to *read* it: `snapshotFlow` re-emits whenever
    // the text it reads changes.
    LaunchedEffect(taskId, session) {
        snapshotFlow { session.richTextState.annotatedString }
            .map { session.richTextState.toHtml() }
            .distinctUntilChanged()
            .collect { session.dispatchHtml() }
    }

    TaskDescriptionEditorBody(
        taskId = taskId,
        session = session,
        alwaysEditable = alwaysEditable,
        modifier = modifier,
        onSearchInternalLinks = onSearchInternalLinks,
    )
}

/** One editor state per task id; the source is the editor's starting content. */
@Composable
private fun rememberTaskDescriptionSession(
    taskId: String,
    initialHtml: String,
    onDescriptionChange: (String) -> Unit,
): EditorSession {
    val onDescriptionChangeState = rememberUpdatedState(onDescriptionChange)
    return remember(taskId) {
        val richTextState = RichTextState().also { it.setHtml(initialHtml) }
        EditorSession(
            richTextState = richTextState,
            // The baseline is the loaded content as the editor renders it, so the
            // load itself is not echoed back as an edit.
            lastDispatchedHtml = richTextState.toHtml(),
            onBodyChange = { _, html -> onDescriptionChangeState.value(html) },
            id = taskId,
        )
    }
}

@Composable
private fun TaskDescriptionEditorBody(
    taskId: String,
    session: EditorSession,
    alwaysEditable: Boolean,
    modifier: Modifier,
    onSearchInternalLinks: (suspend (String) -> List<LinkResult>)?,
) {
    var editing by remember(taskId, alwaysEditable) { mutableStateOf(alwaysEditable) }
    val focusRequester = remember { FocusRequester() }
    var focusRequested by remember { mutableStateOf(false) }
    var showExternalLink by remember { mutableStateOf(false) }
    var showInternalLink by remember { mutableStateOf(false) }

    LaunchedEffect(focusRequested) {
        if (focusRequested) {
            focusRequester.requestFocus()
            focusRequested = false
        }
    }

    val bodyStyle =
        MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    val enterEditing = {
        editing = true
        focusRequested = true
    }
    val editorModifier =
        Modifier
            .fillMaxWidth()
            .bringIntoViewOnFocus()
            .padding(vertical = TaskSpacing.sm)
            .focusRequester(focusRequester)
            .testTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT)
            .semantics { contentDescription = "Task description" }

    Column(modifier = modifier.fillMaxWidth()) {
        if (editing) {
            DescriptionEditorSurface(
                session = session,
                bodyStyle = bodyStyle,
                modifier = editorModifier,
                onFormatChange = { session.dispatchHtml() },
                onExternalLink = { showExternalLink = true },
                onInternalLink =
                    if (onSearchInternalLinks != null) {
                        { showInternalLink = true }
                    } else {
                        null
                    },
            )
        } else {
            DescriptionText(
                state = session.richTextState,
                bodyStyle = bodyStyle,
                onClick = enterEditing,
            )
        }
    }

    TaskDescriptionLinkDialogs(
        session = session,
        showExternalLink = showExternalLink,
        showInternalLink = showInternalLink,
        onSearchInternalLinks = onSearchInternalLinks,
        onExternalDismiss = { showExternalLink = false },
        onInternalDismiss = { showInternalLink = false },
    )
}

@Composable
private fun DescriptionEditorSurface(
    session: EditorSession,
    bodyStyle: TextStyle,
    modifier: Modifier,
    onFormatChange: () -> Unit,
    onExternalLink: () -> Unit,
    onInternalLink: (() -> Unit)?,
) {
    BasicRichTextEditor(
        state = session.richTextState,
        textStyle = bodyStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier,
        decorationBox = { innerTextField ->
            if (session.richTextState.annotatedString.text
                    .isEmpty()
            ) {
                Text(
                    text = stringResource(Res.string.task_description_placeholder),
                    color = placeholderTextColor(),
                    style = bodyStyle,
                )
            }
            innerTextField()
        },
    )

    EditorToolbar(
        richTextState = session.richTextState,
        onHtmlChange = onFormatChange,
        // The task editor has its own AI affordances; the toolbar's generic
        // "improve" action has nothing to call here.
        onAiClick = null,
        onLinkClick = onExternalLink,
        onInternalLinkClick = onInternalLink,
    )
}

@Composable
private fun TaskDescriptionLinkDialogs(
    session: EditorSession,
    showExternalLink: Boolean,
    showInternalLink: Boolean,
    onSearchInternalLinks: (suspend (String) -> List<LinkResult>)?,
    onExternalDismiss: () -> Unit,
    onInternalDismiss: () -> Unit,
) {
    if (showExternalLink) {
        LinkUrlDialog(
            onConfirm = { url ->
                session.insertLink(url = url, text = url)
                onExternalDismiss()
            },
            onDismiss = onExternalDismiss,
        )
    }

    val searchLinks = onSearchInternalLinks
    if (showInternalLink && searchLinks != null) {
        InternalLinkPickerSheet(
            onSearch = searchLinks,
            onSelected = { result ->
                session.insertLink(
                    url = result.kind.urlFor(result.id),
                    text = result.title.ifBlank { "(Untitled)" },
                )
                onInternalDismiss()
            },
            onDismiss = onInternalDismiss,
        )
    }
}

/**
 * The description as rendered text.
 *
 * The `RichText` reads link taps through `LocalUriHandler`; the app handler is
 * installed here so an internal link stays inside the app. A tap that is not on
 * a link falls through to [onClick] and enters editing.
 */
@Composable
private fun DescriptionText(
    state: RichTextState,
    bodyStyle: TextStyle,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalUriHandler provides rememberAppUriHandler()) {
        SelectableTextGroup {
            if (state.annotatedString.text.isEmpty()) {
                DescriptionPlaceholder(bodyStyle = bodyStyle, onClick = onClick)
            } else {
                RichText(
                    state = state,
                    style = bodyStyle,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = TaskSpacing.sm)
                            .clickable(onClick = onClick)
                            .testTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT)
                            .semantics { contentDescription = "Task description" },
                )
            }
        }
    }
}

@Composable
private fun DescriptionPlaceholder(
    bodyStyle: TextStyle,
    onClick: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.task_description_placeholder),
        color = placeholderTextColor(),
        style = bodyStyle,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = TaskSpacing.sm)
                .clickable(onClick = onClick)
                .testTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT)
                .semantics { contentDescription = "Task description" },
    )
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskDescriptionFieldPreview() =
    PreviewThemed(darkTheme = false) {
        TaskDescriptionField(
            taskId = "t1",
            source = StaticText("<p>Pick up the <strong>oat milk</strong>.</p>"),
            onDescriptionChange = {},
            alwaysEditable = false,
        )
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskDescriptionFieldEmptyPreview() =
    PreviewThemed(darkTheme = false) {
        TaskDescriptionField(
            taskId = "t1",
            source = StaticText(""),
            onDescriptionChange = {},
            alwaysEditable = true,
        )
    }
