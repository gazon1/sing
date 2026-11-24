@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import androidx.datastore.preferences.core.edit
import com.singularity.todo.core.auth.hangingPreferencesStore
import com.singularity.todo.core.auth.newPreferencesStore
import com.singularity.todo.core.auth.throwingPreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HLC store: what a stored clock reads back as, and what happens when the
 * stored value is not a clock.
 *
 * ## What a malformed value does
 *
 * [Hlc.parse] is total: it wraps whatever string it is given and never throws,
 * so a stored value that is not a clock comes back as a *degenerate* clock
 * rather than as `null` — `physical` 0, `counter` 0, and a node id that is
 * whatever was in the file. That is deliberate on the parse side (a clock that
 * cannot be read is a clock that cannot be compared), and it is harmless on the
 * tick side: a degenerate clock sits at zero, and the next tick takes the wall
 * clock, so nothing drifts backwards.
 *
 * The point of the tests here is that a malformed value does not **break** the
 * settings read: the store still answers, and the next write still lands.
 */
@Tag("fast")
class HlcStoreTest {
    @Test
    fun `a saved clock is read back`() =
        runTest {
            val store = DataStoreHlcStore(newPreferencesStore())
            val saved = Hlc.of(1234L, 5, "node-1")

            store.save(saved)

            assertEquals(saved, store.load())
        }

    @Test
    fun `an empty store has no clock`() =
        runTest {
            val store = DataStoreHlcStore(newPreferencesStore())

            assertNull(store.load())
        }

    @Test
    fun `a malformed stored value does not break the settings read`() =
        runTest {
            val dataStore = newPreferencesStore()
            val store = DataStoreHlcStore(dataStore)
            dataStore.edit { it[DataStoreHlcStore.LAST_HLC] = "not-a-clock" }

            // The read answers. It does not throw, and it does not leave the store
            // in a state where the next write is lost.
            val loaded = assertNotNull(store.load(), "the read answered")
            assertEquals(0L, loaded.physical, "a degenerate clock sits at zero")
            assertEquals(0, loaded.counter, "and so does its counter")

            val next = Hlc.of(99L, 0, "node-1")
            store.save(next)
            assertEquals(next, store.load(), "the store still takes writes")
        }

    @Test
    fun `a malformed stored value does not hold the clock back`() =
        runTest {
            val dataStore = newPreferencesStore()
            val store = DataStoreHlcStore(dataStore)
            dataStore.edit { it[DataStoreHlcStore.LAST_HLC] = "not-a-clock" }

            // A degenerate clock is at zero, so the next tick is the wall clock and
            // the counter restarts. A stored value that cannot be read must not
            // become a clock that cannot move forward.
            val ticked = Hlc.tick(store.load(), "node-1", nowMillis = 5_000L)

            assertEquals(5_000L, ticked.physical)
            assertEquals(0, ticked.counter)
        }

    /**
     * The stored clock never moves backwards.
     *
     * The stored clock is the device's promise that it will not mint a clock older
     * than it. A write carrying an *older* clock would undo that promise: after a
     * restart the device would restore a clock older than one it already issued, and
     * the next patch could carry a timestamp older than a timestamp the server
     * already holds. The write is dropped instead.
     */
    @Test
    fun `the store refuses to go back to an older clock`() =
        runTest {
            val store = DataStoreHlcStore(newPreferencesStore())
            val newer = Hlc.of(100L, 0, "node-1")

            store.save(newer)
            store.save(Hlc.of(50L, 0, "node-1"))

            assertEquals(newer, store.load(), "the stored clock stays the newest one written")
        }

    @Test
    fun `a cancelled read propagates the cancellation`() =
        runTest {
            val store = DataStoreHlcStore(hangingPreferencesStore())

            val read = async { store.load() }
            runCurrent()
            read.cancel()

            // The cancellation is not swallowed into a null: a caller that gave up
            // on the read has to hear that it gave up, not that there was no clock.
            assertFailsWith<CancellationException> { read.await() }
        }

    @Test
    fun `a read that fails outright is not a clock either`() =
        runTest {
            val store = DataStoreHlcStore(throwingPreferencesStore { SecurityException("the key is gone") })

            // A decryption-shaped failure is not an I/O failure, so the store does
            // not substitute empty preferences for it — and it certainly does not
            // invent a clock.
            assertFailsWith<SecurityException> { store.load() }
        }

    @Test
    fun `the store answers with the clock it was given, not a copy of the file`() =
        runTest {
            val dataStore = newPreferencesStore()
            val store = DataStoreHlcStore(dataStore)

            store.save(Hlc.of(7L, 1, "node-1"))

            assertTrue(
                dataStore.data.first()[DataStoreHlcStore.LAST_HLC] == "7:1:node-1",
                "the value on disk is the encoded clock",
            )
        }
}
