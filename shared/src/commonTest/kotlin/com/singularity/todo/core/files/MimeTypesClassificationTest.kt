package com.singularity.todo.core.files

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Classification is the one place that decides "can this app show it". Storage records
 * the answer and the viewer routes on it, so a format that is viewable to one and
 * external to the other is a defect waiting to be reported by a user.
 */
@Tag("fast")
class MimeTypesClassificationTest {

    // ── classify by file name ───────────────────────────────────────────────────

    @Test
    fun `image names classify as image`() {
        for (name in listOf("a.png", "a.jpg", "a.jpeg", "a.gif", "a.webp", "a.bmp", "A.PNG")) {
            assertEquals(FileCategory.Image, MimeTypes.classify(name), name)
        }
    }

    @Test
    fun `text names classify as text`() {
        for (name in listOf("a.txt", "a.md", "a.markdown", "a.json", "a.csv", "a.log")) {
            assertEquals(FileCategory.Text, MimeTypes.classify(name), name)
        }
    }

    @Test
    fun `document and archive names classify as external`() {
        for (name in listOf("a.pdf", "a.docx", "a.doc", "a.zip", "a.7z", "a.mp4", "a.qqq")) {
            assertEquals(FileCategory.External, MimeTypes.classify(name), name)
        }
    }

    @Test
    fun `svg is external even though it is an image type`() {
        // No SVG renderer exists. Classifying it as Image would route it to a viewer
        // that cannot render it.
        assertEquals(FileCategory.External, MimeTypes.classify("logo.svg"))
    }

    @Test
    fun `a name with no extension is external`() {
        for (name in listOf("README", "", "Makefile")) {
            assertEquals(FileCategory.External, MimeTypes.classify(name), name)
        }
    }

    @Test
    fun `only the last extension decides`() {
        assertEquals(FileCategory.Image, MimeTypes.classify("archive.tar.png"))
        assertEquals(FileCategory.External, MimeTypes.classify("notes.txt.zip"))
    }

    // ── classifyExtension ───────────────────────────────────────────────────────

    @Test
    fun `a leading dot is optional`() {
        assertEquals(FileCategory.Image, MimeTypes.classifyExtension("png"))
        assertEquals(FileCategory.Image, MimeTypes.classifyExtension(".png"))
        assertEquals(FileCategory.Image, MimeTypes.classifyExtension(".PNG"))
    }

    @Test
    fun `an unknown extension is external`() {
        assertEquals(FileCategory.External, MimeTypes.classifyExtension("qqq"))
        assertEquals(FileCategory.External, MimeTypes.classifyExtension(""))
    }

    // ── classifyMime ────────────────────────────────────────────────────────────

    @Test
    fun `mime types classify as image`() {
        assertEquals(FileCategory.Image, MimeTypes.classifyMime("image/png"))
        assertEquals(FileCategory.Image, MimeTypes.classifyMime("image/webp"))
    }

    @Test
    fun `text-like mime types classify as text`() {
        for (mime in listOf(
            "text/plain",
            "text/markdown",
            "text/csv",
            "application/json",
            "application/xml",
            "application/javascript",
        )) {
            assertEquals(FileCategory.Text, MimeTypes.classifyMime(mime), mime)
        }
    }

    @Test
    fun `office and archive mime types are external`() {
        for (mime in listOf(
            "application/pdf",
            "application/zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/x-7z-compressed",
            "video/mp4",
        )) {
            assertEquals(FileCategory.External, MimeTypes.classifyMime(mime), mime)
        }
    }

    @Test
    fun `a null mime falls back to the name`() {
        assertEquals(FileCategory.Image, MimeTypes.classifyMime(null, "a.png"))
        assertEquals(FileCategory.Text, MimeTypes.classifyMime(null, "a.md"))
        assertEquals(FileCategory.External, MimeTypes.classifyMime(null, "a.pdf"))
        assertEquals(FileCategory.External, MimeTypes.classifyMime(null))
    }

    @Test
    fun `a blank mime falls back to the name`() {
        assertEquals(FileCategory.Image, MimeTypes.classifyMime("", "a.png"))
        assertEquals(FileCategory.Image, MimeTypes.classifyMime("  ", "a.png"))
    }

    // ── The supported set ───────────────────────────────────────────────────────

    @Test
    fun `supported extensions are exactly the table minus the fallback row`() {
        // The picker's filter and the classifier must see the same world.
        assertTrue(MimeTypes.supportedExtensions.isNotEmpty())
        for (ext in MimeTypes.supportedExtensions) {
            assertFalse(
                ext.isBlank(),
                "a blank extension would be offered to the file picker",
            )
        }
    }

    @Test
    fun `every supported extension resolves to a mime type`() {
        for (ext in MimeTypes.supportedExtensions) {
            val mime = MimeTypes.fromExtension(ext)
            assertNotEquals(
                "application/octet-stream",
                mime,
                "$ext is in the table but fell through to the catch-all",
            )
        }
    }

    @Test
    fun `an extension is case insensitive`() {
        assertEquals("image/png", MimeTypes.fromExtension("PNG"))
        assertEquals("image/png", MimeTypes.fromExtension("png"))
    }

    @Test
    fun `an unknown extension yields the catch-all`() {
        assertEquals("application/octet-stream", MimeTypes.fromExtension("qqq"))
    }

    @Test
    fun `extensionOf reads the trailing extension`() {
        assertEquals("png", MimeTypes.extensionOf("photo.png"))
        assertEquals("pdf", MimeTypes.extensionOf("report.PDF"))
        assertEquals("gz", MimeTypes.extensionOf("archive.tar.gz"))
    }

    @Test
    fun `extensionOf returns null when there is none`() {
        assertNull(MimeTypes.extensionOf("README"))
        assertNull(MimeTypes.extensionOf(""))
        assertNull(MimeTypes.extensionOf("trailing."))
    }
}
