@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

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
import com.singularity.todo.core.datastore.catchDataStoreIoError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
@Tag("fast")
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

    /**
     * The half of the contract that is easy to lose.
     *
     * Swallowing everything instead of I/O errors would make this suite pass and the
     * app wrong: a cancelled read would render as "no settings", and a programming
     * error would be indistinguishable from a corrupt file. This is the assertion that
     * says the catch is narrow.
     */
    @Test
    fun a_failure_that_is_not_io_still_propagates() = runTest {
        val store = failingDataStore(IllegalStateException("bug in the caller"))
        val wrapper = BooleanPref(prefSpecOf(booleanPreferencesKey("flag"), default = false, store = store))

        val thrown = assertFailsWith<IllegalStateException> { wrapper.flow.first() }
        assertEquals("bug in the caller", thrown.message)
    }

    @Test
    fun the_io_callback_sees_the_failure_that_was_swallowed() = runTest {
        val store = ioExceptionThrowingDataStore()
        val seen = mutableListOf<Throwable>()

        val value = store.data.catchDataStoreIoError { seen += it }.first()

        assertEquals(emptyPreferences(), value)
        assertEquals(1, seen.size, "the draft store logs here; a swallowed failure must still be reportable")
        assertTrue(seen.single() is IOException)
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

/** A [DataStore] whose [data] flow fails with [failure] on subscription. */
private fun failingDataStore(failure: Throwable): DataStore<Preferences> = object : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw failure }

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
