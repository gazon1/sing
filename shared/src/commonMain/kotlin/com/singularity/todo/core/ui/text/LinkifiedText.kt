package com.singularity.todo.core.ui.text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow

/**
 * Text whose links the user can tap.
 *
 * The text is scanned for links — web addresses and the app's own — and each one
 * becomes tappable. A web link is handed to [rememberAppUriHandler], which opens it
 * outside the app. An internal link is handed to [onInternalLink], which stays
 * inside. The text is selectable: a person who wants to copy a sentence that
 * contains a link should not have to give up the link to do it.
 *
 * When [onClick] is provided, a tap on plain text invokes it while a tap on a link
 * still opens or navigates that link. The split is Compose's own: each link is laid
 * over its range as a child that takes the tap for itself, so the text's
 * [clickable] never sees a tap that landed on a link.
 *
 * @param text the text to show.
 * @param modifier applied to the selection container: the layout of the block as a
 *   whole. A `weight` belongs here, because it resolves against the parent row only
 *   while it is on the row's own child.
 * @param textModifier applied to the text itself — its test tag, its semantics, its
 *   identity. A tag here names the node a test or a flow reads the text from.
 * @param style the text style.
 * @param color the text colour.
 * @param maxLines the maximum number of lines.
 * @param overflow how to render text that does not fit.
 * @param onClick what a tap on plain text does, or null for text that is only read.
 * @param onInternalLink what to do with an internal link. Defaults to the handler
 *   provided by [LocalInternalLinkHandler].
 */
@Composable
fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    textModifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onClick: (() -> Unit)? = null,
    onInternalLink: InternalLinkHandler = LocalInternalLinkHandler.current,
) {
    val spans = remember(text) { TextLinkDetector.detect(text) }
    val handler by rememberUpdatedState(onInternalLink)
    val uriHandler = rememberAppUriHandler(onInternalLink)
    val primary = MaterialTheme.colorScheme.primary
    val linkStyles =
        remember(primary) {
            TextLinkStyles(style = SpanStyle(color = primary, textDecoration = TextDecoration.Underline))
        }
    val annotated =
        remember(text, spans, linkStyles, uriHandler) {
            buildLinkifiedAnnotated(
                text = text,
                spans = spans,
                linkStyles = linkStyles,
                onWeb = { uriHandler.openUri(it) },
                onInternal = { handler(it) },
            )
        }
    SelectionContainer(modifier = modifier) {
        Text(
            text = annotated ?: AnnotatedString(text),
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            modifier = if (onClick == null) textModifier else textModifier.clickable(onClick = onClick),
        )
    }
}

/**
 * The text with each detected link attached to its range.
 *
 * Every link carries its own interaction listener: a link without one is a coloured
 * range that does nothing when tapped, and an accessibility service is handed that
 * same nothing. Null when the text has no links, so the caller draws plain text.
 */
private fun buildLinkifiedAnnotated(
    text: String,
    spans: List<TextLinkSpan>,
    linkStyles: TextLinkStyles,
    onWeb: (String) -> Unit,
    onInternal: (TextLink.Internal) -> Unit,
): AnnotatedString? {
    if (spans.isEmpty()) return null
    return buildAnnotatedString {
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
                            linkInteractionListener = { onWeb(link.url) },
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
                            linkInteractionListener = { onInternal(link) },
                        ),
                        start,
                        end,
                    )
                }
            }
        }
    }
}
