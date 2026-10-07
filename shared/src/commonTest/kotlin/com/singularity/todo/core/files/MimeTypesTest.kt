package com.singularity.todo.core.files

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("fast")
class MimeTypesTest {

    @Test
    fun fromExtensionReturnsCorrectMimeForImageTypes() {
        assertEquals("image/png", MimeTypes.fromExtension("png"))
        assertEquals("image/jpeg", MimeTypes.fromExtension("jpg"))
        assertEquals("image/jpeg", MimeTypes.fromExtension("jpeg"))
        assertEquals("image/gif", MimeTypes.fromExtension("gif"))
        assertEquals("image/webp", MimeTypes.fromExtension("webp"))
    }

    @Test
    fun fromExtensionReturnsCorrectMimeForDocuments() {
        assertEquals("application/pdf", MimeTypes.fromExtension("pdf"))
        assertEquals("application/msword", MimeTypes.fromExtension("doc"))
        assertEquals("text/plain", MimeTypes.fromExtension("txt"))
        assertEquals("text/csv", MimeTypes.fromExtension("csv"))
    }

    @Test
    fun fromExtensionIsCaseInsensitive() {
        assertEquals("image/png", MimeTypes.fromExtension("PNG"))
        assertEquals("image/jpeg", MimeTypes.fromExtension("JPG"))
    }

    @Test
    fun fromExtensionReturnsOctetStreamForUnknown() {
        assertEquals("application/octet-stream", MimeTypes.fromExtension("xyz"))
        assertEquals("application/octet-stream", MimeTypes.fromExtension("unknown"))
    }

    @Test
    fun fromExtensionReturnsOctetStreamForEmptyString() {
        assertEquals("application/octet-stream", MimeTypes.fromExtension(""))
    }

    @Test
    fun isImageReturnsTrueForImageMimeTypes() {
        assertEquals(true, MimeTypes.isImage("image/png"))
        assertEquals(true, MimeTypes.isImage("image/jpeg"))
        assertEquals(true, MimeTypes.isImage("image/gif"))
    }

    @Test
    fun isImageReturnsFalseForNonImageMimeTypes() {
        assertEquals(false, MimeTypes.isImage("text/plain"))
        assertEquals(false, MimeTypes.isImage("application/pdf"))
    }

    @Test
    fun isDocumentReturnsTrueForDocumentMimeTypes() {
        assertEquals(true, MimeTypes.isDocument("application/pdf"))
        assertEquals(true, MimeTypes.isDocument("application/msword"))
    }

    @Test
    fun isArchiveReturnsTrueForArchiveMimeTypes() {
        assertEquals(true, MimeTypes.isArchive("application/zip"))
        assertEquals(true, MimeTypes.isArchive("application/x-7z-compressed"))
    }

    // ── The derived set the picker filters on ───────────────────────────────────

    @Test
    fun supportedExtensionsCarriesNoBlankEntry() {
        // The table's `"" to "application/octet-stream"` row is a lookup fallback, not
        // a file type. Leaking it into a picker filter asks FileKit to match files whose
        // extension is nothing.
        assertTrue(
            MimeTypes.supportedExtensions.none { it.isBlank() },
            "supportedExtensions must not contain a blank extension",
        )
    }

    @Test
    fun supportedExtensionsIsTheTableMinusTheFallbackRow() {
        val fromTable = MimeTypes.supportedExtensions
        for (ext in listOf("png", "jpg", "pdf", "docx", "txt", "csv", "zip", "mp4")) {
            assertTrue(ext in fromTable, "$ext is in the table but not in supportedExtensions")
        }
        assertEquals(fromTable.size, fromTable.distinct().size, "supportedExtensions has duplicates")
    }
}
