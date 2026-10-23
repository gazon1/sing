@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.search.query

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.TEST_TZ
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.LocalDate
import com.singularity.todo.core.search.SearchMode

@Tag("fast")
class SearchQueryResolverTest {

    // ─── Inline fakes ─────────────────────────────────────────────────────────

    private val tagStore = mutableMapOf<String, FakeTagEntity>()
    private val projectStore = mutableMapOf<String, FakeProjectEntity>()

    private val fakeTagLookup = object : TagLookup {
        override suspend fun findByName(userId: String, name: String): TagLookupResult? = tagStore.values.firstOrNull {
            it.userId == userId && it.name.equals(name, ignoreCase = true)
        }?.let { TagLookupResult(it.id, it.userId, it.name) }
    }

    private val fakeProjectLookup = object : ProjectLookup {
        override suspend fun findByName(userId: String, name: String): ProjectLookupResult? =
            projectStore.values.firstOrNull {
                it.userId == userId && it.name.equals(name, ignoreCase = true)
            }?.let { ProjectLookupResult(it.id, it.userId, it.name) }
    }

    // A fixed clock and zone, so a `due:` condition resolves to a date this file
    // can name. The resolver had no way to supply either before #91: it read the
    // system clock, so any assertion about "today" would have been a claim about
    // the day the suite ran.
    private val clock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))
    private val resolver = DefaultSearchQueryResolver(
        fakeTagLookup,
        fakeProjectLookup,
        clock,
        TEST_TZ,
    )
    private val userId = "user_1"

    private data class FakeTagEntity(val id: String, val userId: String, val name: String)
    private data class FakeProjectEntity(val id: String, val userId: String, val name: String)

    private fun addTag(name: String) {
        tagStore["tag_${tagStore.size}"] = FakeTagEntity("tag_${tagStore.size}", userId, name)
    }

    private fun addProject(name: String) {
        projectStore["proj_${projectStore.size}"] = FakeProjectEntity("proj_${projectStore.size}", userId, name)
    }

    private fun task(id: String, title: String): Task = Task(
        id = TaskId(id),
        title = title,
        createdAt = Instant.parse("2026-09-16T10:00:00Z"),
        updatedAt = Instant.parse("2026-09-16T10:00:00Z"),
        userId = UserId(userId),
    )

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `empty query returns null filter`() = runTest {
        val resolved = resolver.resolve(Query.EMPTY, userId, SearchMode.Word)
        assertNull(resolved.taskFilter)
        assertNull(resolved.dateRange)
        assertTrue(resolved.freeText.isNullOrBlank())
    }

    @Test
    fun `HasText accumulates freeText`() = runTest {
        val query = SingularityQueryParser("hello world").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertEquals("hello world", resolved.freeText)
        assertNull(resolved.taskFilter)
    }

    @Test
    fun `free text terms become the rule every kind is matched by`() = runTest {
        val query = SingularityQueryParser("hello world").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)

        assertEquals(listOf("hello", "world"), resolved.freeTextRule.terms.map { it.text })
        assertTrue(resolved.freeTextRule.terms.none { it.negated })
        assertFalse(resolved.freeTextRule.combinesWithOr, "a conjunction is not a disjunction")
    }

    @Test
    fun `an OR query makes the free text terms alternatives`() = runTest {
        val query = SingularityQueryParser("hello OR world").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)

        assertTrue(resolved.freeTextRule.combinesWithOr)
    }

    @Test
    fun `a query with no free text has no rule`() = runTest {
        val query = SingularityQueryParser("priority:high").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)

        assertTrue(resolved.freeTextRule.isEmpty)
    }

    @Test
    fun `unknown tag name is tracked as unknown`() = runTest {
        addTag("Work")

        val query = SingularityQueryParser("tag:work tag:unknown").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertTrue(resolved.unknownTagNames.contains("unknown"), "unknown tag should be tracked")
        assertTrue(resolved.resolvedTagIds.contains("tag_0"), "resolved tag should be tracked")
    }

    @Test
    fun `resolved tag builds ByTag filter`() = runTest {
        addTag("Work")

        val query = SingularityQueryParser("tag:Work").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertIs<TaskFilter.ByTag>(resolved.taskFilter)
        assertEquals("tag_0", resolved.taskFilter.id.value)
    }

    @Test
    fun `multiple resolved tags builds ByTags matchAll filter`() = runTest {
        addTag("Work")
        addTag("Home")

        val query = SingularityQueryParser("tag:Work tag:Home").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertIs<TaskFilter.ByTags>(resolved.taskFilter)
        assertTrue(resolved.taskFilter.matchAll)
        assertTrue(resolved.resolvedTagIds.contains("tag_0"))
        assertTrue(resolved.resolvedTagIds.contains("tag_1"))
    }

    @Test
    fun `unknown project name is tracked as unknown`() = runTest {
        addProject("Plans")

        val query = SingularityQueryParser("project:Plans project:Missing").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertTrue(resolved.unknownProjectNames.contains("Missing"))
        assertEquals("proj_0", resolved.resolvedProjectId)
    }

    @Test
    fun `resolved project name builds ByProject filter`() = runTest {
        addProject("Plans")

        val query = SingularityQueryParser("project:Plans").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertIs<TaskFilter.ByProject>(resolved.taskFilter)
        assertEquals("proj_0", resolved.taskFilter.id.value)
    }

    @Test
    fun `sort and options are passed through`() = runTest {
        val query = Query(
            condition = Condition.HasText("test"),
            sortOrder = SortOrder.PRIORITY,
            sortDescending = true,
            options = Options(limit = 20, offset = 5),
        )

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertEquals(SortOrder.PRIORITY, resolved.sortOrder)
        assertTrue(resolved.sortDescending)
        assertEquals(20, resolved.options.limit)
        assertEquals(5, resolved.options.offset)
    }

    @Test
    fun `pinned condition sets needsPostFilter`() = runTest {
        val query = SingularityQueryParser("pinned").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertTrue(resolved.needsPostFilter)
        assertNotNull(resolved.postFilter)
    }

    @Test
    fun `archived condition sets needsPostFilter`() = runTest {
        val query = SingularityQueryParser("archived").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertTrue(resolved.needsPostFilter)
    }

    @Test
    fun `has description condition sets needsPostFilter`() = runTest {
        val query = SingularityQueryParser("has:description").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertTrue(resolved.needsPostFilter)
    }

    @Test
    fun `OR condition sets isOrPostFilter`() = runTest {
        val query = SingularityQueryParser("priority:high OR priority:urgent").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertTrue(resolved.isOrPostFilter)
        assertTrue(resolved.needsPostFilter)
    }

    @Test
    fun `AND condition does NOT set isOrPostFilter`() = runTest {
        val query = SingularityQueryParser("priority:high tag:work").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertTrue(resolved.needsPostFilter)
        assertTrue(resolved.isOrPostFilter == false)
    }

    @Test
    fun `negated tag sets needsPostFilter`() = runTest {
        addTag("Work")

        val query = SingularityQueryParser("-tag:Work").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        assertTrue(resolved.needsPostFilter)
    }

    // ── The free-text predicate of the resolved query ─────────────────────────
    //
    // The predicate the resolver hands to the use case is what the *rule* says
    // about a candidate — and a candidate that came from the index has already
    // been matched against the positive terms. The predicate is therefore about
    // the negated terms alone, and the whole rule only where the index could not
    // answer at all.

    @Test
    fun `the free-text predicate of a narrowed rule does not ask again about the positives`() = runTest {
        val query = SingularityQueryParser("alpha").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        val unrelated = task(id = "t1", title = "Nothing to do with the word")

        val kept = resolved.postFilter(sequenceOf(unrelated)).map { it.id.value }.toList()

        assertEquals(
            listOf("t1"),
            kept,
            "the positives are the index's business — the rule must not filter them a second time",
        )
    }

    @Test
    fun `the free-text predicate of a narrowed rule still holds the negated term`() = runTest {
        val query = SingularityQueryParser("alpha NOT beta").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        val carrying = task(id = "t1", title = "Alpha and Beta")
        val other = task(id = "t2", title = "Alpha only")

        val kept = resolved.postFilter(sequenceOf(carrying, other)).map { it.id.value }.toList()

        assertEquals(listOf("t2"), kept, "the negated term is the rule's business")
    }

    @Test
    fun `the free-text predicate of a broad rule holds the whole rule`() = runTest {
        // A rule the index cannot answer — one that says only what a record must
        // *not* carry — is answered by the rule alone.
        val query = SingularityQueryParser("NOT alpha").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)
        val carrying = task(id = "t1", title = "Alpha report")
        val other = task(id = "t2", title = "Beta report")

        val kept = resolved.postFilter(sequenceOf(carrying, other)).map { it.id.value }.toList()

        assertEquals(listOf("t2"), kept)
    }

    // ── Date determinism (#91) ─────────────────────────────────────────────────
    //
    // The resolver resolved a `due:` condition against the system clock, so any
    // assertion about it was a claim about the day the suite ran — which is why
    // this file had no date test at all despite the parser having eleven. The
    // clock and zone are now supplied, and these are the first assertions about a
    // resolved date in the repository.

    @Test
    fun `due today resolves against the injected clock, not the host`() = runTest {
        val query = SingularityQueryParser("due:today").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)

        // FakeClock is pinned to 2026-09-16T10:00:00Z and TEST_TZ is UTC, so the
        // bound is 2026-09-16 regardless of when this runs. Before the injection
        // there was no way to write this expectation down at all.
        //
        // `to`, not `from`: a bare `due:` means LE ("on or before"), and an LE
        // range is open-ended below — `from` is 1970-01-01 by definition. The first
        // version of this test asserted `from` and read 1970-01-01, which is
        // correct behaviour and a wrong expectation; the date the clock controls
        // is the upper bound.
        assertEquals(LocalDate(2026, 9, 16), resolved.dateRange?.to)
    }

    @Test
    fun `a relative due date is a fixed number of days from the injected today`() = runTest {
        val query = SingularityQueryParser("due:3d").parse()

        val resolved = resolver.resolve(query, userId, SearchMode.Word)

        assertEquals(
            LocalDate(2026, 9, 19),
            resolved.dateRange?.to,
            "due:3d is three days from the injected today, and nothing else",
        )
    }

    @Test
    fun `the resolved date follows the clock`() = runTest {
        // The other half of the same property. The first version of this test
        // advanced the clock and asserted the date did *not* move, which would
        // have passed on code that ignored the clock entirely — the exact failure
        // #91 describes, certified as a success. Asserting the opposite makes the
        // injection load-bearing: a resolver still reading the system clock would
        // not move 3 days in a suite that runs in seconds.
        val query = SingularityQueryParser("due:today").parse()

        val before = resolver.resolve(query, userId, SearchMode.Word).dateRange?.to
        clock.advance(3.days)
        val after = resolver.resolve(query, userId, SearchMode.Word).dateRange?.to

        assertEquals(LocalDate(2026, 9, 16), before)
        assertEquals(LocalDate(2026, 9, 19), after, "the resolved date must follow the injected clock")
    }
}
