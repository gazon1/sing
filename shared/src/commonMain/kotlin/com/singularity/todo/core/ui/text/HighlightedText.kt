package com.singularity.todo.core.ui.text

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.singularity.todo.core.search.TextMatchRange
import kotlinx.collections.immutable.ImmutableList

/**
 * The text, with the matched spans emphasised.
 *
 * ## Where the span comes from
 *
 * The span is the index's answer, not a guess: the index holds the tokenizer
 * and it knows which column of the record matched, so it can say where in the
 * text the word is. The range is in characters, and it is in the units of the
 * string the screen is about to draw — so the span lands on the word and not
 * next to it.
 *
 * @param text the text to draw.
 * @param ranges where the match is, in the characters of [text].
 * @param style the style the match is drawn with.
 */
fun highlightedText(text: String, ranges: ImmutableList<TextMatchRange>, style: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(text)
        ranges.forEach { range ->
            val start = range.start.coerceIn(0, text.length)
            val end = range.end.coerceIn(start, text.length)
            if (end > start) addStyle(style, start, end)
        }
    }

/**
 * The text, with the matched spans emphasised in the theme's colours.
 *
 * The emphasis is a tint and a heavier weight: the word has to stand out in a
 * list of titles, and it has to stay readable — so the colour of the text is
 * the colour of the text, and only the ground under it changes.
 *
 * @param text the text to draw.
 * @param ranges where the match is, in the characters of [text].
 */
@Composable
fun highlightedText(text: String, ranges: ImmutableList<TextMatchRange>): AnnotatedString {
    val style = SpanStyle(
        fontWeight = FontWeight.SemiBold,
        background = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
    )
    return highlightedText(text, ranges, style)
}
