package com.singularity.todo.core.security

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
class JvmSecureStorageKeyringTest {

    @TempDir
    lateinit var tempDir: Path

    private val secureBin: Path get() = tempDir.resolve("secure.bin")

    @Test
    fun `fallback round-trips a value when a keyring is available`() = runTest {
        val keyring = mutableMapOf<String, String>()
        fun make() = object : JvmSecureStorage() {
            override val fallbackFile: Path = this@JvmSecureStorageKeyringTest.secureBin
            override fun secretToolAvailable() = true
            override fun secretToolRead(key: String): String? = keyring[key]
            override fun secretToolWrite(key: String, value: String): Boolean {
                keyring[key] = value
                return true
            }
        }
        val first = make()
        first.write("key", "secret value")
        // A second instance sharing the same keyring must decrypt the file.
        val second = make()
        assertEquals("secret value", second.read("key"))
    }
}
