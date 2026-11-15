package com.singularity.todo.core.ui.text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
 * The text is scanned for links — web addresses and the app's own — and each one
 * becomes tappable. A web link is handed to [rememberAppUriHandler], which opens it
 * outside the app. An internal link is handed to [onInternalLink], which stays
 * inside. The text is selectable: a person who wants to copy a sentence that
 * contains a link should not have to give up the link to do it.
 *
 * When [onClick] is provided, taps on plain text invoke it while taps on links
 * still open/navigate the link.
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
    val onTextClickState = rememberUpdatedState(onClick)
    val internalHandlerState = rememberUpdatedState(onInternalLink)
    val uriHandlerState = rememberUpdatedState(rememberAppUriHandler(onInternalLink))
    val primary = MaterialTheme.colorScheme.primary
    val linkStyles = remember(primary) {
        TextLinkStyles(style = SpanStyle(color = primary, textDecoration = TextDecoration.Underline))
    }
    val dispatchLinksByAnnotation = onClick == null
    val annotated = remember(text, spans, linkStyles, dispatchLinksByAnnotation) {
        buildLinkifiedAnnotated(
            text = text,
            spans = spans,
            linkStyles = linkStyles,
            dispatchLinks = dispatchLinksByAnnotation,
            onWeb = { uriHandlerState.value.openUri(it) },
            onInternal = { internalHandlerState.value(it) },
        )
    }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val tapModifier = when {
        onClick == null -> Modifier
        spans.isEmpty() -> Modifier.clickable { onTextClickState.value?.invoke() }
        else ->
            Modifier
                .semantics {
                    onClick {
                        onTextClickState.value?.invoke()
                        true
                    }
                }
                .pointerInput(text, spans) {
                    detectLinkifiedTap(
                        layout = { layout },
                        spans = spans,
                        onWeb = { uriHandlerState.value.openUri(it) },
                        onInternal = { internalHandlerState.value(it) },
                        onText = { onTextClickState.value?.invoke() },
                    )
                }
    }
    SelectionContainer(modifier = modifier) {
        Text(
            text = annotated ?: AnnotatedString(text),
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = tapModifier,
        )
    }
}

private fun buildLinkifiedAnnotated(
    text: String,
    spans: List<TextLinkSpan>,
    linkStyles: TextLinkStyles,
    dispatchLinks: Boolean,
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
                is TextLink.Web -> addLink(
                    LinkAnnotation.Url(
                        url = link.url,
                        styles = linkStyles,
                        linkInteractionListener = if (dispatchLinks) ({ onWeb(link.url) }) else null,
                    ),
                    start,
                    end,
                )

                is TextLink.Internal -> addLink(
                    LinkAnnotation.Clickable(
                        tag = link.target.url,
                        styles = linkStyles,
                        linkInteractionListener = if (dispatchLinks) ({ onInternal(link) }) else null,
                    ),
                    start,
                    end,
                )
            }
        }
    }
}

private suspend fun PointerInputScope.detectLinkifiedTap(
    layout: () -> TextLayoutResult?,
    spans: List<TextLinkSpan>,
    onWeb: (String) -> Unit,
    onInternal: (TextLink.Internal) -> Unit,
    onText: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        if (!isQuickTap(down.position, up.position, up.uptimeMillis - down.uptimeMillis)) {
            return@awaitEachGesture
        }
        val result = layout() ?: return@awaitEachGesture
        val downLink = result.linkAt(down.position, spans)
        val upLink = result.linkAt(up.position, spans)
        when {
            downLink != null && downLink == upLink -> when (downLink) {
                is TextLink.Web -> onWeb(downLink.url)
                is TextLink.Internal -> onInternal(downLink)
            }

            downLink == null && upLink == null -> onText()
            else -> Unit
        }
    }
}

private fun TextLayoutResult.linkAt(
    position: Offset,
    spans: List<TextLinkSpan>,
): TextLink? {
    val plainText = layoutInput.text.text
    if (plainText.isEmpty() || lineCount == 0) return null
    if (position.y < 0f || position.y > size.height.toFloat()) return null
    val clampedY = position.y.coerceIn(0f, (size.height - 1).coerceAtLeast(0).toFloat())
    val line = getLineForVerticalPosition(clampedY).coerceIn(0, lineCount - 1)
    if (position.x < getLineLeft(line) || position.x > getLineRight(line)) return null
    val visibleEnd = getLineEnd(line, visibleEnd = true)
    if (visibleEnd <= 0) return null
    val rawOffset = getOffsetForPosition(position).coerceIn(0, plainText.length)
    val candidates = listOf(rawOffset, rawOffset - 1).distinct().filter { it in plainText.indices }
    for (offset in candidates) {
        if (offset >= visibleEnd) continue
        val span = spans.firstOrNull { offset in it.range } ?: continue
        val box = getBoundingBox(offset)
        if (
            position.x >= box.left &&
            position.x <= box.right &&
            position.y >= box.top &&
            position.y <= box.bottom
        ) {
            return span.link
        }
    }
    return null
}

private fun PointerInputScope.isQuickTap(
    start: Offset,
    end: Offset,
    durationMs: Long,
): Boolean =
    (end - start).getDistance() < viewConfiguration.touchSlop &&
        durationMs < viewConfiguration.longPressTimeoutMillis
