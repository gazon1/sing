package com.singularity.todo.core.attachments

import org.junit.jupiter.api.Tag
import com.singularity.todo.core.ui.formatFileSize
fb90fbc7 (refactor(ui): merge four formatter copies, and correct a finding I misread)
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("fast")
class AttachmentDomainTest {

    @Test
    fun generateAttachmentIdCreatesValidFormat() {
        val id = AttachmentDomain.generateAttachmentId()
        assertTrue(id.value.startsWith("att_"))
        assertEquals(36 + 4, id.value.length) // UUID is 36 chars + "att_" prefix
    }

    @Test
    fun generateAttachmentIdProducesUniqueIds() {
        val id1 = AttachmentDomain.generateAttachmentId()
        val id2 = AttachmentDomain.generateAttachmentId()
        assertTrue(id1.value != id2.value)
    }

    @Test
    fun buildLocalPathCreatesCorrectPath() {
        val path = AttachmentDomain.buildLocalPath("/files", "task123", "att_abc", "png")
        assertEquals("/files/task123/att_abc.png", path)
    }

    @Test
    fun buildLocalPathHandlesEmptyExtension() {
        val path = AttachmentDomain.buildLocalPath("/files", "task123", "att_abc", "")
        assertEquals("/files/task123/att_abc", path)
    }

    @Test
    fun extractExtensionReturnsCorrectExtension() {
        assertEquals("png", AttachmentDomain.extractExtension("photo.png"))
        assertEquals("jpg", AttachmentDomain.extractExtension("image.JPG"))
        assertEquals("pdf", AttachmentDomain.extractExtension("document.pdf"))
    }

    @Test
    fun extractExtensionReturnsEmptyStringForNoExtension() {
        assertEquals("", AttachmentDomain.extractExtension("filename"))
        assertEquals("here", AttachmentDomain.extractExtension("noextension.here"))
    }

    @Test
    fun validateUrlAcceptsValidHttpUrls() {
        val result = AttachmentDomain.validateUrl("http://example.com")
        assertTrue(result.isSuccess)
    }

    @Test
    fun validateUrlAcceptsValidHttpsUrls() {
        val result = AttachmentDomain.validateUrl("https://example.com/path?query=1")
        assertTrue(result.isSuccess)
    }

    @Test
    fun validateUrlRejectsInvalidUrls() {
        assertTrue(AttachmentDomain.validateUrl("").isFailure)
        assertTrue(AttachmentDomain.validateUrl("not-a-url").isFailure)
        assertTrue(AttachmentDomain.validateUrl("ftp://example.com").isFailure)
    }

    @Test
    fun formatFileSizeFormatsBytesCorrectly() {
        // The formatter moved to core.ui/Formatters.kt when the four duplicate
        // copies were merged; AttachmentDomain.formatFileSize no longer exists.
        assertEquals("500 B", formatFileSize(500))
        assertEquals("1 KB", formatFileSize(1024))
        assertEquals("1 KB", formatFileSize(1500))
        assertEquals("1 MB", formatFileSize(1024 * 1024))
        assertEquals("1 MB", formatFileSize((1.5 * 1024 * 1024).toLong()))
        // The backup-screen copy of this function stopped at MB and would have
        // rendered a 2 GB backup as "2048.0 MB".
        assertEquals("2.0 GB", formatFileSize(2L * 1024 * 1024 * 1024))
    }
}
