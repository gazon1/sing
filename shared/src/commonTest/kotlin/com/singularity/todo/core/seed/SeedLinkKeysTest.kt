package com.singularity.todo.core.seed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
                    listOf(SeedLinkKeys.RESET, SeedLinkKeys.CLEANUP, SeedLinkKeys.SESSION),
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
