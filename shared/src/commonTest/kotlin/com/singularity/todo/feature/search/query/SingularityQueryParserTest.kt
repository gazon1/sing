package com.singularity.todo.feature.search.query

import com.singularity.todo.feature.search.query.Condition.And
import com.singularity.todo.feature.search.query.Condition.Due
import com.singularity.todo.feature.search.query.Condition.HasAllTags
import com.singularity.todo.feature.search.query.Condition.HasDescription
import com.singularity.todo.feature.search.query.Condition.HasPriority
import com.singularity.todo.feature.search.query.Condition.HasStatus
import com.singularity.todo.feature.search.query.Condition.HasTag
import com.singularity.todo.feature.search.query.Condition.HasText
import com.singularity.todo.feature.search.query.Condition.InProject
import com.singularity.todo.feature.search.query.Condition.IsArchived
import com.singularity.todo.feature.search.query.Condition.IsPinned
import com.singularity.todo.feature.search.query.Condition.Not
import com.singularity.todo.feature.search.query.Condition.Or
import com.singularity.todo.feature.search.query.Condition.Scheduled
import com.singularity.todo.feature.search.query.Relation.GT
import com.singularity.todo.feature.search.query.Relation.LE
import com.singularity.todo.feature.search.query.SortOrder
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

@Tag("fast")
class SingularityQueryParserTest {

    private fun parse(input: String): Query = SingularityQueryParser(input).parse()

    companion object {
        @JvmStatic
        fun conditionCases(): List<Arguments> = listOf(
            // ── Bare text ────────────────────────────────────────────────────
            Arguments.of("", null),
            Arguments.of("   ", null),
            Arguments.of("meeting", HasText("meeting")),
            Arguments.of("\"quarterly review\"", HasText("quarterly review")),
            // ── Status ───────────────────────────────────────────────────────
            Arguments.of("state:active", HasStatus(TaskStatus.Active)),
            Arguments.of("state:completed", HasStatus(TaskStatus.Completed)),
            Arguments.of("state:all", HasStatus(TaskStatus.All)),
            Arguments.of("STATE:DONE", HasStatus(TaskStatus.Completed)),
            Arguments.of("-state:completed", Not(HasStatus(TaskStatus.Completed))),
            // ── Priority ─────────────────────────────────────────────────────
            Arguments.of("priority:none", HasPriority(TaskPriority.None)),
            Arguments.of("priority:low", HasPriority(TaskPriority.Low)),
            Arguments.of("priority:medium", HasPriority(TaskPriority.Medium)),
            Arguments.of("priority:high", HasPriority(TaskPriority.High)),
            Arguments.of("priority:urgent", HasPriority(TaskPriority.Urgent)),
            Arguments.of("-priority:high", Not(HasPriority(TaskPriority.High))),
            // ── Tags ─────────────────────────────────────────────────────────
            Arguments.of("tag:work", HasTag("work")),
            Arguments.of("-tag:work", Not(HasTag("work"))),
            Arguments.of("tags:work,urgent,home", HasAllTags(setOf("work", "urgent", "home"))),
            Arguments.of("-tags:work,urgent", Not(HasAllTags(setOf("work", "urgent")))),
            // ── Project ──────────────────────────────────────────────────────
            Arguments.of("project:Plans", InProject("Plans")),
            // ── Due date ─────────────────────────────────────────────────────
            Arguments.of("due:today", Due(QueryInterval(0), LE)),
            Arguments.of("due:3d", Due(QueryInterval(3), LE)),
            Arguments.of("due:-1w", Due(QueryInterval(-7), LE)),
            Arguments.of("due:>3d", Due(QueryInterval(3), GT)),
            Arguments.of("due:tomorrow", Due(QueryInterval(1), LE)),
            Arguments.of("due:yesterday", Due(QueryInterval(-1), LE)),
            // ── Scheduled ────────────────────────────────────────────────────
            Arguments.of("scheduled:today", Scheduled(QueryInterval(0), LE)),
            Arguments.of("scheduled:-2d", Scheduled(QueryInterval(-2), LE)),
            // ── Has description ──────────────────────────────────────────────
            Arguments.of("has:description", HasDescription),
            // ── Special flags ────────────────────────────────────────────────
            Arguments.of("pinned", IsPinned),
            Arguments.of("archived", IsArchived),
            // ── Boolean combinators ──────────────────────────────────────────
            Arguments.of(
                "priority:high tag:work",
                And(listOf(HasPriority(TaskPriority.High), HasTag("work"))),
            ),
            Arguments.of(
                "priority:high AND tag:work",
                And(listOf(HasPriority(TaskPriority.High), HasTag("work"))),
            ),
            Arguments.of(
                "a AND b OR c",
                Or(listOf(And(listOf(HasText("a"), HasText("b"))), HasText("c"))),
            ),
            Arguments.of(
                "a OR b AND c",
                Or(listOf(HasText("a"), And(listOf(HasText("b"), HasText("c"))))),
            ),
            Arguments.of("NOT tag:archived", Not(HasTag("archived"))),
            Arguments.of(
                "(priority:high OR priority:urgent) AND tag:work",
                And(
                    listOf(
                        Or(listOf(HasPriority(TaskPriority.High), HasPriority(TaskPriority.Urgent))),
                        HasTag("work"),
                    ),
                ),
            ),
        )

        @JvmStatic
        fun sortCases(): List<Arguments> = listOf(
            Arguments.of("priority:high sort:priority", SortOrder.PRIORITY, false),
            Arguments.of("sort:due desc", SortOrder.DUE, true),
            Arguments.of("tag:work sort:title", SortOrder.TITLE, false),
            Arguments.of("meeting", SortOrder.DUE, false),
        )

        @JvmStatic
        fun limitCases(): List<Arguments> = listOf(
            Arguments.of("limit:20", 20),
            Arguments.of("limit:5000", 1000),
            Arguments.of("meeting", 50),
        )

        @JvmStatic
        fun wholeQueryCases(): List<Arguments> = listOf(
            Arguments.of(
                "priority:high tag:work project:Plans due:today sort:priority",
                Query(
                    condition = And(
                        listOf(
                            HasPriority(TaskPriority.High),
                            HasTag("work"),
                            InProject("Plans"),
                            Due(QueryInterval(0), LE),
                        ),
                    ),
                    sortOrder = SortOrder.PRIORITY,
                    options = Options(limit = 50),
                ),
            ),
            Arguments.of(
                "-tag:archived pinned",
                Query(
                    condition = And(listOf(Not(HasTag("archived")), IsPinned)),
                ),
            ),
        )

        @JvmStatic
        fun emptyCases(): List<Arguments> = listOf(
            Arguments.of("", true),
            Arguments.of("   ", true),
            Arguments.of("meeting", false),
        )
    }

    @ParameterizedTest
    @MethodSource("conditionCases")
    fun `input parses to condition`(input: String, expected: Condition?) {
        assertEquals(expected, parse(input).condition, input)
    }

    @ParameterizedTest
    @MethodSource("sortCases")
    fun `sort directive is parsed`(input: String, expectedOrder: SortOrder, expectedDescending: Boolean) {
        val q = parse(input)
        assertEquals(expectedOrder, q.sortOrder, input)
        assertEquals(expectedDescending, q.sortDescending, input)
    }

    @ParameterizedTest
    @MethodSource("limitCases")
    fun `limit option is parsed`(input: String, expectedLimit: Int) {
        assertEquals(expectedLimit, parse(input).options.limit, input)
    }

    @ParameterizedTest
    @MethodSource("wholeQueryCases")
    fun `whole query is parsed`(input: String, expected: Query) {
        assertEquals(expected, parse(input), input)
    }

    @ParameterizedTest
    @MethodSource("emptyCases")
    fun `query emptiness is reported`(input: String, expected: Boolean) {
        assertEquals(expected, parse(input).isEmpty, input)
    }
}
