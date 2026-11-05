package com.singularity.todo.core.seed

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The seed handler knows its own keys.
 *
 * A seed link is written by hand, in a flow, and one of its keys — the one
 * that makes a task *blocked* — changes the meaning of the seed rather than
 * the shape of it. A misspelling there produces a task that looks right and
 * carries no blocker, and the symptom (an empty bucket) is several steps away
 * from the cause (a key the handler never read). These tests hold the rule
 * that makes that visible: a key the handler does not know is reported, by
 * name, and everything else the query carries is seeded all the same.
 */
@Tag("fast")
class SeedQueryTest {
    @Test
    fun `a key the handler does not know is reported`() {
        val report =
            SeedQuery.inspect(
                queryKeys = listOf("task", "dependsOn"),
                usableKeys = setOf("task"),
            )

        assertEquals(listOf("dependsOn"), report.unknownKeys)
        assertTrue(
            report.messages().any { it.contains("dependsOn") },
            "the finding has to name the key it is about",
        )
    }

    @Test
    fun `a known key with an empty value is not an unknown key`() {
        val report =
            SeedQuery.inspect(
                queryKeys = listOf("task", "blockedBy"),
                usableKeys = setOf("task"),
            )

        assertTrue(report.unknownKeys.isEmpty())
        assertFalse(
            report.messages().any { it.contains("Unknown") },
            "an empty value is not a misspelling",
        )
    }

    @Test
    fun `the handler's own control keys are not unknown keys`() {
        val report =
            SeedQuery.inspect(
                queryKeys = listOf("reset", "cleanup", "session"),
                usableKeys = setOf("reset"),
            )

        assertTrue(report.unknownKeys.isEmpty())
        assertFalse(report.seedsNothing)
    }

    @Test
    fun `a title named by a key is not itself a key`() {
        val report =
            SeedQuery.inspect(
                queryKeys = listOf("note", "linkTo", "blockedBy"),
                usableKeys = setOf("note"),
            )

        assertTrue(report.unknownKeys.isEmpty())
    }

    @Test
    fun `a query that names no known key says that nothing was seeded`() {
        val report =
            SeedQuery.inspect(
                queryKeys = listOf("dependsOn"),
                usableKeys = emptySet(),
            )

        assertEquals(listOf("dependsOn"), report.unknownKeys)
        assertTrue(report.seedsNothing)
        assertTrue(
            report.messages().any { it.contains("nothing was seeded") },
            "a query that seeds nothing has to say so",
        )
    }

    @Test
    fun `a query that names an entity does not say that nothing was seeded`() {
        val report =
            SeedQuery.inspect(
                queryKeys = listOf("task"),
                usableKeys = setOf("task"),
            )

        assertFalse(report.seedsNothing)
        assertTrue(report.messages().isEmpty())
    }
}
