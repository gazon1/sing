package com.singularity.todo.core.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.settings.SettingsMigrationGate
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.koinApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Resolving the JVM platform bindings against a temporary root stays inside it.
 *
 * This is the half the fast construction test cannot see: resolving a binding runs its
 * factory body, and that is where the database used to be wiped and opened. A module
 * that reads the process-global home again is caught here as a missing file — the
 * database does not appear under the root it was given. The test does not redirect
 * `user.home` itself: classes in this source set run concurrently in one JVM, so a
 * redirect is a race with everything else.
 */
@Tag("slow")
class JvmPlatformModuleResolutionIsolationTest {
    @Test
    fun `the database is created under the temporary root`(
        @TempDir tempRoot: Path,
    ) {
        val app = koinApplication { modules(listOf(jvmPlatformModule(tempRoot.toFile()))) }
        try {
            val database = app.koin.get<AppDatabase>()
            try {
                assertTrue(
                    Files.exists(tempRoot.resolve(".singularity-todo/singularity-todo.db")),
                    "resolving the database did not create it under '$tempRoot'; the binding " +
                        "is resolving a path the module was not given",
                )
            } finally {
                database.close()
            }
        } finally {
            app.close()
        }
    }

    @Test
    fun `the settings migration starts on first use, not at construction`(
        @TempDir tempRoot: Path,
    ) {
        val app = koinApplication { modules(listOf(jvmPlatformModule(tempRoot.toFile()))) }
        try {
            assertFalse(
                app.koin.get<SettingsMigrationGate>().isStarted,
                "constructing the graph started the settings migration",
            )

            app.koin.get<DataStore<Preferences>>()

            assertTrue(
                app.koin.get<SettingsMigrationGate>().isStarted,
                "resolving the settings did not start the settings migration",
            )
        } finally {
            app.close()
        }
    }
}
