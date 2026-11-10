package com.singularity.todo.core.seed

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seed link keys: what they are and which of them remove instead of seed.
 *
 * Tag: `@Tag("fast")` — no coroutines, no clock, no repository, so it can never
 * outlive a second.
 */
@Tag("fast")
class SeedLinkKeysTest {
    @Test
    fun `the historical typo is unknown`() {
        assertEquals(
            listOf("dependsOn"),
            SeedLinkKeys.unknown(listOf(SeedLinkKeys.TASK, "dependsOn")),
        )
    }

    @Test
    fun `control keys are known`() {
        assertTrue(
            SeedLinkKeys
                .unknown(
                    listOf(SeedLinkKeys.RESET, SeedLinkKeys.CLEANUP, SeedLinkKeys.SESSION, SeedLinkKeys.COUNT),
                ).isEmpty(),
        )
    }

    @Test
    fun `delete keys are the two that remove instead of seed`() {
        assertEquals(
            setOf(SeedLinkKeys.RESET, SeedLinkKeys.CLEANUP),
            SeedLinkKeys.deleteKeys,
        )
    }

    @Test
    fun `every key has a kind`() {
        assertEquals(SeedLinkKeys.all, SeedLinkKeys.kinds.keys)
    }
}
