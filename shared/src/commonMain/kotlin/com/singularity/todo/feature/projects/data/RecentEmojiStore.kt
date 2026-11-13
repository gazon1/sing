package com.singularity.todo.feature.projects.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import com.singularity.todo.core.datastore.catchDataStoreIoError
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.settings.SettingsNamespace
import com.singularity.todo.feature.projects.domain.logic.rememberRecentEmoji
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The emoji the user picked most recently, for the project icon picker.
 *
 * ## Why the settings file
 *
 * The history is a preference of a few glyphs, which is what the app's
 * `user_settings` DataStore is for. A store of its own would need its own
 * migration, its own behaviour when the settings are cleared and its own answer
 * to sign-out — for ten characters.
 *
 * ## Why one string
 *
 * The order is the whole point of a "recently used" list, and DataStore's own
 * string set does not keep one. The list is therefore stored as a single
 * string, most recent first, separated by [SEPARATOR] — a control character no
 * emoji contains, so a composed sequence stays one value.
 */
class RecentEmojiStore(
    private val dataStore: DataStore<Preferences>,
    private val log: Logger = Logger.withTag("RecentEmojiStore"),
) {
    /** The recently used emoji, most recent first. Empty until one is picked. */
    val recent: Flow<ImmutableList<String>> =
        dataStore.data
            .catchDataStoreIoError()
            .map { prefs -> decode(prefs[KEY]).toImmutableList() }

    /**
     * Records [emoji] as the most recently used one.
     *
     * A write that fails is logged, not thrown: the icon itself was already
     * picked, and the history is a convenience around it.
     */
    suspend fun record(emoji: String) {
        runCatchingCancellable {
            dataStore.edit { prefs ->
                val current = decode(prefs[KEY])
                prefs[KEY] = encode(rememberRecentEmoji(current, emoji))
            }
        }.onFailure { e ->
            log.w(e) { "Could not record a recently used emoji" }
        }
    }

    private fun decode(raw: String?): List<String> = raw?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    private fun encode(emojis: List<String>): String = emojis.joinToString(SEPARATOR)

    private companion object {
        val KEY =
            stringPreferencesKey(
                SettingsNamespace.key(SettingsNamespace.PROJECTS, "recent_emojis"),
            )

        /** ASCII unit separator — no emoji contains it. */
        const val SEPARATOR = "\u001F"
    }
}
