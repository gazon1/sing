package com.singularity.todo.core.ui.text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow

/**
 * Text whose links the user can tap.
 *
 * ## What it does
 *
 * The text is scanned for links — web addresses and the app's own — and each one
 * becomes tappable. A web link is handed to [rememberAppUriHandler], which opens it
 * outside the app. An internal link is handed to [onInternalLink], which stays
 * inside.
 *
 * The text is also selectable: a person who wants to copy a sentence that contains
 * a link should not have to give up the link to do it.
 *
 * ## When there are no links
 *
 * The text is rendered as plain text. There is no annotation to build and no link to
 * style, and the result is exactly what a plain `Text` would show.
 *
 * @param text the text to show.
 * @param modifier applied to the selection container.
 * @param style the text style.
 * @param color the text colour.
 * @param maxLines maximum number of lines.
 * @param overflow how visual overflow is handled.
 * @param onClick called when text without a link is tapped.
 * @param onInternalLink what to do with an internal link. Defaults to the handler
 *   provided by [LocalInternalLinkHandler].
 */
@Composable
fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onClick: (() -> Unit)? = null,
    onInternalLink: InternalLinkHandler = LocalInternalLinkHandler.current,
) {
    val spans = remember(text) { TextLinkDetector.detect(text) }
    val handler by rememberUpdatedState(onInternalLink)
    val clickHandler by rememberUpdatedState(onClick)
    val uriHandler = rememberAppUriHandler(onInternalLink)
    val primary = MaterialTheme.colorScheme.primary
    val linkStyles =
        remember(primary) {
            TextLinkStyles(style = SpanStyle(color = primary, textDecoration = TextDecoration.Underline))
        }
    val annotated =
        remember(text, spans, linkStyles, uriHandler, handler, onClick == null) {
            if (spans.isEmpty()) {
                null
            } else {
                buildAnnotatedString {
                    append(text)
                    for (span in spans) {
                        val start = span.range.first
                        val end = span.range.last + 1
                        when (val link = span.link) {
                            is TextLink.Web -> {
                                addLink(
                                    LinkAnnotation.Url(
                                        url = link.url,
                                        styles = linkStyles,
                                        linkInteractionListener = {
                                            if (onClick == null) uriHandler.openUri(link.url)
                                        },
                                    ),
                                    start,
                                    end,
                                )
                            }

                            is TextLink.Internal -> {
                                addLink(
                                    LinkAnnotation.Clickable(
                                        tag = link.target.url,
                                        styles = linkStyles,
                                        linkInteractionListener = {
                                            if (onClick == null) handler(link)
                                        },
                                    ),
                                    start,
                                    end,
                                )
                            }
                        }
                    }
                }
            }
        }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val textModifier =
        when {
            onClick == null -> Modifier
            spans.isEmpty() -> Modifier.clickable { clickHandler?.invoke() }
            else ->
                Modifier.pointerInput(text, spans) {
                    detectTapGestures { pos ->
                        val layout = layoutResult ?: return@detectTapGestures
                        val offset =
                            layout
                                .getOffsetForPosition(pos)
                                .coerceIn(0, (text.length - 1).coerceAtLeast(0))
                        val span = spans.firstOrNull { offset in it.range }
                        when (val link = span?.link) {
                            is TextLink.Web -> uriHandler.openUri(link.url)
                            is TextLink.Internal -> handler(link)
                            null -> clickHandler?.invoke()
                        }
                    }
                }
        }
    SelectionContainer(modifier = modifier) {
        if (annotated == null) {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = maxLines,
                overflow = overflow,
                onTextLayout = { layoutResult = it },
                modifier = textModifier,
            )
        } else {
            Text(
                text = annotated,
                style = style,
                color = color,
                maxLines = maxLines,
                overflow = overflow,
                onTextLayout = { layoutResult = it },
                modifier = textModifier,
            )
        }
    }
}
