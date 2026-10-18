package com.singularity.todo.core.security

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [SecureStoreRecovery] — the "an unreadable encrypted store is erased and reopened" policy.
 *
 * ## What this covers, and why it is not a detail of the Android port
 *
 * The decision was originally written inline in `AndroidSecureStorage`, which needs a `Context`
 * and a `security-crypto` artifact. That made the recovery untestable from the JVM, and the
 * recovery is the whole fix: an uninstall/reinstall can leave
 * `EncryptedSharedPreferences` holding ciphertext whose Android Keystore key no longer exists,
 * and whether the app comes back from that is the question the bug was about.
 *
 * These tests pin the decision, and leave the two platform lambdas to the port.
 */
@Tag("fast")
class SecureStoreRecoveryTest {

    @Test
    fun `a store that opens first time is not erased`() {
        var erases = 0

        val result = SecureStoreRecovery.openOrErase(
            open = { "opened" },
            erase = { erases++ },
        )

        assertEquals("opened", result)
        assertEquals(0, erases, "nothing was wrong, so nothing should have been deleted")
    }

    @Test
    fun `a store that cannot be opened is erased and reopened`() {
        var erases = 0
        var attempts = 0

        // The shape of the real failure: the first open throws because the ciphertext does
        // not match the key, and the second succeeds because the file is gone.
        val result = SecureStoreRecovery.openOrErase(
            open = {
                attempts++
                if (attempts == 1) throw IllegalStateException("no such key")
                "opened after erase"
            },
            erase = { erases++ },
        )

        assertEquals("opened after erase", result)
        assertEquals(1, erases)
        assertEquals(2, attempts)
    }

    @Test
    fun `a second failure is rethrown rather than retried forever`() {
        // Erasing again would not help: a revoked lock screen or a full disk fails the same
        // way on a clean file. An unbounded retry is how a broken store turns into a startup
        // that never completes, so the second failure is the caller's to interpret.
        var attempts = 0

        val thrown = assertFailsWith<Exception> {
            SecureStoreRecovery.openOrErase(
                open = {
                    attempts++
                    throw IllegalStateException("attempt $attempts")
                },
                erase = {},
            )
        }

        assertEquals(2, attempts, "exactly one retry, then the failure surfaces")
        assertEquals("attempt 2", thrown.message, "the second failure is the one that surfaces")
    }

    @Test
    fun `the first failure is attached to the second`() {
        // The first failure is the diagnostic: it says the store was unreadable, while the
        // second only says the clean store also failed. Dropping the first loses which of
        // the two problems the device actually has.
        var attempts = 0

        val thrown = assertFailsWith<Exception> {
            SecureStoreRecovery.openOrErase(
                open = {
                    attempts++
                    throw IllegalStateException(if (attempts == 1) "ciphertext" else "locked")
                },
                erase = {},
            )
        }

        assertEquals(1, thrown.suppressed.size)
        assertEquals("ciphertext", thrown.suppressed[0].message)
    }

    @Test
    fun `an Error propagates without erasing anything`() {
        // The policy catches `Exception`, matching `runCatchingCancellable`: an `Error` is
        // never a stale-ciphertext problem, and retrying one — let alone deleting the user's
        // keychain first — would turn a heap exhaustion into data loss.
        var erases = 0

        assertFailsWith<OutOfMemoryError> {
            SecureStoreRecovery.openOrErase<String>(
                open = { throw OutOfMemoryError("heap") },
                erase = { erases++ },
            )
        }

        assertEquals(0, erases, "an Error is not a recoverable store condition")
    }
}
