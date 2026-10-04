package com.singularity.todo.core.analytics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records every event the port was asked to log. */
private class RecordingAnalytics : Analytics {
    val events = mutableListOf<String>()
    override fun logEvent(event: String, vararg params: Pair<String, Any>) {
        events += event
    }

    override fun identify(distinctId: String) = Unit
}

/**
 * The dedup has to actually persist, or the throttle is a no-op and the "once per day"
 * name is a lie — the first implementation built a mutated map, discarded it, and
 * returned the original snapshot, so nothing was ever written and every call logged.
 */
@Tag("fast")
class LogEventOncePerDayTest {

    private fun store(dir: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(dir, "analytics.preferences_pb") }

    @Test
    fun `logs once and suppresses the rest of the same day`(
        @TempDir dir: File,
    ) = runTest {
        val analytics = RecordingAnalytics()
        val prefs = store(dir)

        assertTrue(analytics.logEventOncePerDay(prefs, "app_opened", "day", 20_000), "first call logs")
        assertFalse(analytics.logEventOncePerDay(prefs, "app_opened", "day", 20_000), "same day is suppressed")
        assertFalse(analytics.logEventOncePerDay(prefs, "app_opened", "day", 20_000), "still suppressed")

        assertEquals(listOf("app_opened"), analytics.events)
    }

    @Test
    fun `logs again on a new day`(
        @TempDir dir: File,
    ) = runTest {
        val analytics = RecordingAnalytics()
        val prefs = store(dir)

        assertTrue(analytics.logEventOncePerDay(prefs, "app_opened", "day", 20_000))
        assertTrue(analytics.logEventOncePerDay(prefs, "app_opened", "day", 20_001), "new day logs")

        assertEquals(2, analytics.events.size)
    }

    @Test
    fun `the marker survives the DataStore instance — it is on disk`(
        @TempDir dir: File,
    ) = runTest {
        val file = File(dir, "analytics.preferences_pb")

        // A DataStore over one file must be single-instance, so the only way to prove the
        // marker reached disk is to let the first one go and open a second. A scope we own
        // is what lets that happen without leaking a watcher.
        val firstScope = CoroutineScope(Job())
        val analytics = RecordingAnalytics()
        assertTrue(
            analytics.logEventOncePerDay(
                PreferenceDataStoreFactory.create(scope = firstScope) { file },
                "app_opened",
                "day",
                20_000,
            ),
        )
        val firstJob = firstScope.coroutineContext[Job]
        firstScope.cancel()
        firstJob?.join()

        val secondScope = CoroutineScope(Job())
        assertFalse(
            analytics.logEventOncePerDay(
                PreferenceDataStoreFactory.create(scope = secondScope) { file },
                "app_opened",
                "day",
                20_000,
            ),
            "A fresh instance must still see the marker, so the throttle really persisted",
        )
        secondScope.cancel()

        assertEquals(1, analytics.events.size)
    }

    @Test
    fun `separate dedupe keys throttle independently`(
        @TempDir dir: File,
    ) = runTest {
        val analytics = RecordingAnalytics()
        val prefs = store(dir)

        assertTrue(analytics.logEventOncePerDay(prefs, "app_opened", "a", 20_000))
        assertTrue(analytics.logEventOncePerDay(prefs, "app_opened", "b", 20_000))

        assertEquals(2, analytics.events.size)
    }

    @Test
    fun `separate events throttle independently`(
        @TempDir dir: File,
    ) = runTest {
        val analytics = RecordingAnalytics()
        val prefs = store(dir)

        assertTrue(analytics.logEventOncePerDay(prefs, "app_opened", "day", 20_000))
        assertTrue(analytics.logEventOncePerDay(prefs, "task_created", "day", 20_000))

        assertEquals(2, analytics.events.size)
    }

    @Test
    fun `the persisted key is the one the original implementation intended`(
        @TempDir dir: File,
    ) = runTest {
        val analytics = RecordingAnalytics()
        val prefs = store(dir)
        analytics.logEventOncePerDay(prefs, "app_opened", "day", 20_000)

        val stored = prefs.data.first()[longPreferencesKey("last_logged_app_opened:day")]
        assertEquals(20_000L, stored, "The marker must be written under the documented key")
    }
}
