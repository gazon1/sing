package com.singularity.todo.feature.flows

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.files.FileOpener
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Verifies that every platform port in the test graph resolves to a test double,
 * not a production implementation that would touch the filesystem or OS.
 *
 * On JVM, the real implementations would call `secret-tool`, `notify-send`, `at`,
 * or read `System.getProperty("user.home")` — all process-global. If any escaped
 * into the test graph, one test's temp-dir mutation would corrupt another test's
 * read, surfacing as SQLite "database is locked" errors.
 *
 * ## How to add a new port
 *
 * 1. Add an assertion below using [assertIsBoundTo].
 * 2. If the test goes red, the error message names the leaked port and its actual
 *    class — add the missing test double to [test helpers][TestPlatformModule].
 * 3. If the double is a production no-op (e.g. `NoopCalendarProvider`), document
 *    the reason in a comment.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class PlatformParityTest {

    @Test
    fun `platform ports resolve to test doubles`() = runDesktopAppTest { koin ->
        // Data layer — FakeAppDatabase replaces Room SQLite
        assertIsBoundTo<AppDatabase>(koin, "FakeAppDatabase")

        // OS-access ports: would touch filesystem, secrets, or notifications
        assertIsBoundTo<AuthRepository>(koin, "FakeAuthRepository")
        assertIsBoundTo<SecureStoragePort>(koin, "InMemorySecureStorage")
        assertIsBoundTo<FileSystem>(koin, "InMemoryFileSystem")
        assertIsBoundTo<FileRevealer>(koin, "FakeFileRevealer")
        // Opening an attachment for real would hand a file to the desktop's file
        // associations — process-global, and the point of this test is that it cannot.
        assertIsBoundTo<FileOpener>(koin, "FakeFileOpener")
        assertIsBoundTo<FileSourceFactory>(koin, "InertFileSourceFactory")
        assertIsBoundTo<SharePort>(koin, "InertSharePort")
        assertIsBoundTo<BackupCodec>(koin, "UnusedBackupCodec")
        assertIsBoundTo<RemoteConfigPort>(koin, "FakeRemoteConfigPort")

        // Calendar sync — no JVM implementation; production no-op is the correct double
        assertIsBoundTo<CalendarProviderPort>(koin, "NoopCalendarProvider")
    }
}

/**
 * Asserts that a port is bound to a test double of [expectedClassName].
 *
 * Usage: `assertIsBoundTo<AuthRepository>(koin, "FakeAuthRepository")`
 *
 * The reified type `T` is resolved from the call site so the function signature
 * stays simple. If the assertion fails, the error message names the port, expected
 * class, and actual class — no need to consult the map source to interpret a failure.
 */
private inline fun <reified T : Any> assertIsBoundTo(
    koin: org.koin.core.Koin,
    expectedClassName: String,
) {
    val actual = koin.get<T>()::class.simpleName
    check(actual == expectedClassName) {
        "Platform port ${T::class.simpleName} resolved to `$actual`, expected `$expectedClassName`. " +
            "Add a test double to testPlatformModule() and update this assertion."
    }
}
