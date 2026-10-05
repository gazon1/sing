package com.singularity.todo.core.analytics

// Provenance: ADAPTED from Tasks.org (GPL-3.0) — interface shape and method names only.
//   Full registry: docs/legal/PROVENANCE.md

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey

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
 * ## Why the read-modify-write happens inside `updateData`
 *
 * The obvious spelling reads the day, compares, logs, then writes in a *separate*
 * `updateData` call. That is a read-modify-write race: two coroutines can both read
 * yesterday's value and both log. `updateData` serialises the whole
 * read-compare-write against other writers, and its return value is the state that was
 * actually committed — so "did I already log today?" is answered from the committed
 * snapshot, not from a value that may already be stale.
 *
 * The event is logged from inside the transform. `updateData` may retry, and DataStore
 * documents that its block must be pure — so a duplicate log is possible in principle.
 * That is the deliberate trade: the alternative loses events, and analytics is
 * best-effort by nature. Do not put anything here that is not safe to repeat.
 *
 * @param preferences  DataStore preferences for persisting the last-logged day.
 * @param event       The analytics event name to fire if the day has changed.
 * @param dedupeBy    Secondary key to allow multiple events to share a day-counter
 *                    while remaining independently throttled.
 * @param todayEpochDay Today's epoch day number (from `date.toEpochDays()`).
 * @param params      Additional event parameters passed to [Analytics.logEvent].
 * @return `true` if the event was logged by this call, `false` if it was already
 *   logged today.
 */
suspend fun Analytics.logEventOncePerDay(
    preferences: DataStore<Preferences>,
    event: String,
    dedupeBy: String,
    todayEpochDay: Long,
    vararg params: Pair<String, Any>,
): Boolean {
    val key = longPreferencesKey("last_logged_$event:$dedupeBy")
    var logged = false
    preferences.updateData { prefs ->
        if ((prefs[key] ?: 0L) >= todayEpochDay) {
            prefs // already logged today — return unchanged
        } else {
            logged = true
            prefs.toMutablePreferences().apply { this[key] = todayEpochDay }
        }
    }
    if (logged) logEvent(event, *params)
    return logged
}
