package com.singularity.todo.feature.search

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.search.SearchColumn
import com.singularity.todo.core.search.TextMatchRange
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.TaskCard
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.semanticsRoots
import com.singularity.todo.test.helpers.visit
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Desktop automation for scenario `SEARCH-09` — see
 * `scripts/src/traceability/scenarios/search/SEARCH-09.yaml`.
 *
 * The `@DisplayName` above is the linkage. The id is the **first token** of the
 * string, and that strictness is the contract: the traceability scanner rejects
 * an id anywhere else rather than guessing.
 *
 * ## What this test does and does not claim
 *
 * It covers the **drawing**: the result card draws the span the index
 * reported, over the characters the index named, and a range that belongs to
 * another column of the record is not drawn in the title.
 *
 * It does not cover the index's own answer — which bytes of which column the
 * match is in. That is asserted against a real SQLite in `FtsMatchOffsetsTest`,
 * because the fake index has no tokenizer to read a position from and a rule in
 * Kotlin that guessed the position would be a second answer to a question only
 * the index can answer.
 *
 * The card it draws is the card the search screen draws: the rows of a search
 * result are ordinary task cards, and the ranges arrive with them.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class SearchMatchHighlightScenarioTest {

    @Test
    @DisplayName("SEARCH-09 the result shows the word that was matched")
    fun the_result_shows_the_word_that_was_matched() = runIsolatedComposeTest {
        val task = task("Architecture of the house")

        setContent {
            SingularityTheme(themeMode = ThemeMode.Light) {
                TaskCard(
                    task = task,
                    onClick = {},
                    matchRanges = listOf(TextMatchRange(SearchColumn.TITLE, 0, 12)),
                )
            }
        }

        val title = textShown(task.title)

        val span = title.spanStyles.single()
        assertEquals(0, span.start, "the match starts at the first character of the title")
        assertEquals(12, span.end, "the match covers the word the index reported")
    }

    @Test
    fun `a match that belongs to another column is not drawn in the title`() = runIsolatedComposeTest {
        val task = task("The plan")

        setContent {
            SingularityTheme(themeMode = ThemeMode.Light) {
                TaskCard(
                    task = task,
                    onClick = {},
                    matchRanges = listOf(TextMatchRange(SearchColumn.DESCRIPTION, 0, 12)),
                )
            }
        }

        val title = textShown(task.title)

        assertTrue(
            title.spanStyles.isEmpty(),
            "the title does not carry the word, so nothing is drawn in it: ${title.spanStyles}",
        )
    }

    /**
     * The text the scene shows, as the scene holds it.
     *
     * The text of a node is an [AnnotatedString], and the spans are the match:
     * the emphasis is not a colour that a test could read, it is a range over
     * the characters — so the range is what the test looks at.
     */
    private fun textShown(text: String): AnnotatedString {
        var found: AnnotatedString? = null
        for (root in semanticsRoots()) {
            root.visit { node ->
                if (found == null) {
                    val shown = node.config.getOrNull(SemanticsProperties.Text)
                    found = shown?.firstOrNull { it.text == text }
                }
            }
            if (found != null) break
        }
        return found ?: error("the scene shows no text '$text'")
    }

    /** A task with the given title — the card under test is the card the search draws. */
    private fun task(title: String): Task = Task(
        id = TaskId("t-search-match"),
        title = title,
        createdAt = Instant.fromEpochMilliseconds(1_760_000_000_000),
        updatedAt = Instant.fromEpochMilliseconds(1_760_000_000_000),
        userId = UserId("u1"),
    )
}
