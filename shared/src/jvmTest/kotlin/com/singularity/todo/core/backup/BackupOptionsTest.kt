package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag

@Tag("slow")
class BackupOptionsTest {

    @Test
    fun `exportOptions builds valid ExportOptions`() {
        val opts = exportOptions {
            userId = UserId.anonymous
            destPath = "/tmp/backup.zip"
            includeAttachments = true
            appVersion = "1.0.0"
        }
        assertEquals(UserId.anonymous, opts.userId)
        assertEquals("/tmp/backup.zip", opts.destPath)
        assertEquals(true, opts.includeAttachments)
        assertEquals("1.0.0", opts.appVersion)
    }

    @Test
    fun `exportOptions defaults includeAttachments to true`() {
        val opts = exportOptions {
            userId = UserId.anonymous
            destPath = "/tmp/backup.zip"
        }
        assertEquals(true, opts.includeAttachments)
        assertEquals("0.0.11", opts.appVersion)
    }

    @Test
    fun `importOptions builds valid ImportOptions`() {
        val opts = importOptions {
            sourcePath = "/tmp/backup.zip"
            targetUserId = UserId.anonymous
            overwriteExisting = true
        }
        assertEquals("/tmp/backup.zip", opts.sourcePath)
        assertEquals(UserId.anonymous, opts.targetUserId)
        assertEquals(true, opts.overwriteExisting)
    }

    @Test
    fun `importOptions defaults overwriteExisting to true`() {
        val opts = importOptions {
            sourcePath = "/tmp/backup.zip"
            targetUserId = UserId.anonymous
        }
        assertEquals(true, opts.overwriteExisting)
    }

    @Test
    fun `BackupId fromPath extracts name correctly`() {
        val id = BackupId.fromPath("/backups/singularity-2024-01-15.zip")
        assertEquals("singularity-2024-01-15", id.value)
    }
}
