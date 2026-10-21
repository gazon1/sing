package com.singularity.todo.feature.search.query

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.TEST_TZ
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The tag conditions of the query language — §4 of the grammar.
 *
 * ## Why these rules have their own file
 *
 * A tag condition is the one place where the *shape* of the AST is part of what the
 * query means. `tag:work tag:urgent` is one condition that asks for both tags, not
 * two conditions that happen to be joined — and the difference is visible to anything
 * that compares queries, which is what a saved search does (C1).
 *
 * The other half is what an unresolvable name means (C3). A name that resolves to
 * nothing is not an error and it is not a filter that is quietly dropped: it
 * resolves to no rows.
 *
 * @see <a href="../../../../../../../docs/specs/search-query-grammar.md">the grammar</a>
 */
@Tag("fast")
class SearchQueryTagConditionsTest {

    // ─── C1 — the accumulating condition ───────────────────────────────────────

    @Test
    fun `repeated tag words are one accumulating condition`() {
        val condition = SingularityQueryParser("tag:work tag:urgent").parse().condition

        val accumulating = assertIs<Condition.HasAllTags>(condition)
        assertEquals(setOf("work", "urgent"), accumulating.tagNames)
    }

    @Test
    fun `a single tag word is a single tag condition`() {
        val condition = SingularityQueryParser("tag:work").parse().condition

        val single = assertIs<Condition.HasTag>(condition)
        assertEquals("work", single.tagName)
    }

    @Test
    fun `an explicit tag list and a tag word accumulate together`() {
        val condition = SingularityQueryParser("tags:work,urgent tag:home").parse().condition

        val accumulating = assertIs<Condition.HasAllTags>(condition)
        assertEquals(setOf("work", "urgent", "home"), accumulating.tagNames)
    }

    @Test
    fun `a negated tag is not accumulated with the tags beside it`() {
        val condition = SingularityQueryParser("-tag:work tag:urgent").parse().condition

        val conjunction = assertIs<Condition.And>(condition)
        assertIs<Condition.Not>(conjunction.parts[0])
        assertIs<Condition.HasTag>(conjunction.parts[1])
    }

    @Test
    fun `tags in a disjunction are not accumulated`() {
        val condition = SingularityQueryParser("tag:work OR tag:urgent").parse().condition

        val disjunction = assertIs<Condition.Or>(condition)
        assertEquals(2, disjunction.parts.size, "either tag, not both")
        assertIs<Condition.HasTag>(disjunction.parts[0])
        assertIs<Condition.HasTag>(disjunction.parts[1])
    }

    // ─── C3 — an unresolvable name ─────────────────────────────────────────────

    @Test
    fun `an unresolvable name in an accumulating condition matches no rows`() = runTest {
        val resolver = resolver(resolving = setOf("work"))
        val query = SingularityQueryParser("tag:work tag:missing").parse()

        val resolved = resolver.resolve(query, USER_ID)

        assertTrue(resolved.unknownTagNames.contains("missing"), "the name that resolves to nothing is tracked")
        assertTrue(resolved.resolvedTagIds.contains("tag_work"), "the name that resolved is tracked too")
        assertFalse(
            resolved.postFilter(sequenceOf(task(tags = listOf(TagId("tag_work"))))).any(),
            "a task carrying only the tag that resolved must not match: the condition asks for both",
        )
    }

    @Test
    fun `an accumulating condition whose names all resolve matches the tasks that carry them`() = runTest {
        val resolver = resolver(resolving = setOf("work", "urgent"))
        val query = SingularityQueryParser("tag:work tag:urgent").parse()

        val resolved = resolver.resolve(query, USER_ID)

        assertTrue(
            resolved.postFilter(sequenceOf(task(tags = listOf(TagId("tag_work"), TagId("tag_urgent"))))).any(),
            "a task carrying both tags matches",
        )
        assertFalse(
            resolved.postFilter(sequenceOf(task(tags = listOf(TagId("tag_work"))))).any(),
            "a task carrying one of the two tags does not",
        )
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /** The id the resolver gives to the tag named `work`, and to every other one. */
    private fun idFor(name: String): String = "tag_" + name.lowercase()

    /**
     * A resolver whose tag lookup answers for [resolving] and for nothing else.
     *
     * A name outside the set is what C3 is about: the query still parses, and the
     * name resolves to nothing.
     */
    private fun resolver(resolving: Set<String>): DefaultSearchQueryResolver {
        val lookup = FakeTagLookup(resolving.map { it to idFor(it) }.toMap())
        return DefaultSearchQueryResolver(
            tagLookup = lookup,
            projectLookup = FakeProjectLookup(),
            clock = FakeClock(Instant.parse("2026-09-16T10:00:00Z")),
            timeZone = TEST_TZ,
        )
    }

    /** A task carrying the given tags, and nothing else that matters here. */
    private fun task(tags: List<TagId>): Task = Task(
        id = TaskId("task_1"),
        title = "A task",
        createdAt = Instant.parse("2026-09-16T10:00:00Z"),
        updatedAt = Instant.parse("2026-09-16T10:00:00Z"),
        userId = UserId.fromString(USER_ID),
        tags = tags,
    )

    /** A tag lookup that answers for the names it was given. */
    private class FakeTagLookup(private val known: Map<String, String>) : TagLookup {
        override suspend fun findByName(userId: String, name: String): TagLookupResult? {
            val id = known[name] ?: return null
            return TagLookupResult(id = id, userId = userId, name = name)
        }
    }

    /** A project lookup that knows no projects. */
    private class FakeProjectLookup : ProjectLookup {
        override suspend fun findByName(userId: String, name: String): ProjectLookupResult? = null
    }

    private companion object {
        /** The user the queries are resolved for. */
        const val USER_ID = "user_1"
    }
}
