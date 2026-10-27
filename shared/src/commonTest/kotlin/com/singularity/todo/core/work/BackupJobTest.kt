package com.singularity.todo.core.work

import com.singularity.todo.core.backup.BackupFormat
import com.singularity.todo.core.backup.BackupManifest
import com.singularity.todo.core.backup.BackupResult
import com.singularity.todo.core.backup.EntityCounts
import com.singularity.todo.test.fakes.FakeBackupRepository
import com.singularity.todo.test.fakes.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The daily backup run, and the cloud half of it.
 *
 * ## What this is about
 *
 * A local backup is a file on the device, and the device dies. So the daily run
 * also pushes to the cloud — but only when the device may seal archives on its
 * own. The check is the repository's, and the job asks it as a question; the
 * job does not know what a key is.
 *
 * Three cases are worth holding to:
 *
 * 1. auto-backup off → nothing happens at all;
 * 2. auto-backup on, device not authorized → the local backup is made and the
 *    cloud is left alone (there is no key to seal with);
 * 3. auto-backup on, device authorized → the cloud gets the archive, and nobody
 *    was there to type a password.
 */
@Tag("fast")
class BackupJobTest {

    @Test
    fun `nothing happens when the auto backup is off`() = runTest {
        val repository = FakeBackupRepository()
        val settings = FakeSettingsRepository()

        val outcome = BackupJob(repository, settings).run()

        assertIs<JobOutcome.Success>(outcome)
        assertEquals(0, repository.exportCount)
        assertEquals(0, repository.scheduledPushCount)
    }

    @Test
    fun `the local backup is made and the cloud is left alone when the device is not authorized`() = runTest {
        val repository = FakeBackupRepository()
        repository.exportResult = Result.success(fakeBackupResult())
        val settings = FakeSettingsRepository()
        settings.setAutoBackupEnabled(true)

        val outcome = BackupJob(repository, settings).run()

        assertIs<JobOutcome.Success>(outcome)
        assertEquals(1, repository.exportCount)
        assertEquals(
            0,
            repository.scheduledPushCount,
            "a device with no key must not push: the archive would be sealed under nothing",
        )
    }

    @Test
    fun `the cloud gets the archive when the device is authorized`() = runTest {
        val repository = FakeBackupRepository()
        repository.exportResult = Result.success(fakeBackupResult())
        repository.scheduledBackupAuthorized = true
        val settings = FakeSettingsRepository()
        settings.setAutoBackupEnabled(true)

        val outcome = BackupJob(repository, settings).run()

        assertIs<JobOutcome.Success>(outcome)
        assertEquals(1, repository.exportCount)
        assertEquals(1, repository.scheduledPushCount)
    }

    @Test
    fun `a failed push is a failed run`() = runTest {
        val repository = FakeBackupRepository()
        repository.exportResult = Result.success(fakeBackupResult())
        repository.scheduledBackupAuthorized = true
        repository.pushScheduledResult = Result.failure(IllegalStateException("the bucket refused the write"))
        val settings = FakeSettingsRepository()
        settings.setAutoBackupEnabled(true)

        val outcome = BackupJob(repository, settings).run()

        assertIs<JobOutcome.Failed>(outcome)
        assertTrue(
            outcome.error.message?.contains("the bucket refused the write") == true,
            "the failure must say what happened: ${outcome.error.message}",
        )
    }

    /** A result the fake hands back for a made archive. */
    private fun fakeBackupResult(): BackupResult = BackupResult(
        manifest = BackupManifest(
            formatVersion = BackupFormat.FORMAT_VERSION,
            appName = BackupFormat.APP_NAME,
            appVersion = "1",
            createdAtEpochMillis = 0L,
            userIdHash = "hash",
            schemaVersion = BackupFormat.SCHEMA_VERSION,
            entityCounts = EntityCounts(),
            payloadChecksum = "checksum",
        ),
        destPath = "/backups/singularity_backup_0.zip",
        byteSize = 0L,
    )
}
