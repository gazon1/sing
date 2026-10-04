package com.singularity.todo.core.files

import io.github.vinceglb.filekit.dialogs.FileKitType
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("fast")
class AppFilePickerTest {
    @Test
    fun `backup purpose filters to zip archives`() {
        assertEquals(FileKitType.File(extensions = setOf("zip")), FilePickPurpose.Backup.fileKitType)
    }

    @Test
    fun `settings purpose filters to json files`() {
        assertEquals(FileKitType.File(extensions = setOf("json")), FilePickPurpose.SettingsJson.fileKitType)
    }

    @Test
    fun `every purpose has a non-blank dialog title`() {
        FilePickPurpose.entries.forEach { purpose ->
            assertTrue(purpose.title.isNotBlank(), "missing dialog title for $purpose")
        }
    }

    @Test
    fun `backup and settings filters do not overlap`() {
        val backup = FilePickPurpose.Backup.fileKitType
        val settings = FilePickPurpose.SettingsJson.fileKitType
        assertTrue(backup != settings)
    }
}
