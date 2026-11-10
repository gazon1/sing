package com.singularity.todo.core.seed

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `count=` fixture size is bounded: a malformed debug link must not be able to
 * ask the device to write an unbounded number of rows.
 */
@Tag("fast")
class SeedCountTest {
    @Test
    fun `an absent count is one task`() {
        assertEquals(1, SeedCount.parse(null))
    }

    @Test
    fun `a valid count is used as given`() {
        assertEquals(1, SeedCount.parse("1"))
        assertEquals(1_001, SeedCount.parse("1001"))
        assertEquals(SeedCount.MAX, SeedCount.parse(SeedCount.MAX.toString()))
    }

    @Test
    fun `zero and negative counts are rejected`() {
        assertNull(SeedCount.parse("0"))
        assertNull(SeedCount.parse("-1"))
    }

    @Test
    fun `an integer overflow is rejected, not wrapped`() {
        assertNull(SeedCount.parse("999999999999999999999"))
    }

    @Test
    fun `a count above the cap is rejected`() {
        assertNull(SeedCount.parse((SeedCount.MAX + 1).toString()))
    }

    @Test
    fun `a non-numeric count is rejected`() {
        assertNull(SeedCount.parse("many"))
    }
}
