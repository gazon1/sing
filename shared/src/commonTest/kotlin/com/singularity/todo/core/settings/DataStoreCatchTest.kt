package com.singularity.todo.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals

/**
 * Verifies that `.catch { emit(emptyPreferences()) }` is applied to [PreferenceWrappers] flows,
 * so that a corrupted DataStore file results in the default value being emitted rather than
 * propagating the exception.
 *
 * Each test corrupts the underlying [DataStore] by making its [data][DataStore.data] flow
 * emit an [IOException], then asserts that the wrapper's [Flow.first][Flow.first] returns
 * the configured default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreCatchTest {

    @Test
    fun booleanPref_falls_back_to_default_on_io_exception() = runTest {
        val throwingStore = ioExceptionThrowingDataStore()
        val spec = prefSpecOf(booleanPreferencesKey("flag"), default = false, store = throwingStore)
        val wrapper = BooleanPref(spec)

        assertEquals(false, wrapper.flow.first())
    }

    @Test
    fun intPref_falls_back_to_default_on_io_exception() = runTest {
        val throwingStore = ioExceptionThrowingDataStore()
        val spec = prefSpecOf(intPreferencesKey("count"), default = 42, store = throwingStore)
        val wrapper = IntPref(spec)

        assertEquals(42, wrapper.flow.first())
    }

    @Test
    fun stringPref_falls_back_to_default_on_io_exception() = runTest {
        val throwingStore = ioExceptionThrowingDataStore()
        val spec = prefSpecOf(stringPreferencesKey("name"), default = "anonymous", store = throwingStore)
        val wrapper = StringPref(spec)

        assertEquals("anonymous", wrapper.flow.first())
    }

    @Test
    fun floatPref_falls_back_to_default_on_io_exception() = runTest {
        val throwingStore = ioExceptionThrowingDataStore()
        val spec = prefSpecOf(floatPreferencesKey("ratio"), default = 1.0f, store = throwingStore)
        val wrapper = FloatPref(spec)

        assertEquals(1.0f, wrapper.flow.first())
    }
}

// ─── Test infrastructure ──────────────────────────────────────────────────────────────

/**
 * A [DataStore] whose [data][DataStore.data] flow throws [IOException] on the first
 * subscription. Simulates a corrupted on-disk preferences file.
 */
private fun ioExceptionThrowingDataStore(): DataStore<Preferences> = object : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow {
        throw IOException("Simulated DataStore corruption")
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(emptyPreferences())
}

/**
 * Creates a [PrefSpec] for testing. [PrefSpec] is internal, so we construct it via
 * the internal constructor and pass the corrupted store directly.
 */
private fun <T : Any> prefSpecOf(key: Preferences.Key<T>, default: T, store: DataStore<Preferences>): PrefSpec<T> =
    PrefSpec(
        dataStore = store,
        key = key,
        default = default,
    )
