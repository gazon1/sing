package com.singularity.todo.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupFileNamerTest {

    @Test
    fun `nextBackupName includes timestamp in filename`() {
        val namer = DefaultBackupFileNamer
        val name = namer.nextBackupName(1700000000000)
        assertEquals("singularity_backup_1700000000000.zip", name)
    }

    @Test
    fun `nextBackupName is deterministic for same timestamp`() {
        val namer = DefaultBackupFileNamer
        val name1 = namer.nextBackupName(1700000000000)
        val name2 = namer.nextBackupName(1700000000000)
        assertEquals(name1, name2)
    }

    @Test
    fun `nextBackupName differs for different timestamps`() {
        val namer = DefaultBackupFileNamer
        val name1 = namer.nextBackupName(1700000000000)
        val name2 = namer.nextBackupName(1700000000001)
        assertTrue(name1 != name2)
    }
}
