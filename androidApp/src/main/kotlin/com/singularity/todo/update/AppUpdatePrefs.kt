package com.singularity.todo.update

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight preferences for update gating, backed by SharedPreferences.
 *
 * SharedPreferences (not DataStore) is intentional:
 * - Only 2 fields, no transactions needed
 * - Already available as a transitive dependency of the Play Core library
 */
class AppUpdatePrefs private constructor(private val prefs: SharedPreferences) {

    /** Returns the epoch millis of the last update offer, or 0 if never. */
    fun lastOfferedAt(): Long = prefs.getLong(KEY_LAST_OFFERED_AT, 0L)

    /** Days elapsed since the last offer. Returns [Long.MAX_VALUE] if never offered. */
    fun daysSinceLastOffer(): Long {
        val last = lastOfferedAt()
        if (last == 0L) return Long.MAX_VALUE
        val now = System.currentTimeMillis()
        return java.util.concurrent.TimeUnit.MILLISECONDS.toDays(now - last)
    }

    /** Records that an update offer was made. */
    fun recordUpdateOffered() {
        prefs.edit().putLong(KEY_LAST_OFFERED_AT, System.currentTimeMillis()).apply()
    }

    /** Clears the cooldown, forcing the next eligible update to show immediately. */
    fun clearCooldown() {
        prefs.edit().remove(KEY_LAST_OFFERED_AT).apply()
    }

    companion object {
        private const val KEY_LAST_OFFERED_AT = "update_last_offered_at"
        private const val PREFS_FILE = "app_update_prefs"

        fun create(context: Context): AppUpdatePrefs = AppUpdatePrefs(
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE),
        )
    }
}
