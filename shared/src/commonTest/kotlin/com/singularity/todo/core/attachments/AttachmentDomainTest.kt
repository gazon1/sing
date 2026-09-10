package com.singularity.todo.core.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("500 B", AttachmentDomain.formatFileSize(500))
        assertEquals("1 KB", AttachmentDomain.formatFileSize(1024))
        assertEquals("1 KB", AttachmentDomain.formatFileSize(1500))
        assertEquals("1 MB", AttachmentDomain.formatFileSize(1024 * 1024))
    }
}
