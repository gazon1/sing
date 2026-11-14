package com.singularity.todo.feature.projects.presentation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.coroutines.loggingBackgroundFailureHandler
import com.singularity.todo.core.datastore.catchDataStoreIoError
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.settings.SettingsNamespace
import com.singularity.todo.feature.projects.domain.logic.normalizeRecentEmoji
import com.singularity.todo.feature.projects.domain.logic.rememberRecentEmoji
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The emoji the user picked most recently, for the project icon picker.
 *
 * ## Why it lives in the presentation layer
 *
 * It is a settings holder the screen reads and the ViewModel writes, and the
 * feature's data layer is off limits to the presentation layer — so it sits
 * beside the screen, the way `WhatsNewPrefs` does for the "what's new" notice.
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
 * string, most recent first, separated by [SEPARATOR].
 *
 * ## Why the value is escaped
 *
 * The picker takes free text as a custom emoji, so a stored value is not
 * necessarily one glyph: it can hold anything the user typed. A value that
 * contains the separator would split the list in two on the way back, so the
 * separator and the escape character itself are written as text and turned
 * back on the way in. A composed sequence — a ZWJ family, a flag — contains
 * neither and stays one value.
 *
 * ## Why the writes are queued
 *
 * [record] is not a suspending call: the icon the user picked must be applied
 * without waiting for the disk, and a sheet that is dismissed the moment the
 * pick happens must not cancel the write. The store therefore owns a scope
 * that lives as long as the store, and the writes are applied one after
 * another, in the order they were recorded.
 */
class RecentEmojiStore(
    private val dataStore: DataStore<Preferences>,
    private val log: Logger = Logger.withTag("RecentEmojiStore"),
    private val scope: CoroutineScope = createBackgroundScope(loggingBackgroundFailureHandler()),
) {
    /**
     * The recorded emoji, waiting to be written.
     *
     * A queue rather than a launch per record: two picks in a row have to end
     * up in the order they were made, and only one writer at a time can
     * guarantee that.
     */
    private val pending = Channel<String>(Channel.UNLIMITED)

    /** The recently used emoji, most recent first. Empty until one is picked. */
    val recent: Flow<ImmutableList<String>> =
        dataStore.data
            .catchDataStoreIoError()
            .map { prefs -> decode(prefs[KEY]).toImmutableList() }

    /**
     * Records [emoji] as the most recently used one.
     *
     * Returns without waiting for the write: the icon was picked, and the
     * history is a convenience around it. A write that fails is logged, not
     * thrown.
     */
    fun record(emoji: String) {
        if (emoji.isBlank()) return
        pending.trySend(emoji)
    }

    /**
     * Records [icon] when it is an emoji pick.
     *
     * "No icon at all" is not a pick and is not remembered.
     */
    fun recordPickedIcon(icon: Emoji?) {
        val emoji = icon?.value?.takeIf { it.isNotBlank() } ?: return
        record(emoji)
    }

    private suspend fun write(emoji: String) {
        runCatchingCancellable {
            dataStore.edit { prefs ->
                val current = decode(prefs[KEY])
                prefs[KEY] = encode(rememberRecentEmoji(current, emoji))
            }
        }.onFailure { e ->
            log.w(e) { "Could not record a recently used emoji" }
        }
    }

    /**
     * Reads the history back: the stored values, unescaped, normalized.
     *
     * Reading normalizes because what is on disk is not always what was
     * written — an older version kept a longer list, and a hand-edited
     * settings file can hold anything.
     */
    private fun decode(raw: String?): List<String> =
        raw
            ?.split(SEPARATOR)
            ?.map { unescape(it) }
            ?.let { normalizeRecentEmoji(it) }
            ?: emptyList()

    private fun encode(emojis: List<String>): String = emojis.joinToString(SEPARATOR) { escape(it) }

    /** Writes the separator and the escape character as text. */
    private fun escape(value: String): String =
        value
            .replace(ESCAPE, ESCAPED_ESCAPE)
            .replace(SEPARATOR, ESCAPED_SEPARATOR)

    /** Turns the text written by [escape] back into the value it stands for. */
    private fun unescape(value: String): String {
        if (!value.contains(ESCAPE)) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char != ESCAPE[0] -> {
                    out.append(char)
                    index++
                }

                value.startsWith(ESCAPED_ESCAPE, index) -> {
                    out.append(ESCAPE)
                    index += ESCAPED_ESCAPE.length
                }

                value.startsWith(ESCAPED_SEPARATOR, index) -> {
                    out.append(SEPARATOR)
                    index += ESCAPED_SEPARATOR.length
                }

                // An escape nobody wrote: kept as it is, so a value is never
                // lost to a format that did not expect it.
                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return out.toString()
    }

    /**
     * The writer: the one coroutine that applies the recorded emoji, one after
     * another, in the order they were recorded.
     *
     * A property initializer rather than a `launch` in an `init` block: both
     * start during construction, but the initializer runs after the fields
     * above are assigned, so the writer can never observe a half-built store.
     * The job is held so the writer can be reasoned about from the field.
     */
    private val writer =
        scope.launch {
            for (emoji in pending) {
                write(emoji)
            }
        }

    private companion object {
        val KEY =
            stringPreferencesKey(
                SettingsNamespace.key(SettingsNamespace.PROJECTS, "recent_emojis"),
            )

        /** ASCII unit separator — a control character, not text a user types. */
        const val SEPARATOR = "\u001F"

        /** The escape character: a backslash. */
        const val ESCAPE = "\\"

        /** An escaped backslash. */
        const val ESCAPED_ESCAPE = "\\\\"

        /** An escaped separator, written as text. */
        const val ESCAPED_SEPARATOR = "\\u001F"
    }
}
