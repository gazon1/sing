package com.singularity.todo.core.di

import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.core.settings.SettingsMigrationGate
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.koinApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Building the JVM platform module must not touch the filesystem.
 *
 * The module used to launch the settings migration from its own body and to resolve
 * every path from `System.getProperty("user.home")`. Both happened when the module was
 * constructed — which every test that compares registrations does — so merely building
 * a graph could start a migration against the real settings files.
 *
 * The guard does not redirect `user.home`: on this source set classes run concurrently
 * in one JVM, so one test's redirect is another test's race — the very defect this is
 * meant to catch. It gives the module a temporary root instead and holds it there:
 * building creates nothing, and every path a binding hands out is under the root. The
 * resolution half — the database and the first-use migration — is in
 * [JvmPlatformModuleResolutionIsolationTest].
 */
@Tag("fast")
class JvmPlatformModuleConstructionIsolationTest {
    @Test
    fun `building the module creates nothing`(
        @TempDir tempRoot: Path,
    ) {
        val app = koinApplication { modules(listOf(jvmPlatformModule(tempRoot.toFile()))) }
        try {
            assertTrue(
                tempRoot.toFile().listFiles().orEmpty().isEmpty(),
                "building the graph created ${tempRoot.toFile().list()?.toList()} " +
                    "before anything was used",
            )
            assertFalse(
                app.koin.get<SettingsMigrationGate>().isStarted,
                "building the graph started the settings migration; it must start on " +
                    "first use of the settings",
            )
        } finally {
            app.close()
        }
    }

    @Test
    fun `every path binding is rooted at the temporary root`(
        @TempDir tempRoot: Path,
    ) {
        val app = koinApplication { modules(listOf(jvmPlatformModule(tempRoot.toFile()))) }
        try {
            val directories = listOf(
                BackupDirQualifier,
                LogDirQualifier,
                AttachmentDirQualifier,
            ).map { qualifier -> app.koin.get<String>(qualifier) }

            directories.forEach { directory ->
                assertTrue(
                    Path.of(directory).startsWith(tempRoot),
                    "directory binding '$directory' is outside the temporary root '$tempRoot'",
                )
            }

            val dataDirectory = app.koin.get<HostEnvironmentPort>().applicationDataDirectory()
            assertTrue(
                Path.of(dataDirectory).startsWith(tempRoot),
                "the host environment reports '$dataDirectory', outside the temporary root '$tempRoot'",
            )

            val attachments = app.koin.get<FileRevealer>().attachmentsBasePath()
            assertTrue(
                Path.of(attachments).startsWith(tempRoot),
                "the file revealer opens '$attachments', outside the temporary root '$tempRoot'",
            )
        } finally {
            app.close()
        }
    }
}
