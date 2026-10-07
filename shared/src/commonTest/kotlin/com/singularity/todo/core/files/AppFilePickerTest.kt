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

    // ── Attachment ──────────────────────────────────────────────────────────────
    //
    // This purpose did not exist until 2026-10-07, and its absence is why attaching a
    // file from the UI was impossible: there was no value a caller could pass, so no
    // caller could ask for a file. A file that cannot be chosen cannot be attached.

    @Test
    fun `attachment purpose filters to every extension the app knows`() {
        assertEquals(
            FileKitType.File(extensions = MimeTypes.supportedExtensions),
            FilePickPurpose.Attachment.fileKitType,
        )
    }

    /** FileKit's `extensions` is nullable; the attachment purpose must never pass null. */
    private fun attachmentExtensions(): Set<String> =
        requireNotNull((FilePickPurpose.Attachment.fileKitType as FileKitType.File).extensions) {
            "the attachment picker was built with no extension filter at all"
        }

    @Test
    fun `attachment filter never offers the empty extension`() {
        // MimeTypes.TABLE carries `"" to "application/octet-stream"` as a fallback row.
        // Passing that to FileKit asks it to match files whose extension is nothing.
        val extensions = attachmentExtensions()
        assertTrue(extensions.isNotEmpty())
        assertTrue(
            extensions.none { it.isBlank() },
            "the picker was offered a blank extension: $extensions",
        )
    }

    @Test
    fun `attachment filter offers the formats the settings help promises`() {
        val extensions = attachmentExtensions()
        for (expected in listOf("pdf", "docx", "txt", "png", "jpg", "zip")) {
            assertTrue(
                expected in extensions,
                "$expected is in MimeTypes.TABLE but missing from the attachment filter",
            )
        }
    }

    @Test
    fun `the attachment filter is not the backup or the settings filter`() {
        val attachment = FilePickPurpose.Attachment.fileKitType
        assertTrue(attachment != FilePickPurpose.Backup.fileKitType)
        assertTrue(attachment != FilePickPurpose.SettingsJson.fileKitType)
    }
}
