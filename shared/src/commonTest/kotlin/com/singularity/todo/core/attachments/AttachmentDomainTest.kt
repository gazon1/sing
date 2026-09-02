package com.singularity.todo.core.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AttachmentDomainTest {

    @Test
    fun `generateAttachmentId creates valid format`() {
        val id = AttachmentDomain.generateAttachmentId()
        assertTrue(id.value.startsWith("att_"))
        assertEquals(36 + 4, id.value.length) // UUID is 36 chars + "att_" prefix
    }

    @Test
    fun `generateAttachmentId produces unique ids`() {
        val id1 = AttachmentDomain.generateAttachmentId()
        val id2 = AttachmentDomain.generateAttachmentId()
        assertTrue(id1.value != id2.value)
    }

    @Test
    fun `buildLocalPath creates correct path`() {
        val path = AttachmentDomain.buildLocalPath("/files", "task123", "att_abc", "png")
        assertEquals("/files/task123/att_abc.png", path)
    }

    @Test
    fun `buildLocalPath handles empty extension`() {
        val path = AttachmentDomain.buildLocalPath("/files", "task123", "att_abc", "")
        assertEquals("/files/task123/att_abc", path)
    }

    @Test
    fun `extractExtension returns correct extension`() {
        assertEquals("png", AttachmentDomain.extractExtension("photo.png"))
        assertEquals("jpg", AttachmentDomain.extractExtension("image.JPG"))
        assertEquals("pdf", AttachmentDomain.extractExtension("document.pdf"))
    }

    @Test
    fun `extractExtension returns empty string for no extension`() {
        assertEquals("", AttachmentDomain.extractExtension("filename"))
        assertEquals("here", AttachmentDomain.extractExtension("noextension.here"))
    }

    @Test
    fun `validateUrl accepts valid http URLs`() {
        val result = AttachmentDomain.validateUrl("http://example.com")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `validateUrl accepts valid https URLs`() {
        val result = AttachmentDomain.validateUrl("https://example.com/path?query=1")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `validateUrl rejects invalid URLs`() {
        assertTrue(AttachmentDomain.validateUrl("").isFailure)
        assertTrue(AttachmentDomain.validateUrl("not-a-url").isFailure)
        assertTrue(AttachmentDomain.validateUrl("ftp://example.com").isFailure)
    }

    @Test
    fun `formatFileSize formats bytes correctly`() {
        assertEquals("500 B", AttachmentDomain.formatFileSize(500))
        assertEquals("1 KB", AttachmentDomain.formatFileSize(1024))
        assertEquals("1 KB", AttachmentDomain.formatFileSize(1500))
        assertEquals("1 MB", AttachmentDomain.formatFileSize(1024 * 1024))
    }
}
