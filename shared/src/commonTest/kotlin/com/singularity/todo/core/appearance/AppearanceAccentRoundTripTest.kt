package com.singularity.todo.core.appearance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.singularity.todo.core.ui.theme.SingularityAccents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The accent a user picks is stored as a plain string and read back through
 * [SingularityAccents.fromString], so the only thing standing between "the user
 * chose pink" and "the app shows blue" is that those two functions agree on a
 * spelling.
 *
 * They used not to matter. The palette was two hardcoded schemes and the accent
 * reached only a CompositionLocal, so all nine choices rendered identically and a
 * broken round trip was invisible. Now that the accent actually drives the
 * palette, a value that fails to survive storage is a user-visible silent
 * downgrade — which is why this file exists rather than a note in the enum.
 *
 * Covers the two ways the value actually gets written: the settings screen, and a
 * settings import from a backup file.
 */
@Tag("fast")
class AppearanceAccentRoundTripTest {

    @Test
    fun `every accent survives storage under its own name`() = runTest {
        val repository = DataStoreAppearanceSettingsRepository(inMemoryDataStore())

        SingularityAccents.entries.forEach { accent ->
            repository.setAccentColor(accent.name)

            val stored = repository.accentColor.first()
            val resolved = SingularityAccents.fromString(stored)
            assertEquals(
                resolved,
                accent,
                "${accent.name} came back as $resolved after being stored as \"$stored\"",
            )
        }
    }

    @Test
    fun `the accent is not case-sensitive on the way back`() = runTest {
        // Backup files are hand-edited and older builds wrote different casing.
        SingularityAccents.entries.forEach { accent ->
            assertEquals(
                accent,
                SingularityAccents.fromString(accent.name.lowercase()),
                "${accent.name.lowercase()} did not resolve to $accent",
            )
            assertEquals(
                accent,
                SingularityAccents.fromString(accent.name.uppercase()),
                "${accent.name.uppercase()} did not resolve to $accent",
            )
        }
    }

    @Test
    fun `an accent that is not a known name falls back rather than throwing`() {
        // A settings import carries whatever the file says. A downgrade leaves the
        // store holding a name this build no longer has. Throwing here would take
        // the app down over a value the user cannot see or edit, so the fallback is
        // the correct behaviour — the *silence* of it is what item 5 of the plan
        // flags, not the fallback itself.
        assertEquals(SingularityAccents.Blue, SingularityAccents.fromString("chartreuse"))
        assertEquals(SingularityAccents.Blue, SingularityAccents.fromString(""))
    }

    @Test
    fun `the accent is genuinely part of the theme, not a constant`() {
        // The reason a broken round trip is user-visible. If this ever fails, the
        // palette has gone back to ignoring the accent and every test above is
        // asserting something that no longer matters.
        val colours = SingularityAccents.entries.map { it.color }

        assertEquals(
            colours.size,
            colours.toSet().size,
            "two accents render identically, so choosing between them does nothing",
        )
        SingularityAccents.entries
            .filter { it != SingularityAccents.Blue }
            .forEach { accent ->
                assertNotEquals(
                    SingularityAccents.Blue.color,
                    accent.color,
                    "$accent renders exactly like the default accent",
                )
            }
    }

    @Test
    fun `the stored default is an accent this build can resolve`() = runTest {
        val repository = DataStoreAppearanceSettingsRepository(inMemoryDataStore())

        val stored = repository.accentColor.first()

        // Compared as the resolved enum, not as a string: the stored spelling is
        // lowercase ("blue") and the enum's name is not, and pinning those to each
        // other would fail on a correct implementation.
        assertEquals(
            SingularityAccents.Blue,
            SingularityAccents.fromString(stored),
            "the shipped default \"$stored\" is not a name the enum can read back",
        )
    }

    private companion object {
        /** A [DataStore] held in memory, so the test touches no files. */
        fun inMemoryDataStore(
            initial: Preferences = emptyPreferences(),
        ): DataStore<Preferences> {
            val state = MutableStateFlow(initial)
            return object : DataStore<Preferences> {
                override val data: Flow<Preferences> = state
                override suspend fun updateData(
                    transform: suspend (Preferences) -> Preferences,
                ): Preferences = transform(state.value).also { state.value = it }
            }
        }
    }
}
