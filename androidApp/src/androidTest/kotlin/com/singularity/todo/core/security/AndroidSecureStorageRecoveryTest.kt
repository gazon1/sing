package com.singularity.todo.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [AndroidSecureStorage] against a real `EncryptedSharedPreferences`.
 *
 * ## What this proves, and what it cannot
 *
 * `SecureStoreRecovery` — the decision to erase and reopen — is pure and is covered by
 * `SecureStoreRecoveryTest`. What is left is the IO it delegates to, and that needs a real
 * Android Keystore: that a value written here really is encrypted, that the file lands where
 * the port expects it, and that reopening a healthy store does not disturb what is in it.
 * None of that can be established by a fake, because the encryption is the thing under test.
 *
 * ## Why the broken-store recovery is not in this file
 *
 * It was, and it cannot be, and the reason is worth more than the test would have been.
 *
 * Every attempt to produce an unreadable store inside a single instrumented process failed
 * for the same underlying reason, twice over:
 *
 * - Deleting the keystore entry, leaving the file. The next `create` raises
 *   `AEADBadTagException` — correct — but **Tink caches the keyset per process**, so the
 *   retry inside `openOrErase` reads the same dead cached key and fails again. Recovery
 *   works in production precisely because a reinstall gets a fresh process, which is
 *   exactly what an in-process test cannot arrange.
 * - Corrupting the file's contents, keeping the key. **Android caches the parsed
 *   `SharedPreferences` in memory**, so the storage keeps serving the value it read before
 *   the file was touched. The test saw `value-1` from a file full of garbage.
 *
 * So this file keeps what a device can honestly establish, and the recovery itself is proven
 * where a fresh process is the natural unit: `Maestro/flows/auth/12-unreadable-store-recovers.yaml`,
 * which damages the file with `run-as`, kills the app, and relaunches.
 *
 * The first version of this file also deleted every keystore alias between tests and had
 * three of four tests fail with `AEADBadTagException` in tests that only wrote and read —
 * the cache effect above, leaking across tests.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
@Suppress("NoRunBlocking") // Instrumented tests use runBlocking to test suspend functions synchronously.
class AndroidSecureStorageRecoveryTest {

    private lateinit var context: Context

    private val prefsDir: File
        get() = File(context.applicationInfo.dataDir, "shared_prefs")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        wipePrefs()
    }

    @After
    fun tearDown() {
        wipePrefs()
    }

    /**
     * Deletes the file and **nothing else** — in particular not the keystore entry.
     *
     * Tearing down the key looks tidier and is actively harmful: it leaves Tink's per-process
     * keyset cache pointing at something that no longer exists, and every test after it fails
     * for a reason that has nothing to do with what it asserts.
     */
    private fun wipePrefs() {
        prefsDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * The prefs file, waited for.
     *
     * `AndroidSecureStorage.write` goes through `edit { }`, which applies asynchronously, so
     * the file is not on disk the instant `write` returns. Asserting on it immediately is a
     * race that passes or fails on the device's disk speed.
     */
    private fun awaitPrefsFile(timeoutMillis: Long = 5_000): File {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            prefsDir.listFiles()?.firstOrNull { it.isFile }?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("no prefs file appeared in $prefsDir within ${timeoutMillis}ms")
    }

    @Test
    fun storesAndReadsBackAValue() = runBlocking {
        val storage = AndroidSecureStorage(context)

        storage.write("token", "value-1")

        assertEquals("value-1", storage.read("token"))
    }

    @Test
    fun writesAFileRatherThanKeepingValuesInMemoryAlone() = runBlocking {
        // The encryption is only real if something lands on disk. A fake asserts this
        // trivially; here it is the difference between an encrypted store and a map.
        AndroidSecureStorage(context).write("token", "value-1")

        val file = awaitPrefsFile()

        assertTrue("precondition: a prefs file must exist", file.exists())
        val contents = file.readText()
        assertTrue("the file must not be empty", contents.isNotEmpty())
        assertTrue(
            "the stored value must not be readable as plaintext in the file, was: $contents",
            !contents.contains("value-1"),
        )
    }

    @Test
    fun reopeningAHealthyStoreDoesNotDisturbIt() = runBlocking {
        // The guard on the recovery: erasing is only correct for a file that cannot be read.
        // If it were unconditional, this value would be lost on every second open.
        AndroidSecureStorage(context).write("token", "value-1")

        assertEquals("value-1", AndroidSecureStorage(context).read("token"))
        assertEquals("value-1", AndroidSecureStorage(context).read("token"))
    }

    @Test
    fun deleteRemovesTheValue() = runBlocking {
        val storage = AndroidSecureStorage(context)
        storage.write("token", "value-1")

        storage.delete("token")

        assertNull("a deleted key must read back absent", storage.read("token"))
    }
}
