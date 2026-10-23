package com.singularity.todo.feature.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.error.runCatchingCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The active profile, in a file of its own.
 *
 * ## Why a file of its own
 *
 * The active profile used to live in the settings file, next to the appearance and the
 * notification settings. That file is opened by anything that needs the AI
 * configuration — and the tool server an external agent is handed needs exactly that,
 * because the tools it exposes are backed by the model's provider.
 *
 * So the tool server opened the file that held the active profile, and the rule that
 * says it must not ("a process told which profile to serve never opens the
 * application's stored active-profile state") was a rule about *not opening a file*,
 * which a reader of the settings file breaks by opening it. The rule was never about
 * the key: the tool server never read it and never wrote it. But a rule that is
 * satisfied by an intention rather than by a fact is not a rule.
 *
 * The key has its own file now, and the file holds nothing else. A process that works
 * against a profile it was told about never resolves this store at all, so the file
 * that says which profile the user is working in stays closed to it — and the settings
 * file, which it may open for the AI configuration, no longer says anything about the
 * profile at all.
 *
 * ## Why the key is still in DataStore
 *
 * The active profile survives a database wipe: the profile records are in Room, and a
 * wipe of the profile's content must not send the user back to the default profile.
 *
 * ## The move out of the old file
 *
 * The key used to be in the settings file, and a user who has been running the
 * application has it there. The first read after the move looks in the new file, and
 * if the key is not there it looks in the old one, once, and moves it: the value is
 * written to the new file and taken out of the old one. A user who never ran the old
 * version has nothing to move, and the old file is not read at all.
 */
internal class ActiveProfileState(
    private val store: DataStore<Preferences>,
    /**
     * The file the key used to live in, or null when there is nothing to move.
     *
     * Null in a process that was told which profile to work as: such a process has no
     * active profile of its own, and it must not open the file that holds one.
     */
    private val legacyStore: DataStore<Preferences>? = null,
) {

    /**
     * The stored profile, following the file as it changes.
     *
     * The first emission is what is stored — the value that was moved out of the old
     * file, if there was one — and the default profile when nothing is stored at all.
     * After that, the flow follows the file: a profile the user switches to in the
     * application is a profile this flow reports.
     */
    val ids: Flow<ProfileId> = flow {
        val stored = store.data.first()[ACTIVE_PROFILE_ID] ?: moveOutOfTheLegacyFile()
        emit(stored?.let(ProfileId::fromString) ?: ProfileId.default)
        emitAll(
            store.data.map { prefs ->
                prefs[ACTIVE_PROFILE_ID]?.let(ProfileId::fromString) ?: ProfileId.default
            },
        )
    }

    /** Stores the profile the user switched to. */
    suspend fun write(id: ProfileId) {
        store.edit { it[ACTIVE_PROFILE_ID] = id.value }
    }

    /**
     * Moves the key out of the file it used to share with the settings.
     *
     * @return the value that was moved, or null when there was nothing to move.
     */
    private suspend fun moveOutOfTheLegacyFile(): String? {
        val legacy = legacyStore ?: return null
        val stored = runCatchingCancellable { legacy.data.first()[ACTIVE_PROFILE_ID] }.getOrNull() ?: return null

        // The write is conditional: a profile the user switched to in the meantime
        // wins, and the value that is being moved is only a fallback.
        store.edit { prefs ->
            if (ACTIVE_PROFILE_ID !in prefs) prefs[ACTIVE_PROFILE_ID] = stored
        }
        // And the key leaves the file that other processes open. Leaving it there
        // would keep the old file a place where the active profile can be found —
        // which is the thing this file was moved out of.
        runCatchingCancellable { legacy.edit { it.remove(ACTIVE_PROFILE_ID) } }
        return stored
    }
}

/** The key the active profile is stored under. */
private val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
