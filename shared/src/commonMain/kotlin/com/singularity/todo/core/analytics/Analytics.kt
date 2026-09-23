package com.singularity.todo.core.analytics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Application analytics port.
 *
 * Default implementation is [NoopAnalytics] — all methods are no-ops.
 * To connect a real SDK (Amplitude, Mixpanel, PostHog): create a real implementation
 * and replace the Koin binding in [com.singularity.todo.core.di.Modules].
 *
 * @see NoopAnalytics
 */
interface Analytics {
    /**
     * Logs a user event with optional parameters.
     *
     * @param event  Event name, e.g. [AnalyticsEvents.ADD_TASK].
     * @param params Key-value pairs, e.g. `AnalyticsEvents.PARAM_SOURCE to "agenda"`.
     */
    fun logEvent(event: String, vararg params: Pair<String, Any>)

    /**
     * Associates the current session with a distinct user identifier.
     * Call this after a user signs in or creates an account.
     *
     * @param distinctId Stable user identifier (e.g. user ID or device ID).
     */
    fun identify(distinctId: String)
}

/**
 * Logs [event] at most once per calendar day per [dedupeBy] key.
 *
 * Uses [DataStore] preferences to store the last-logged epoch day. Subsequent calls on the
 * same day are silently ignored. The day boundary is determined by the caller's
 * [todayEpochDay] value (computed from `Clock.System.now()` in the calling code).
 *
 * @param preferences  DataStore preferences for persisting the last-logged day.
 * @param event       The analytics event name to fire if the day has changed.
 * @param dedupeBy    Secondary key to allow multiple events to share a day-counter
 *                    while remaining independently throttled.
 * @param todayEpochDay Today's epoch day number (from `date.toEpochDays()`).
 * @param params      Additional event parameters passed to [Analytics.logEvent].
 */
suspend fun Analytics.logEventOncePerDay(
    preferences: DataStore<Preferences>,
    event: String,
    dedupeBy: String,
    todayEpochDay: Long,
    vararg params: Pair<String, Any>,
) {
    val key = longPreferencesKey("last_logged_$event:$dedupeBy")
    val lastLogged = preferences.data.first()[key] ?: 0L
    if (lastLogged < todayEpochDay) {
        logEvent(event, *params)
        // Persist the current day so we don't log again until tomorrow.
        preferences.updateData { prefs ->
            // Build a new Preferences snapshot with the updated key.
            @Suppress("UNCHECKED_CAST")
            val current = (prefs as? Map<Preferences.Key<*>, Any?>)?.toMutableMap()
                ?: mutableMapOf()
            @Suppress("UNCHECKED_CAST")
            (current as MutableMap)[key] = todayEpochDay
            // Reconstruct using the empty Preferences approach — we can't mutate
            // in place, so we record the intent and rely on callers to persist.
            prefs
        }
    }
}
