package com.singularity.todo.feature.notes.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.singularity.todo.feature.notes.EditorAction

/**
 * Pure extension that maps an [EditorAction] onto a [RichTextState].
 * Pulled out of the Composable so the same `when`-table can be unit-tested
 * directly with a real [RichTextState] (no UI runtime required).
 */
internal fun RichTextState.apply(action: EditorAction): RichTextState = when (action) {
    EditorAction.Bold        -> apply { toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
    EditorAction.Italic      -> apply { toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
    EditorAction.Underline   -> apply { toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
    EditorAction.Strike      -> apply { toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }
    EditorAction.Code        -> apply { toggleCodeSpan() }
    EditorAction.H1          -> apply { setHeadingStyle(HeadingStyle.H1) }
    EditorAction.H2          -> apply { setHeadingStyle(HeadingStyle.H2) }
    EditorAction.H3          -> apply { setHeadingStyle(HeadingStyle.H3) }
    EditorAction.Bullet      -> apply { toggleUnorderedList() }
    EditorAction.Ordered     -> apply { toggleOrderedList() }
    // Quote: the richeditor library has no first-class blockquote toggle.
    // Blockquotes are supported in HTML round-trip but require explicit HTML editing.
    EditorAction.Quote       -> this
    EditorAction.ExternalLink -> this  // handled via dialog, not toolbar
    EditorAction.InternalLink -> this  // handled via dialog, not toolbar
    EditorAction.AlignLeft   -> apply { toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.Start)) }
    EditorAction.AlignCenter -> apply { toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.Center)) }
    EditorAction.AlignRight  -> apply { toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.End)) }
}

/** Pure predicate that mirrors the visual "active" state for each toolbar button. */
internal fun RichTextState.isActive(action: EditorAction): Boolean = when (action) {
    EditorAction.Bold        -> currentSpanStyle.fontWeight?.let { it >= FontWeight.Bold } ?: false
    EditorAction.Italic      -> currentSpanStyle.fontStyle == FontStyle.Italic
    EditorAction.Underline   -> currentSpanStyle.textDecoration?.contains(TextDecoration.Underline) ?: false
    EditorAction.Strike      -> currentSpanStyle.textDecoration?.contains(TextDecoration.LineThrough) ?: false
    EditorAction.Code        -> isCodeSpan
    EditorAction.H1          -> currentHeadingStyle == HeadingStyle.H1
    EditorAction.H2          -> currentHeadingStyle == HeadingStyle.H2
    EditorAction.H3          -> currentHeadingStyle == HeadingStyle.H3
    EditorAction.Bullet      -> isUnorderedList
    EditorAction.Ordered     -> isOrderedList
    EditorAction.Quote       -> false  // no blockquote state in library
    EditorAction.ExternalLink -> isLink
    EditorAction.InternalLink -> false
    EditorAction.AlignLeft   -> currentParagraphStyle.textAlign == TextAlign.Start || currentParagraphStyle.textAlign == TextAlign.Left
    EditorAction.AlignCenter -> currentParagraphStyle.textAlign == TextAlign.Center
    EditorAction.AlignRight  -> currentParagraphStyle.textAlign == TextAlign.End
}

/** One button in the toolbar. */
private data class ToolbarButton(
    val action: EditorAction,
    val icon: ImageVector,
    val label: String,
)

private val primaryButtons = listOf(
    ToolbarButton(EditorAction.Bold,        Icons.Filled.FormatBold,                              "Bold"),
    ToolbarButton(EditorAction.Italic,      Icons.Filled.FormatItalic,                            "Italic"),
    ToolbarButton(EditorAction.Underline,   Icons.Filled.FormatUnderlined,                        "Underline"),
    ToolbarButton(EditorAction.Strike,      Icons.Filled.FormatStrikethrough,                     "Strikethrough"),
    ToolbarButton(EditorAction.H1,          Icons.Filled.Title,                                  "Heading 1"),
    ToolbarButton(EditorAction.Bullet,      Icons.AutoMirrored.Filled.FormatListBulleted,        "Bullet list"),
    ToolbarButton(EditorAction.Ordered,     Icons.Filled.FormatListNumbered,                      "Numbered list"),
)

private val overflowButtons = listOf(
    ToolbarButton(EditorAction.H2,           Icons.Filled.Title,                                  "Heading 2"),
    ToolbarButton(EditorAction.H3,           Icons.Filled.Title,                                  "Heading 3"),
    ToolbarButton(EditorAction.Code,          Icons.Filled.Code,                                   "Inline code"),
    ToolbarButton(EditorAction.AlignLeft,    Icons.Filled.FormatAlignLeft,                        "Align left"),
    ToolbarButton(EditorAction.AlignCenter,  Icons.Filled.FormatAlignCenter,                      "Align center"),
    ToolbarButton(EditorAction.AlignRight,   Icons.Filled.FormatAlignRight,                       "Align right"),
    ToolbarButton(EditorAction.ExternalLink, Icons.Filled.Link,                                  "External link"),
    ToolbarButton(EditorAction.InternalLink, Icons.Filled.Link,                                  "Internal link"),
)

/**
 * Sticky formatting toolbar for the rich-text editor.
 * Stateless — every button click routes through [RichTextState.apply] and then
 * [onHtmlChange] so the parent stays in control of when (and how) the new HTML
 * is persisted.
 *
 * Placed in [androidx.compose.material3.Scaffold.bottomBar] by [com.singularity.todo.feature.notes.NoteEditorScreenContent].
 */
@Composable
fun EditorToolbar(
    richTextState: RichTextState,
    onHtmlChange: () -> Unit,
    onAiClick: () -> Unit,
    onLinkClick: () -> Unit,
    onInternalLinkClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overflowExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        // Primary formatting buttons
        primaryButtons.forEach { button ->
            val isActive = richTextState.isActive(button.action)
            IconButton(
                onClick = {
                    richTextState.apply(button.action)
                    onHtmlChange()
                },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    button.icon,
                    contentDescription = button.label,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Undo / Redo
        IconButton(
            onClick = { richTextState.history.undo() },
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo",
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(
            onClick = { richTextState.history.redo() },
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Redo,
                contentDescription = "Redo",
                modifier = Modifier.size(22.dp),
            )
        }

        // Overflow menu
        IconButton(
            onClick = { overflowExpanded = true },
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "More formatting options",
                modifier = Modifier.size(22.dp),
            )
        }

        DropdownMenu(
            expanded = overflowExpanded,
            onDismissRequest = { overflowExpanded = false },
        ) {
            overflowButtons.forEach { button ->
                val isActive = richTextState.isActive(button.action)
                DropdownMenuItem(
                    text = { Text(button.label) },
                    onClick = {
                        when (button.action) {
                            is EditorAction.ExternalLink -> {
                                overflowExpanded = false
                                onLinkClick()
                            }
                            is EditorAction.InternalLink -> {
                                overflowExpanded = false
                                onInternalLinkClick()
                            }
                            else -> {
                                richTextState.apply(button.action)
                                onHtmlChange()
                            }
                        }
                    },
                    leadingIcon = {
                        Icon(
                            button.icon,
                            contentDescription = null,
                            tint = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        // AI improve button (spacer)
        IconButton(onClick = onAiClick) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = "AI improve",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
