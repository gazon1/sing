package com.singularity.todo.feature.notes.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.singularity.todo.feature.notes.EditorAction

/**
 * Pure extension that maps an [EditorAction] onto a [RichTextState]. Pulled out
 * of the Composable so the same `when`-table can be unit-tested directly with a
 * real `RichTextState` (no UI runtime required).
 */
internal fun RichTextState.apply(action: EditorAction): RichTextState = when (action.key) {
    "bold" -> apply { toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
    "italic" -> apply { toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
    "underline" -> apply { toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
    "strike" -> apply { toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }
    "h1" -> apply { setHeadingStyle(HeadingStyle.H1) }
    "h2" -> apply { setHeadingStyle(HeadingStyle.H2) }
    "h3" -> apply { setHeadingStyle(HeadingStyle.H3) }
    "bullet" -> apply { toggleUnorderedList() }
    "ordered" -> apply { toggleOrderedList() }
    "code" -> apply { toggleCodeSpan() }
    "quote", "link" -> this // not wired to UI yet
    else -> this
}

/** Pure predicate that mirrors the visual "active" state for each toolbar button. */
internal fun RichTextState.isActive(action: EditorAction): Boolean = when (action.key) {
    "bold" -> currentSpanStyle.fontWeight?.let { it >= FontWeight.Bold } ?: false
    "italic" -> currentSpanStyle.fontStyle == FontStyle.Italic
    "underline" -> currentSpanStyle.textDecoration?.contains(TextDecoration.Underline) ?: false
    "strike" -> currentSpanStyle.textDecoration?.contains(TextDecoration.LineThrough) ?: false
    "h1" -> currentHeadingStyle == HeadingStyle.H1
    "h2" -> currentHeadingStyle == HeadingStyle.H2
    "h3" -> currentHeadingStyle == HeadingStyle.H3
    "bullet" -> isUnorderedList
    "ordered" -> isOrderedList
    "code" -> isCodeSpan
    else -> false
}

/** One row in the editor toolbar. Pure value, easy to extend or reorder. */
internal data class ToolbarButton(
    val action: EditorAction,
    val icon: ImageVector,
    val label: String,
)

private val buttons = listOf(
    ToolbarButton(EditorAction.Bold, Icons.Filled.FormatBold, "Bold"),
    ToolbarButton(EditorAction.Italic, Icons.Filled.FormatItalic, "Italic"),
    ToolbarButton(EditorAction.Underline, Icons.Filled.FormatUnderlined, "Underline"),
    ToolbarButton(EditorAction.Strike, Icons.Filled.FormatStrikethrough, "Strike"),
    ToolbarButton(EditorAction.H1, Icons.Filled.Title, "Heading 1"),
    ToolbarButton(EditorAction.Bullet, Icons.Filled.FormatListBulleted, "Bullet list"),
    ToolbarButton(EditorAction.Ordered, Icons.Filled.FormatListNumbered, "Numbered list"),
    ToolbarButton(EditorAction.Code, Icons.Filled.Code, "Code"),
)

/**
 * Formatting toolbar. The toolbar itself is stateless — every button click
 * routes through [RichTextState.apply] and then [onHtmlChange] so the parent
 * stays in control of when (and how) the new HTML is persisted.
 */
@Composable
fun EditorToolbar(
    richTextState: RichTextState,
    onHtmlChange: () -> Unit,
    onAiClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        buttons.forEach { button ->
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
            ) { Icon(button.icon, contentDescription = button.label) }
        }
        IconButton(onClick = onAiClick) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = "AI improve",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
