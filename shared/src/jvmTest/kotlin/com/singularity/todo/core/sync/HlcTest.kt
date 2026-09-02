package com.singularity.todo.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class HlcTest {

    @Test
    fun `tick creates increasing timestamps`() {
        val node = "node1"
        val h1 = Hlc.tick(null, node, 1000)
        val h2 = Hlc.tick(h1, node, 1001)
        val h3 = Hlc.tick(h2, node, 1002)

        assertTrue(h2 > h1)
        assertTrue(h3 > h2)
        assertEquals(1000, h1.physical) // First tick uses wall clock
        assertEquals(1001, h2.physical)
        assertEquals(1002, h3.physical)
    }

    @Test
    fun `tick uses wall clock when ahead`() {
        val node = "node1"
        val h1 = Hlc.tick(null, node, 5000)
        assertEquals(5000, h1.physical)
    }

    @Test
    fun `tick increments counter when same physical time`() {
        val node = "node1"
        val h1 = Hlc.tick(null, node, 1000)
        val h2 = Hlc.tick(h1, node, 1000) // Same time
        val h3 = Hlc.tick(h2, node, 1000) // Same time again

        assertEquals(1000, h1.physical)
        assertEquals(1000, h2.physical)
        assertEquals(0, h1.counter)
        assertEquals(1, h2.counter)
        assertEquals(2, h3.counter)
    }

    @Test
    fun `tock merges remote correctly`() {
        val node = "node1"
        val remote = Hlc.of(5000, 0, "remote")

        val local = Hlc.of(4000, 5, node)
        val merged = Hlc.tock(local, remote, node, 4000)

        // Max of local, remote, now
        assertEquals(5000, merged.physical)
        assertTrue(merged > local)
    }

    @Test
    fun `tock increments counter when same physical`() {
        val node = "node1"
        val remote = Hlc.of(4000, 10, "remote")
        val local = Hlc.of(4000, 5, node)

        val merged = Hlc.tock(local, remote, node, 4000)

        assertEquals(4000, merged.physical)
        assertEquals(11, merged.counter) // max(local.counter, remote.counter) + 1
    }

    @Test
    fun `Hlc is Comparable`() {
        val node = "node"
        val earlier = Hlc.of(1000, 0, node)
        val later = Hlc.of(2000, 0, node)
        val sameTimeHigherCounter = Hlc.of(1000, 1, node)

        assertTrue(later > earlier)
        assertTrue(sameTimeHigherCounter > earlier)
        assertTrue(later.compareTo(earlier) > 0)
    }

    @Test
    fun `zero creates valid initial Hlc`() {
        val h = Hlc.zero("node1")
        assertEquals(0, h.physical)
        assertEquals(0, h.counter)
        assertEquals("node1", h.node)
    }

    @Test
    fun `parse roundtrips`() {
        val original = Hlc.of(12345, 67, "mynode")
        val parsed = Hlc.parse(original.toString())
        assertEquals(original.physical, parsed.physical)
        assertEquals(original.counter, parsed.counter)
        assertEquals(original.node, parsed.node)
    }
}
