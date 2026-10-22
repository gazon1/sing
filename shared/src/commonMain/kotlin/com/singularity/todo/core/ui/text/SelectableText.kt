package com.singularity.todo.core.ui.text

import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.LocalTextStyle

/**
 * Text the user can select and copy.
 *
 * ## Why the container is a component and not a habit
 *
 * `SelectionContainer` is a stateful thing: it owns a selection state and a toolbar.
 * Putting one around every `Text` in a list would give every row its own selection
 * manager, and the selection would not survive the list scrolling the row out of
 * view. Putting it around the *block* of text gives one container per block, which
 * is what a person means by "copy this paragraph".
 *
 * That is also why the container does not cross a lazy layout: a `LazyColumn` item
 * that is not composed is not in the copy, and no selection across rows is offered.
 *
 * @param text the text to show.
 * @param modifier applied to the selection container.
 * @param style the text style.
 * @param color the text colour.
 * @param maxLines the maximum number of lines.
 * @param overflow how to render text that does not fit.
 */
@Composable
fun SelectableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    SelectionContainer(modifier = modifier) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/**
 * Text the user can select and copy, with the matched spans emphasised.
 *
 * The same text as [SelectableText], with a range of it drawn differently — the
 * word the search found, in the result the search found it in.
 *
 * @param text the text to show.
 * @param modifier applied to the selection container.
 * @param style the text style.
 * @param color the text colour.
 * @param maxLines the maximum number of lines.
 * @param overflow how to render text that does not fit.
 */
@Composable
fun SelectableText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    SelectionContainer(modifier = modifier) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/**
 * Makes a group of texts selectable as one block.
 *
 * Use this when several texts belong together — a heading and the lines under it —
 * and the person should be able to select across them. Texts that should not join
 * the selection go inside [UnselectableContent].
 *
 * @param modifier applied to the selection container.
 * @param content the content that becomes selectable.
 */
@Composable
fun SelectableTextGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SelectionContainer(modifier = modifier, content = content)
}

/**
 * Takes an interactive child out of the surrounding selection.
 *
 * A button inside a selectable block would otherwise be swallowed by a long press
 * that is meant to start a selection. Wrapping it here keeps the button's own
 * gesture and gives the selection back to the text.
 *
 * A text that carries its own selection container keeps it: a nested container has
 * its own registrar, so wrapping *that* in here would not take its selection away.
 *
 * @param content the content that is no longer selectable.
 */
@Composable
fun UnselectableContent(content: @Composable () -> Unit) {
    DisableSelection(content = content)
}
