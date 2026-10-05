package com.singularity.todo.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [EnumPref] reads a stored name back out of the enum's own entries.
 *
 * It used to do that with `KClass.java.enumConstants`, which resolves to
 * `java.lang.Class` — a type Kotlin/Native does not have, reached from `commonMain`
 * through a helper whose whole purpose was to hold something that would not compile
 * everywhere. The behaviour this file pins is the part that had to survive the
 * change: a name maps to its constant, and anything else falls back rather than
 * throwing or emitting null.
 */
@Tag("fast")
class EnumPrefTest {

    private enum class Mode { OFF, GENTLE, STRICT }

    @Test
    fun `a stored name resolves to its constant`() = runTest {
        val store = inMemoryDataStore()
        val pref = EnumPref(store, KEY, Mode.OFF, Mode.entries)

        pref.set(Mode.STRICT)

        assertEquals(Mode.STRICT, pref.flow.first())
    }

    @Test
    fun `a stored name that no longer exists falls back to the default`() = runTest {
        // What a downgrade looks like: the build that wrote this value no longer has
        // the constant. Throwing here would take a settings screen down over a value
        // the user cannot edit and never sees.
        val store = inMemoryDataStore(mutablePreferencesOf(KEY to "RETIRED_MODE"))
        val pref = EnumPref(store, KEY, Mode.OFF, Mode.entries)

        assertEquals(Mode.OFF, pref.flow.first())
    }

    @Test
    fun `an absent key falls back to the default`() = runTest {
        val pref = EnumPref(inMemoryDataStore(), KEY, Mode.GENTLE, Mode.entries)

        assertEquals(Mode.GENTLE, pref.flow.first())
    }

    @Test
    fun `every constant round-trips through its own name`() = runTest {
        val store = inMemoryDataStore()
        val pref = EnumPref(store, KEY, Mode.OFF, Mode.entries)

        Mode.entries.forEach { mode ->
            pref.set(mode)
            assertEquals(mode, pref.flow.first(), "${mode.name} did not survive the round trip")
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("enum.mode")

        /** A [DataStore] holding [initial] in memory, so the test needs no files. */
        fun inMemoryDataStore(
            initial: Preferences = emptyPreferences(),
        ): DataStore<Preferences> {
            val state = MutableStateFlow(initial)
            return object : DataStore<Preferences> {
                override val data: Flow<Preferences> = state
                override suspend fun updateData(
                    transform: suspend (Preferences) -> Preferences,
                ): Preferences = transform(state.value).also { state.value = it }
            }
        }
    }
}
