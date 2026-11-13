package com.singularity.todo.feature.projects.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The recent emoji history, as it is written and read back.
 *
 * The rule that decides the order lives in its own test; this one is about the
 * round trip: what the picker wrote has to be what the next opening of the
 * picker reads, in the same order, and it has to still be there after the app
 * was closed and opened again.
 *
 * The file is a real preferences file, not a fake: the order survives because
 * the list is written as one string, and that is a property of how the value is
 * stored, not of the code in memory.
 */
@Tag("fast")
class RecentEmojiStoreTest {
    @Test
    fun `a picked emoji is the next one offered`(
        @TempDir dir: File,
    ) = runTest {
        val store = store(dir, "recent.preferences_pb")

        store.record("🚀")

        assertEquals(listOf("🚀"), store.recent.first(), "the emoji that was just picked")
    }

    @Test
    fun `the newest emoji comes first`(
        @TempDir dir: File,
    ) = runTest {
        val store = store(dir, "recent.preferences_pb")

        store.record("🚀")
        store.record("🎯")

        assertEquals(listOf("🎯", "🚀"), store.recent.first(), "the newest pick is at the front")
    }

    @Test
    fun `a composed emoji is stored as one entry`(
        @TempDir dir: File,
    ) = runTest {
        val store = store(dir, "recent.preferences_pb")

        store.record("👨‍👩‍👧‍👦")

        assertEquals(
            listOf("👨‍👩‍👧‍👦"),
            store.recent.first(),
            "a ZWJ sequence is one emoji, not four",
        )
    }

    @Test
    fun `the history is still there after the app is opened again`(
        @TempDir dir: File,
    ) = runTest {
        val file = File(dir, "recent.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val first = RecentEmojiStore(PreferenceDataStoreFactory.create(scope = scope) { file })
        first.record("🚀")

        // The first store stands for the running app; its scope is cancelled and
        // joined, so the file is released the way closing the app releases it.
        scope.cancel()
        scope.coroutineContext[Job]?.join()

        assertEquals(listOf("🚀"), readHistory(file), "the history outlived the first store")
    }

    private fun store(
        dir: File,
        name: String,
    ): RecentEmojiStore = RecentEmojiStore(PreferenceDataStoreFactory.create { File(dir, name) })

    /**
     * Reads the history out of a file a store has just released.
     *
     * DataStore refuses two stores on one file, and it releases the file when the
     * scope of the first one is cancelled — which happens on the thread that
     * cancelled it, a moment after the cancellation is observable. The wait is
     * therefore part of the test: without it the second store is refused for a
     * reason that has nothing to do with the history.
     */
    private suspend fun readHistory(file: File): List<String> {
        var lastRefusal: String? = null
        repeat(50) {
            try {
                val store = RecentEmojiStore(PreferenceDataStoreFactory.create { file })
                return store.recent.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                lastRefusal = e.message
                delay(20)
            }
        }
        fail("the settings file was never released by the store that held it: $lastRefusal")
    }
}
