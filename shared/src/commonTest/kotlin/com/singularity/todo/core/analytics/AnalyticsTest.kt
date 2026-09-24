package com.singularity.todo.core.analytics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Tag

@Tag("slow")
class AnalyticsTest {

    @Test
    fun `NoopAnalytics logEvent does not throw`() {
        val analytics = NoopAnalytics()
        analytics.logEvent("test_event")
        analytics.logEvent("test_event", "key" to "value")
        analytics.identify("user-123")
    }

    @Test
    fun `NoopAnalytics logEventOncePerDay does not throw`() = runTest {
        val analytics = NoopAnalytics()
        val dataStore = testDataStore()
        analytics.logEventOncePerDay(dataStore, "event1", "default", today())
        analytics.logEventOncePerDay(dataStore, "event1", "default", today()) // second call same day — skipped
        analytics.logEventOncePerDay(dataStore, "event1", "default", today() + 1) // next day — allowed
    }

    @Test
    fun `logEventOncePerDay writes to preferences on first call`() = runTest {
        val analytics = NoopAnalytics()
        val dataStore = testDataStore()
        val key = longPreferencesKey("last_logged_event1:default")

        assertNull(dataStore.data.first()[key], "precondition: key should not exist")

        analytics.logEventOncePerDay(dataStore, "event1", "default", today())

        assertEquals(today(), dataStore.data.first()[key], "first call should write today's epoch day")
    }

    @Test
    fun `logEventOncePerDay skips on same day second call`() = runTest {
        val analytics = NoopAnalytics()
        val dataStore = testDataStore(
            mutablePreferencesOf(
            longPreferencesKey("last_logged_event1:default") to today(),
        )
        )
        val key = longPreferencesKey("last_logged_event1:default")

        // Should not update — just skip
        analytics.logEventOncePerDay(dataStore, "event1", "default", today())

        assertEquals(today(), dataStore.data.first()[key], "same day call should not update")
    }

    @Test
    fun `logEventOncePerDay allows on next day`() = runTest {
        val analytics = NoopAnalytics()
        val dataStore = testDataStore(
            mutablePreferencesOf(
            longPreferencesKey("last_logged_event2:default") to today(),
        )
        )
        val key = longPreferencesKey("last_logged_event2:default")

        analytics.logEventOncePerDay(dataStore, "event2", "default", today() + 1)

        assertEquals(today() + 1, dataStore.data.first()[key], "next day call should update")
    }

    private fun testDataStore(initial: Preferences = mutablePreferencesOf()): DataStore<Preferences> =
        object : DataStore<Preferences> {
            private val flow = MutableStateFlow(initial)
            override val data: Flow<Preferences> = flow
            override suspend fun updateData(reduce: suspend (Preferences) -> Preferences): Preferences {
                val current = flow.value
                val updated = reduce(current)
                flow.value = updated
                return updated
            }
        }
}

private fun today(): Long = java.time.Instant.now().epochSecond / 86_400L
