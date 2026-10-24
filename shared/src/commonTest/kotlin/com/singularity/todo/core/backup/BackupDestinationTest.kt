package com.singularity.todo.core.backup

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Where a backup may be written.
 *
 * ## What was broken
 *
 * The ViewModel passed a bare file name as the destination and the repository
 * wrote it *as given* — so the archive went to the filesystem root. On Android
 * that root is read-only, and the export failed with `EROFS`: a backup that
 * reported a failure the user could do nothing about.
 *
 * The rule is now that the repository owns the directory and the caller names
 * the file inside it. A path is rejected rather than followed, so the mistake
 * becomes a loud one instead of a silent write outside the backup directory.
 */
@Tag("fast")
class BackupDestinationTest {

    @Test
    fun `a file name lands inside the backup directory`() {
        assertEquals(
            "/data/backups/singularity_backup_1.zip",
            resolveBackupDestination("/data/backups", "singularity_backup_1.zip"),
        )
    }

    /** A path with a separator is a path, and paths are refused. */
    @Test
    fun `a path is refused instead of being followed`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            resolveBackupDestination("/data/backups", "../elsewhere/backup.zip")
        }

        assertTrue(failure.message.orEmpty().contains("file name"), "got ${failure.message}")
    }

    /** The same on the other separator — a Windows-style path is still a path. */
    @Test
    fun `a backslash is refused too`() {
        assertFailsWith<IllegalArgumentException> {
            resolveBackupDestination("/data/backups", "sub\\backup.zip")
        }
    }

    /** A blank name is not a name. */
    @Test
    fun `a blank file name is refused`() {
        assertFailsWith<IllegalArgumentException> {
            resolveBackupDestination("/data/backups", "   ")
        }
    }
}
