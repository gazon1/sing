package com.singularity.todo.feature.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The active profile's own file, and the move out of the file it used to live in.
 *
 * ## What is under test
 *
 * The key that says which profile the user is working in used to live in the settings
 * file — the file every process that needs the AI configuration opens. It lives in a
 * file of its own now, so that a second process can open the settings without opening
 * the profile.
 *
 * The move is the part that can go wrong silently: a user whose profile is still in
 * the old file must not lose it, and the old file must not keep saying which profile
 * they are working in.
 */
@Tag("fast")
class ActiveProfileStateTest {

    private fun store(dir: File, name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(dir, name) }

    @Test
    fun `a profile stored in the settings file is moved into its own file`(
        @TempDir dir: File,
    ) = runTest {
        val legacy = store(dir, "user_settings.preferences_pb")
        legacy.edit { it[ACTIVE_PROFILE_KEY] = STORED_PROFILE }
        val own = store(dir, "profile_state.preferences_pb")

        val state = ActiveProfileState(own, legacy)

        assertEquals(ProfileId(STORED_PROFILE), state.ids.first(), "the profile the user was working in")
        assertEquals(STORED_PROFILE, own.data.first()[ACTIVE_PROFILE_KEY], "and it is in its own file now")
        assertNull(legacy.data.first()[ACTIVE_PROFILE_KEY], "and the settings file no longer says")
    }

    @Test
    fun `a profile already in its own file is read from there`(
        @TempDir dir: File,
    ) = runTest {
        val legacy = store(dir, "user_settings.preferences_pb")
        val own = store(dir, "profile_state.preferences_pb")
        own.edit { it[ACTIVE_PROFILE_KEY] = STORED_PROFILE }

        val state = ActiveProfileState(own, legacy)

        assertEquals(ProfileId(STORED_PROFILE), state.ids.first(), "the stored profile is reported")
        assertNull(legacy.data.first()[ACTIVE_PROFILE_KEY], "and nothing was moved into the settings file")
    }

    @Test
    fun `nothing stored anywhere means the default profile`(
        @TempDir dir: File,
    ) = runTest {
        val legacy = store(dir, "user_settings.preferences_pb")
        val own = store(dir, "profile_state.preferences_pb")

        val state = ActiveProfileState(own, legacy)

        assertEquals(ProfileId.default, state.ids.first(), "the default profile is the answer when nothing is stored")
    }

    @Test
    fun `writing a profile leaves the settings file alone`(
        @TempDir dir: File,
    ) = runTest {
        val legacy = store(dir, "user_settings.preferences_pb")
        val own = store(dir, "profile_state.preferences_pb")

        val state = ActiveProfileState(own, legacy)
        state.write(ProfileId(STORED_PROFILE))

        assertEquals(STORED_PROFILE, own.data.first()[ACTIVE_PROFILE_KEY], "the profile is stored where it belongs")
        assertTrue(legacy.data.first().asMap().isEmpty(), "and the settings file says nothing about the profile")
    }

    @Test
    fun `a process with no old file to move from never looks for one`(
        @TempDir dir: File,
    ) = runTest {
        val own = store(dir, "profile_state.preferences_pb")

        // The shape a process that was told which profile to work as gets: no legacy
        // store at all, because it has no business opening the file that holds one.
        val state = ActiveProfileState(own, legacyStore = null)

        assertEquals(ProfileId.default, state.ids.first(), "and it reads its own file, which is empty")
    }
}

/**
 * The key the active profile is stored under.
 *
 * Spelled out here rather than imported, because the *name* is the contract: the
 * settings file of a user who ran the previous version holds this key, and the move
 * only finds it if the name is the same one.
 */
private val ACTIVE_PROFILE_KEY = stringPreferencesKey("active_profile_id")

/** The profile id a user in the test is working in. */
private const val STORED_PROFILE = "01M2167YM7PKJRNS40413DXFRS"
