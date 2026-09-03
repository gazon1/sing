package com.singularity.todo.core.notifications

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeNotificationPortTest {

    private val port = FakeNotificationPort()

    @Test
    fun `scheduleAt records the call`() = runTest {
        port.scheduleAt("key1", "Title", "Body", 12345L, "payload1")
        assertEquals(1, port.scheduled.size)
        val s = port.scheduled[0]
        assertEquals("key1", s.key)
        assertEquals("Title", s.title)
        assertEquals("Body", s.body)
        assertEquals(12345L, s.fireAtEpochMs)
        assertEquals("payload1", s.payload)
    }

    @Test
    fun `cancel records the key`() = runTest {
        port.scheduleAt("key1", "T", "B", 100L, null)
        port.cancel("key1")
        assertEquals(listOf("key1"), port.canceled)
    }

    @Test
    fun `cancelAll records all scheduled keys`() = runTest {
        port.scheduleAt("k1", "T", "B", 100L, null)
        port.scheduleAt("k2", "T", "B", 200L, null)
        port.cancelAll()
        assertTrue(port.canceled.contains("k1"))
        assertTrue(port.canceled.contains("k2"))
    }

    @Test
    fun `isAvailable returns configured value`() {
        val available = FakeNotificationPort(isAvailable = true)
        val unavailable = FakeNotificationPort(isAvailable = false)
        assertEquals(true, available.isAvailable)
        assertEquals(false, unavailable.isAvailable)
    }

    @Test
    fun `reset clears all recorded calls`() = runTest {
        port.scheduleAt("k1", "T", "B", 100L, null)
        port.cancel("k1")
        port.reset()
        assertEquals(emptyList(), port.scheduled)
        assertEquals(emptyList(), port.canceled)
    }
}
