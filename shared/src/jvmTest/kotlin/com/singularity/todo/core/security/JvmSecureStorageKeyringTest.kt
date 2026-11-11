package com.singularity.todo.core.security

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keyring and the fallback file are one store, not two.
 *
 * ## What is at stake
 *
 * The JVM build keeps a secret in the OS keyring and, when the keyring is not
 * there, in an encrypted file. Two defects made the two disagree:
 *
 *  - the keyring's value was trimmed, and an empty one was reported as absent,
 *    so the same key answered with two different values depending on which
 *    backend had it;
 *  - a write that reached the keyring never reached the file, so a keyring
 *    that came back and then went away again made the file answer with the
 *    value from **before** — an old API key, an old refresh token, with no
 *    error anywhere.
 */
@Tag("fast")
class JvmSecureStorageKeyringTest {
    @TempDir
    lateinit var tempDir: Path

    private val secureBin: Path get() = tempDir.resolve("secure.bin")

    @Test
    fun `fallback round-trips a value when a keyring is available`() =
        runTest {
            val keyring = mutableMapOf<String, String>()

            fun make() =
                object : JvmSecureStorage() {
                    override val fallbackFile: Path = this@JvmSecureStorageKeyringTest.secureBin

                    override fun secretToolAvailable() = true

                    override fun secretToolRead(key: String): String? = keyring[key]

                    override fun secretToolWrite(
                        key: String,
                        value: String,
                    ): Boolean {
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

    /**
     * A secret is read back exactly as it was written.
     *
     * The keyring's value used to be trimmed, so a credential with whitespace
     * around it came back without it — corrupted, with nothing to show for it.
     */
    @Test
    fun `a secret with whitespace reads back as it was written`() =
        runTest {
            val keyring = mutableMapOf<String, String>()
            val storage =
                object : JvmSecureStorage() {
                    override val fallbackFile: Path = this@JvmSecureStorageKeyringTest.secureBin

                    override fun secretToolAvailable() = true

                    override fun secretToolRead(key: String): String? = keyring[key]

                    override fun secretToolWrite(
                        key: String,
                        value: String,
                    ): Boolean {
                        keyring[key] = value
                        return true
                    }
                }

            storage.write("key", "  secret value  ")

            assertEquals("  secret value  ", storage.read("key"))
        }

    /**
     * An empty secret is a secret, not an absent one.
     *
     * The port says `null` means "there is no such key". An empty value that
     * came back as `null` sent the app down the sign-in path with a credential
     * the user had already stored.
     */
    @Test
    fun `an empty secret reads back as an empty value`() =
        runTest {
            val keyring = mutableMapOf<String, String>()
            val storage =
                object : JvmSecureStorage() {
                    override val fallbackFile: Path = this@JvmSecureStorageKeyringTest.secureBin

                    override fun secretToolAvailable() = true

                    override fun secretToolRead(key: String): String? = keyring[key]

                    override fun secretToolWrite(
                        key: String,
                        value: String,
                    ): Boolean {
                        keyring[key] = value
                        return true
                    }
                }

            storage.write("key", "")

            assertEquals("", storage.read("key"))
        }

    /**
     * The file never answers with a value older than the keyring's.
     *
     * The sequence is the one the defect needed: the keyring is unavailable at
     * first launch, so the credential goes to the file; the keyring comes back,
     * and the new credential goes there too; the keyring fails again, and the
     * read falls through to the file.
     *
     * The file must not answer with the credential from **before** the keyring
     * came back — that one has been replaced, and answering with it would hand
     * the app a revoked credential as if nothing had happened. So the write that
     * reached the keyring drops the file's copy of that key.
     *
     * What the file answers afterwards is "absent", not "the old value". A user
     * who has to sign in again is a smaller harm than a user whose app keeps
     * using a credential that was replaced on purpose.
     */
    @Test
    fun `the fallback file never holds a value older than the keyring's`() =
        runTest {
            val keyring = mutableMapOf<String, String>()
            var keyringUp = false

            fun make() =
                object : JvmSecureStorage() {
                    override val fallbackFile: Path = this@JvmSecureStorageKeyringTest.secureBin

                    override fun secretToolAvailable() = keyringUp

                    override fun secretToolRead(key: String): String? = if (keyringUp) keyring[key] else null

                    override fun secretToolWrite(
                        key: String,
                        value: String,
                    ): Boolean {
                        if (!keyringUp) return false
                        keyring[key] = value
                        return true
                    }
                }

            // 1. The keyring is unavailable: the credential goes to the file.
            keyringUp = false
            make().write("key", "old credential")

            // 2. The keyring comes back: the new credential is written, and the
            //    file's copy of the key is dropped.
            keyringUp = true
            make().write("key", "new credential")

            // 3. The keyring fails again: the read falls through to the file.
            keyringUp = false
            assertNull(
                make().read("key"),
                "the file answered with the credential from before the keyring came back",
            )
        }

    /**
     * The file's copy of a key is dropped only for that key.
     *
     * The file is one store with many keys. Dropping the stale copy of one
     * must not take the others with it — the whole file is erased on an
     * unreadable error, so a careless "rewrite the file" would cost every
     * secret in it.
     */
    @Test
    fun `the keyring's write drops only its own key from the file`() =
        runTest {
            val keyring = mutableMapOf<String, String>()
            var keyringUp = false

            fun make() =
                object : JvmSecureStorage() {
                    override val fallbackFile: Path = this@JvmSecureStorageKeyringTest.secureBin

                    override fun secretToolAvailable() = keyringUp

                    override fun secretToolRead(key: String): String? = if (keyringUp) keyring[key] else null

                    override fun secretToolWrite(
                        key: String,
                        value: String,
                    ): Boolean {
                        if (!keyringUp) return false
                        keyring[key] = value
                        return true
                    }
                }

            keyringUp = false
            make().write("other", "other credential")
            make().write("key", "old credential")

            keyringUp = true
            make().write("key", "new credential")

            keyringUp = false
            assertNull(make().read("key"))
            assertEquals("other credential", make().read("other"))
        }

    /** A key that was never written is absent, not empty. */
    @Test
    fun `a key that was never written is absent`() =
        runTest {
            val storage =
                object : JvmSecureStorage() {
                    override val fallbackFile: Path = this@JvmSecureStorageKeyringTest.secureBin

                    override fun secretToolAvailable() = false
                }

            assertNull(storage.read("never-written"))
        }

    /**
     * The store says which store holds the secret.
     *
     * The flag used to answer "hardware-backed" while the value behind it was
     * "a keyring is there", and the answer was gated on the host's name as well:
     * a macOS or a Windows host with a keyring answered `false` and looked like
     * the fallback. The answer now follows the backend alone — the operating
     * system's name is not consulted at all — so the assertion below holds on
     * any host.
     */
    @Test
    fun `the platform key store is reported when a keyring is available`() {
        val storage =
            object : JvmSecureStorage() {
                override val fallbackFile: Path = this@JvmSecureStorageKeyringTest.secureBin

                override fun secretToolAvailable() = true
            }

        assertTrue(
            storage.usesPlatformKeyStore(),
            "a host with a keyring keeps its secrets in the platform's own store",
        )
    }

    /**
     * Without a keyring the secrets go to the derived file, and the flag says so.
     *
     * This is the answer the decision record
     * `2026-10-27-jvm-secure-storage-fallback-is-not-a-secret-store` depends on:
     * the fallback is obfuscation rather than protection, and the flag is the only
     * signal a caller gets that it is in use.
     */
    @Test
    fun `the fallback is reported when no keyring is available`() {
        val storage =
            object : JvmSecureStorage() {
                override val fallbackFile: Path = this@JvmSecureStorageKeyringTest.secureBin

                override fun secretToolAvailable() = false
            }

        assertFalse(
            storage.usesPlatformKeyStore(),
            "the derived-file fallback is not the platform's own store",
        )
    }
}
