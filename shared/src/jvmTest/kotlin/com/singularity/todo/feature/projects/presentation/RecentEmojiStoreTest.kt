package com.singularity.todo.feature.projects.presentation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.settings.SettingsNamespace
import com.singularity.todo.feature.projects.domain.logic.RECENT_EMOJI_LIMIT
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

        assertEquals(listOf("🚀"), store.recent.first { it.isNotEmpty() }, "the emoji that was just picked")
    }

    @Test
    fun `the newest emoji comes first`(
        @TempDir dir: File,
    ) = runTest {
        val store = store(dir, "recent.preferences_pb")

        store.record("🚀")
        store.record("🎯")

        assertEquals(listOf("🎯", "🚀"), store.recent.first { it.size == 2 }, "the newest pick is at the front")
    }

    @Test
    fun `a composed emoji is stored as one entry`(
        @TempDir dir: File,
    ) = runTest {
        val store = store(dir, "recent.preferences_pb")

        store.record("👨‍👩‍👧‍👦")

        assertEquals(
            listOf("👨‍👩‍👧‍👦"),
            store.recent.first { it.isNotEmpty() },
            "a ZWJ sequence is one emoji, not four",
        )
    }

    @Test
    fun `a value that holds the separator survives the round trip`(
        @TempDir dir: File,
    ) = runTest {
        val store = store(dir, "recent.preferences_pb")

        // The picker takes free text as a custom emoji, so a stored value can
        // hold the character the list is separated by.
        store.record("a\u001Fb")

        assertEquals(
            listOf("a\u001Fb"),
            store.recent.first { it.isNotEmpty() },
            "the separator inside a value does not split the value",
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
        first.recent.first { it.isNotEmpty() }

        // The first store stands for the running app; its scope is cancelled and
        // joined, so the file is released the way closing the app releases it.
        scope.cancel()
        scope.coroutineContext[Job]?.join()

        assertEquals(listOf("🚀"), readHistory(file), "the history outlived the first store")
    }

    @Test
    fun `a history that is longer than the bound is cut when it is read`(
        @TempDir dir: File,
    ) = runTest {
        val file = File(dir, "recent.preferences_pb")
        writeRaw(file, (1..RECENT_EMOJI_LIMIT + 5).joinToString("\u001F") { "emoji$it" })

        val history = readHistory(file, requireEntries = false)

        assertEquals(RECENT_EMOJI_LIMIT, history.size, "the bound holds when it is read")
    }

    @Test
    fun `a repeated value in the file is shown once`(
        @TempDir dir: File,
    ) = runTest {
        val file = File(dir, "recent.preferences_pb")
        writeRaw(file, "🚀\u001F🎯\u001F🚀")

        val history = readHistory(file, requireEntries = false)

        assertEquals(listOf("🚀", "🎯"), history, "the same value is not shown twice")
    }

    @Test
    fun `a settings file without the key has no history`(
        @TempDir dir: File,
    ) = runTest {
        val file = File(dir, "recent.preferences_pb")
        // A settings file that was cleared: the file is there, the key is not.
        writeOtherKey(file)

        val history = readHistory(file, requireEntries = false)

        assertEquals(emptyList(), history, "a cleared history is no history")
    }

    private fun store(
        dir: File,
        name: String,
    ): RecentEmojiStore = RecentEmojiStore(PreferenceDataStoreFactory.create { File(dir, name) })

    /**
     * Writes a key of its own, so the history key stays absent — the shape a
     * cleared settings file has.
     */
    private suspend fun writeOtherKey(file: File) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            PreferenceDataStoreFactory.create(scope = scope) { file }.edit { prefs ->
                prefs[stringPreferencesKey("projects.something_else")] = "value"
            }
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }

    /**
     * Writes the stored value directly, the way an older version of the app
     * or a hand-edited settings file would leave it.
     *
     * The store that writes it is given a scope of its own and that scope is
     * cancelled afterwards: DataStore refuses a second store on one file for as
     * long as the first one holds it.
     */
    private suspend fun writeRaw(
        file: File,
        raw: String,
    ) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            PreferenceDataStoreFactory.create(scope = scope) { file }.edit { prefs ->
                prefs[KEY] = raw
            }
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }

    /**
     * Reads the history out of a file a store has just released.
     *
     * DataStore refuses two stores on one file, and it releases the file when the
     * scope of the first one is cancelled — which happens on the thread that
     * cancelled it, a moment after the cancellation is observable. The wait is
     * therefore part of the test: without it the second store is refused for a
     * reason that has nothing to do with the history.
     */
    private suspend fun readHistory(
        file: File,
        requireEntries: Boolean = true,
    ): List<String> {
        var lastRefusal: String? = null
        repeat(50) {
            try {
                val store = RecentEmojiStore(PreferenceDataStoreFactory.create { file })
                return if (requireEntries) {
                    store.recent.first { it.isNotEmpty() }
                } else {
                    store.recent.first()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                lastRefusal = e.message
                delay(20)
            }
        }
        fail("the settings file was never released by the store that held it: $lastRefusal")
    }

    private companion object {
        val KEY =
            stringPreferencesKey(
                SettingsNamespace.key(SettingsNamespace.PROJECTS, "recent_emojis"),
            )
    }
}
