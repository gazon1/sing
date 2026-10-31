package com.singularity.todo.core.security

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("slow")
class JvmSecureStorageKeyringIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val secureBin: Path get() = tempDir.resolve("secure.bin")
    private val uniqueKey = "test-${System.nanoTime()}"

    private lateinit var storage: JvmSecureStorage

    @AfterEach
    fun cleanup() {
        runCatching { storage.rotateFallbackKey() }
    }

    @Test
    fun `fallback round-trips a value through the real secret-tool`() = runTest {
        storage = object : JvmSecureStorage() {
            override val fallbackFile: Path = this@JvmSecureStorageKeyringIntegrationTest.secureBin
            override val fallbackKeyName: String = this@JvmSecureStorageKeyringIntegrationTest.uniqueKey
        }
        storage.write("key", "secret value")
        assertEquals("secret value", storage.read("key"))
    }
}
