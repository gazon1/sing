package com.singularity.todo.core.sync

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stored form of a trigger set has to survive a round trip, because it is re-read on
 * every app start. It used not to: an explicitly empty set serialised to the same blank
 * string that meant "all triggers", so turning every trigger off and restarting turned
 * them all back on.
 */
@Tag("fast")
class SyncTriggerCodecTest {

    private fun roundTrip(triggers: Set<SyncTrigger>): Set<SyncTrigger> =
        SyncTrigger.parseCsv(SyncTrigger.toCsv(triggers))

    @Test
    fun `an explicitly empty set stays empty`() {
        assertEquals(emptySet(), roundTrip(emptySet()))
    }

    @Test
    fun `an empty set does not collide with the all-triggers default`() {
        val stored = SyncTrigger.toCsv(emptySet())
        assertTrue(stored.isNotBlank(), "the empty set must not serialise to a blank string")
        assertTrue(
            stored != SyncTrigger.toCsv(SyncTrigger.entries.toSet()),
            "empty and all-triggers must not share an encoding",
        )
    }

    @Test
    fun `every non-empty subset round-trips`() {
        val all = SyncTrigger.entries
        // 2^6 = 64 subsets; small enough to be exhaustive, which is the point.
        for (mask in 0 until (1 shl all.size)) {
            val subset = all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            assertEquals(subset, roundTrip(subset), "round trip changed $subset")
        }
    }

    @Test
    fun `a fresh row defaults to all triggers`() {
        assertEquals(SyncTrigger.entries.toSet(), SyncTrigger.parseCsv(null))
        assertEquals(SyncTrigger.entries.toSet(), SyncTrigger.parseCsv(""))
        assertEquals(SyncTrigger.entries.toSet(), SyncTrigger.parseCsv("   "))
    }

    @Test
    fun `an unknown name costs one trigger, not the whole set`() {
        val parsed = SyncTrigger.parseCsv("Created,RetiredTrigger,Updated")
        assertEquals(setOf(SyncTrigger.Created, SyncTrigger.Updated), parsed)
    }

    @Test
    fun `the stored value is ordered, so a preferences diff stays readable`() {
        val csv = SyncTrigger.toCsv(SyncTrigger.entries.toSet())
        assertEquals(SyncTrigger.entries.sortedBy { it.ordinal }.joinToString(",") { it.name }, csv)
    }
}
