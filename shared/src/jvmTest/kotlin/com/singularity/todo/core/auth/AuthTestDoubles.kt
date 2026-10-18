@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import co.touchlab.kermit.Logger
import com.singularity.todo.core.ids.IdGenerator
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Test doubles shared by the auth tests.
 *
 * Both the session-store and the repository tests need a keychain, a device id
 * generator and a preferences file, and they need the *same* ones: a store built
 * over a different keychain than the one the assertion reads is a test that passes
 * for the wrong reason.
 *
 * An in-memory [SecureStorage] whose contents a test can inspect.
 *
 * [failWrites] fails every write, which is the "the keychain is unavailable" case.
 * [failWriteNumber] fails only the *n*-th write of a test and lets the rest through,
 * which is the case that exposes a caller that leaves a partly written value behind —
 * a blanket failure is undone by any caller that treats the whole operation as
 * all-or-nothing, so it cannot tell the two apart.
 */
class MapSecureStorage(private val failWrites: Boolean = false, private val failWriteNumber: Int? = null) :
    SecureStorage {
    val values = mutableMapOf<String, String>()
    private var writes = 0

    override suspend fun read(key: String): String? = values[key]

    override suspend fun write(key: String, value: String) {
        writes++
        if (failWrites) error("keychain unavailable")
        if (failWriteNumber == writes) error("keychain unavailable on write $writes")
        values[key] = value
    }

    override suspend fun delete(key: String) {
        values.remove(key)
    }

    /** Write attempts so far, whether or not they succeeded. */
    val writeAttempts: Int get() = writes
}

class FixedIdGenerator(private val id: String = "dev") : IdGenerator {
    private var n = 0
    override fun next(): String = "$id-${n++}"
}

/**
 * A [SecureStorage] that never answers.
 *
 * [MapSecureStorage] answers in microseconds, which is why a bug that only shows
 * up when a store stops responding survived a suite built entirely out of fast
 * fakes: nothing in the double can express "this call has no answer", so nothing
 * in the suite can ask what the caller does when there isn't one.
 *
 * `awaitCancellation` rather than a bare `delay(Long.MAX_VALUE)` — it suspends
 * forever *and* stays cancellable, which is what a timeout needs to take effect.
 * A delay would also need virtual time to reach an absurd value before it became
 * cancellable at all.
 */
class HangingSecureStorage : SecureStorage {
    override suspend fun read(key: String): String? = awaitCancellation()

    override suspend fun write(key: String, value: String) {
        awaitCancellation()
    }

    override suspend fun delete(key: String) {
        awaitCancellation()
    }
}

/**
 * A [SecureStorage] that throws on every call — the uninstall/reinstall case, where
 * Auto Backup restores the ciphertext but the Android Keystore key that decrypts it
 * is gone, and every read of the session store raises `GeneralSecurityException`.
 *
 * Throwing and hanging look identical from outside the app (the spinner never
 * stops) but they take different paths to get there: only one of them is a
 * timeout. [failure] is a factory so a test can assert it is the *same* instance
 * that reached the log.
 */
class ThrowingSecureStorage(private val failure: () -> Exception) : SecureStorage {
    override suspend fun read(key: String): String? = throw failure()

    override suspend fun write(key: String, value: String): Unit = throw failure()

    override suspend fun delete(key: String): Unit = throw failure()
}

/** Minimal in-memory [DataStore]; the shape the settings migration test uses too. */
fun newPreferencesStore(): DataStore<Preferences> {
    val state = MutableStateFlow<Preferences>(emptyPreferences())
    return object : DataStore<Preferences> {
        override val data = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }
}

/**
 * A [DataStore] whose reads never emit — the same "no answer" case as
 * [HangingSecureStorage], for the local-owner store that the signed-out path reads.
 *
 * The flow is cold and never produces a value, so `first()` on it suspends
 * indefinitely. This is the one faked store that actually reaches the user's
 * first screen: with no tokens stored, `restoreSession` decides between "offline"
 * and "signed out" by reading this.
 */
fun hangingPreferencesStore(): DataStore<Preferences> = object : DataStore<Preferences> {
    override val data = flow<Preferences> { awaitCancellation() }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        awaitCancellation()
}

/**
 * A [DataStore] that throws on read.
 *
 * Deliberately a [SecurityException] rather than an [java.io.IOException]: the
 * production `catchDataStoreIoError` catches IOException and substitutes empty
 * preferences, so a corruption-shaped failure is *handled* and a
 * decryption-shaped one is not. A fake that threw IOException would test the
 * branch that already works.
 */
fun throwingPreferencesStore(failure: () -> Exception): DataStore<Preferences> =
    object : DataStore<Preferences> {
        override val data = flow<Preferences> { throw failure() }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw failure()
    }

fun testLogger(tag: String = "auth-test") = Logger.withTag(tag)
