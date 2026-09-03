package com.singularity.todo.core.security

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeSecureStorageTest {

    private val storage = FakeSecureStorage()

    @Test
    fun `read returns null for absent key`() = runTest {
        assertNull(storage.read("nonexistent"))
    }

    @Test
    fun `write then read returns the value`() = runTest {
        storage.write("key1", "value1")
        assertEquals("value1", storage.read("key1"))
    }

    @Test
    fun `write overwrites existing value`() = runTest {
        storage.write("key1", "v1")
        storage.write("key1", "v2")
        assertEquals("v2", storage.read("key1"))
    }

    @Test
    fun `delete removes the key`() = runTest {
        storage.write("key1", "v1")
        storage.delete("key1")
        assertNull(storage.read("key1"))
    }

    @Test
    fun `delete is idempotent`() = runTest {
        storage.delete("nonexistent") // must not throw
    }

    @Test
    fun `multiple keys are independent`() = runTest {
        storage.write("k1", "v1")
        storage.write("k2", "v2")
        assertEquals("v1", storage.read("k1"))
        assertEquals("v2", storage.read("k2"))
    }

    @Test
    fun `isHardwareBacked returns configured value`() {
        val backed = FakeSecureStorage(hardwareBacked = true)
        val unbacked = FakeSecureStorage(hardwareBacked = false)
        assertEquals(true, backed.isHardwareBacked())
        assertEquals(false, unbacked.isHardwareBacked())
    }
}
