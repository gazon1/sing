package com.singularity.todo.feature.notes.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
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
import androidx.compose.ui.graphics.Color
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
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.feature.notes.EditorAction

/**
 * Pure extension that maps an [EditorAction] onto a [RichTextState].
 * Pulled out of the Composable so the same `when`-table can be unit-tested
 * directly with a real [RichTextState] (no UI runtime required).
 */
internal fun RichTextState.apply(action: EditorAction): RichTextState =
    when (action) {
        EditorAction.Bold -> apply { toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }

        EditorAction.Italic -> apply { toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }

        EditorAction.Underline -> apply { toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }

        EditorAction.Strike -> apply { toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }

        EditorAction.Code -> apply { toggleCodeSpan() }

        EditorAction.H1 -> apply { setHeadingStyle(HeadingStyle.H1) }

        EditorAction.H2 -> apply { setHeadingStyle(HeadingStyle.H2) }

        EditorAction.H3 -> apply { setHeadingStyle(HeadingStyle.H3) }

        EditorAction.Bullet -> apply { toggleUnorderedList() }

        EditorAction.Ordered -> apply { toggleOrderedList() }

        // Quote: the richeditor library has no first-class blockquote toggle.
        // Blockquotes are supported in HTML round-trip but require explicit HTML editing.
        EditorAction.Quote -> this

        EditorAction.ExternalLink -> this

        // handled via dialog, not toolbar
        EditorAction.InternalLink -> this

        // handled via dialog, not toolbar
        EditorAction.AlignLeft -> apply { toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.Start)) }

        EditorAction.AlignCenter -> apply { toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.Center)) }

        EditorAction.AlignRight -> apply { toggleParagraphStyle(ParagraphStyle(textAlign = TextAlign.End)) }
    }

/** Pure predicate that mirrors the visual "active" state for each toolbar button. */
internal fun RichTextState.isActive(action: EditorAction): Boolean =
    when (action) {
        EditorAction.Bold -> {
            currentSpanStyle.fontWeight?.let { it >= FontWeight.Bold } ?: false
        }

        EditorAction.Italic -> {
            currentSpanStyle.fontStyle == FontStyle.Italic
        }

        EditorAction.Underline -> {
            currentSpanStyle.textDecoration?.contains(TextDecoration.Underline) ?: false
        }

        EditorAction.Strike -> {
            currentSpanStyle.textDecoration?.contains(TextDecoration.LineThrough) ?: false
        }

        EditorAction.Code -> {
            isCodeSpan
        }

        EditorAction.H1 -> {
            currentHeadingStyle == HeadingStyle.H1
        }

        EditorAction.H2 -> {
            currentHeadingStyle == HeadingStyle.H2
        }

        EditorAction.H3 -> {
            currentHeadingStyle == HeadingStyle.H3
        }

        EditorAction.Bullet -> {
            isUnorderedList
        }

        EditorAction.Ordered -> {
            isOrderedList
        }

        EditorAction.Quote -> {
            false
        }

        // no blockquote state in library
        EditorAction.ExternalLink -> {
            isLink
        }

        EditorAction.InternalLink -> {
            false
        }

        EditorAction.AlignLeft -> {
            currentParagraphStyle.textAlign == TextAlign.Start ||
                currentParagraphStyle.textAlign == TextAlign.Left
        }

        EditorAction.AlignCenter -> {
            currentParagraphStyle.textAlign == TextAlign.Center
        }

        EditorAction.AlignRight -> {
            currentParagraphStyle.textAlign == TextAlign.End
        }
    }

/**
 * One entry in the toolbar.
 *
 * [glyph] is a short word drawn where the icon would be. The icon set has one
 * picture for "a heading" and no H1/H2/H3, so all three levels drew the same
 * `Title` and were indistinguishable in the menu; a glyph names the level
 * instead. [icon] stays the picture for everything else — and the row's picture,
 * because only actions the icon set can draw sit on the row.
 */
internal data class ToolbarButton(
    val action: EditorAction,
    val icon: ImageVector,
    val label: String,
    val glyph: String? = null,
)

/**
 * The picture and the spoken label for an action — the one place the toolbar
 * decides how an action looks. Exhaustive, so an action cannot reach the bar
 * without a glyph and a name.
 *
 * [EditorAction.Quote] is mapped for exhaustiveness but never rendered: the
 * library has no blockquote toggle, so neither list offers it.
 */
internal fun toolbarButtonFor(action: EditorAction): ToolbarButton =
    when (action) {
        EditorAction.Bold -> {
            ToolbarButton(action, Icons.Filled.FormatBold, "Bold")
        }

        EditorAction.Italic -> {
            ToolbarButton(action, Icons.Filled.FormatItalic, "Italic")
        }

        EditorAction.Underline -> {
            ToolbarButton(action, Icons.Filled.FormatUnderlined, "Underline")
        }

        EditorAction.Strike -> {
            ToolbarButton(action, Icons.Filled.FormatStrikethrough, "Strikethrough")
        }

        EditorAction.H1 -> {
            ToolbarButton(action, Icons.Filled.Title, "Heading 1", glyph = "H1")
        }

        EditorAction.H2 -> {
            ToolbarButton(action, Icons.Filled.Title, "Heading 2", glyph = "H2")
        }

        EditorAction.H3 -> {
            ToolbarButton(action, Icons.Filled.Title, "Heading 3", glyph = "H3")
        }

        EditorAction.Bullet -> {
            ToolbarButton(action, Icons.AutoMirrored.Filled.FormatListBulleted, "Bullet list")
        }

        EditorAction.Ordered -> {
            ToolbarButton(action, Icons.Filled.FormatListNumbered, "Numbered list")
        }

        EditorAction.Code -> {
            ToolbarButton(action, Icons.Filled.Code, "Inline code")
        }

        EditorAction.Quote -> {
            ToolbarButton(action, Icons.Filled.FormatQuote, "Blockquote")
        }

        EditorAction.AlignLeft -> {
            ToolbarButton(action, Icons.AutoMirrored.Filled.FormatAlignLeft, "Align left")
        }

        EditorAction.AlignCenter -> {
            ToolbarButton(action, Icons.Filled.FormatAlignCenter, "Align center")
        }

        EditorAction.AlignRight -> {
            ToolbarButton(action, Icons.AutoMirrored.Filled.FormatAlignRight, "Align right")
        }

        EditorAction.ExternalLink -> {
            ToolbarButton(action, Icons.Filled.Link, "External link")
        }

        EditorAction.InternalLink -> {
            ToolbarButton(action, Icons.Filled.Link, "Internal link")
        }
    }

/** The row, from [EditorAction.primary]. */
private val primaryButtons: List<ToolbarButton> = EditorAction.primary.map(::toolbarButtonFor)

/** The menu, from [EditorAction.overflow]. */
private val overflowButtons: List<ToolbarButton> = EditorAction.overflow.map(::toolbarButtonFor)

/**
 * The picture at the start of an overflow row: the level word for a heading, the
 * icon for everything else.
 */
@Composable
private fun ToolbarGlyph(
    button: ToolbarButton,
    tint: Color,
) {
    val glyph = button.glyph
    if (glyph == null) {
        Icon(button.icon, contentDescription = null, tint = tint)
    } else {
        Text(
            text = glyph,
            color = tint,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(24.dp),
        )
    }
}

/** The tint that says whether an action is currently applied to the selection. */
@Composable
private fun activeActionTint(isActive: Boolean): Color =
    if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

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
        modifier =
            modifier
                .fillMaxWidth()
                // Three formatting buttons plus Undo, Redo, the menu and AI are
                // seven 48 dp targets — 336 dp — which is what a 360 dp phone can
                // show at once. The scroll is what keeps that true on a narrower
                // window or a larger text scale, where the buttons no longer fit.
                .horizontalScroll(rememberScrollState())
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
                colors = IconButtonDefaults.iconButtonColors(contentColor = activeActionTint(isActive)),
            ) {
                Icon(
                    button.icon,
                    contentDescription = button.label,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Undo / Redo — enabled only when the library's history has something to
        // move. A fresh note has nothing, and a button that looked live but did
        // nothing read as broken rather than unavailable.
        val canUndo = richTextState.history.canUndo
        IconButton(
            onClick = { richTextState.history.undo() },
            enabled = canUndo,
            colors =
                IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo",
                modifier = Modifier.size(22.dp),
            )
        }
        val canRedo = richTextState.history.canRedo
        IconButton(
            onClick = { richTextState.history.redo() },
            enabled = canRedo,
            colors =
                IconButtonDefaults.iconButtonColors(
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
            colors =
                IconButtonDefaults.iconButtonColors(
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
            // Renders in its own popup window — mapTestTagsAsResourceIds required
            // so Maestro id: selectors can find items inside the menu.
            modifier = Modifier.mapTestTagsAsResourceIds(),
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
                        ToolbarGlyph(button = button, tint = activeActionTint(isActive))
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
