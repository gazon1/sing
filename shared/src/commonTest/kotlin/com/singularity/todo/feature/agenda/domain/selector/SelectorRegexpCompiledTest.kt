package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * `Selector.Regexp` memoises its compiled pattern.
 *
 * The memo is a body property, so it must stay invisible to everything that
 * treats a selector as a *value* — equality, copying, and the serialised form.
 * Those are what a saved agenda view depends on, and a memo that leaked into
 * any of them would silently change how stored rules compare.
 */
@Tag("fast")
class SelectorRegexpCompiledTest {

    private val today = LocalDate(2026, 1, 1)
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun task(title: String) = Task(
        id = TaskId("t-${title.hashCode()}"),
        title = title,
        createdAt = now,
        updatedAt = now,
        userId = UserId("u"),
    )

    // ─── Matching is unchanged ────────────────────────────────────────────────

    @Test
    fun `matching is case-insensitive and unanchored, as before`() {
        val selector = Selector.Regexp("weekly")
        assertTrue(selector.matches(task("Weekly review"), today))
        assertTrue(selector.matches(task("a weekly thing"), today))
        assertFalse(selector.matches(task("monthly review"), today))
    }

    @Test
    fun `anchors still work`() {
        assertTrue(Selector.Regexp("^Weekly").matches(task("Weekly review"), today))
        assertFalse(Selector.Regexp("^Weekly").matches(task("Sprint Weekly"), today))
    }

    @Test
    fun `a real user pattern matches across many titles`() {
        val selector = Selector.Regexp("^(fix|feat|refactor)\\s+\\S+")
        assertTrue(selector.matches(task("fix the sync retry"), today))
        assertTrue(selector.matches(task("FEAT agenda filter"), today))
        assertFalse(selector.matches(task("chore: tidy imports"), today))
    }

    // ─── The memo is a memo, not state ─────────────────────────────────────────

    @Test
    fun `two selectors with the same query are equal`() {
        // Exercised *after* both have compiled, so the memo exists on each.
        val a = Selector.Regexp("^Weekly")
        val b = Selector.Regexp("^Weekly")
        assertTrue(a.matches(task("Weekly x"), today))
        assertTrue(b.matches(task("Weekly x"), today))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different queries are still unequal`() {
        assertFalse(Selector.Regexp("^Weekly") == Selector.Regexp("^Monthly"))
    }

    @Test
    fun `copy keeps the same query and discards nothing else`() {
        val original = Selector.Regexp("^Weekly")
        original.matches(task("Weekly x"), today)
        assertEquals(original, original.copy(query = "^Weekly"))
    }

    @Test
    fun `compiling does not change what a stored selector encodes to`() {
        // A saved agenda view round-trips through JSON; the memo must not appear in it.
        val original = Selector.Regexp("^Weekly")
        original.matches(task("Weekly x"), today)
        val encoded = com.singularity.todo.core.serialization.StableJson.encodeToString(
            Selector.Regexp.serializer(),
            original,
        )
        assertFalse(encoded.contains("compiled"), "the memo must not be serialised: $encoded")
    }

    @Test
    fun `a deserialized selector still matches`() {
        val original = Selector.Regexp("^Weekly")
        original.matches(task("Weekly x"), today)
        val encoded = com.singularity.todo.core.serialization.StableJson.encodeToString(
            Selector.Regexp.serializer(),
            original,
        )
        val decoded = com.singularity.todo.core.serialization.StableJson.decodeFromString(
            Selector.Regexp.serializer(),
            encoded,
        )
        assertEquals(original, decoded)
        assertTrue(decoded.matches(task("Weekly y"), today), "a fresh instance must compile on demand")
    }
}
