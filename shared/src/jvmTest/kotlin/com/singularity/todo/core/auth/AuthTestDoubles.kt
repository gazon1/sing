@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import co.touchlab.kermit.Logger
import com.singularity.todo.core.ids.IdGenerator
import kotlinx.coroutines.flow.MutableStateFlow

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

fun testLogger(tag: String = "auth-test") = Logger.withTag(tag)
