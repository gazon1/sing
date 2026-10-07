package com.singularity.todo.core.attachments

import com.singularity.todo.core.files.FileCategory
import com.singularity.todo.core.files.MimeTypes
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Routing is the decision "where does this open", and it is the only place that decides
 * it. It is a pure function specifically so that every branch — including the awkward
 * ones below — is checkable without an emulator, where most of them would otherwise be
 * untestable.
 */
@Tag("fast")
class AttachmentViewerRouteTest {

    private fun targetFor(fileName: String?, mimeType: String?): ViewerTarget =
        AttachmentViewerRoute.routeFor(fileName, mimeType).target

    // ── In-app image ────────────────────────────────────────────────────────────

    @Test
    fun `a png opens in the image viewer`() {
        assertEquals(ViewerTarget.InAppImage, targetFor("photo.png", "image/png"))
    }

    @Test
    fun `a jpeg opens in the image viewer`() {
        assertEquals(ViewerTarget.InAppImage, targetFor("holiday.JPG", "image/jpeg"))
    }

    @Test
    fun `a gif opens in the image viewer`() {
        assertEquals(ViewerTarget.InAppImage, targetFor("loop.gif", "image/gif"))
    }

    // ── The SVG asymmetry ───────────────────────────────────────────────────────

    @Test
    fun `svg goes out even though its mime type says image`() {
        // The one case where "starts with image/" is not enough. There is no SVG
        // renderer in this project, so routing it in-app would promise a viewer that
        // does not exist and show the user an error where a working file handler could
        // have opened it.
        assertEquals(ViewerTarget.External, targetFor("logo.svg", "image/svg+xml"))
        assertEquals(FileCategory.External, MimeTypes.classifyMime("image/svg+xml"))
    }

    @Test
    fun `svg by extension alone also goes out`() {
        assertEquals(ViewerTarget.External, targetFor("logo.svg", null))
    }

    // ── In-app text ─────────────────────────────────────────────────────────────

    @Test
    fun `a txt opens in the text viewer`() {
        assertEquals(ViewerTarget.InAppText, targetFor("notes.txt", "text/plain"))
    }

    @Test
    fun `a markdown file opens in the text viewer`() {
        assertEquals(ViewerTarget.InAppText, targetFor("README.md", "text/markdown"))
    }

    @Test
    fun `a json file opens in the text viewer`() {
        assertEquals(ViewerTarget.InAppText, targetFor("data.json", "application/json"))
    }

    @Test
    fun `a csv file opens in the text viewer by extension when the mime is generic`() {
        // `application/octet-stream` is what a restored-from-backup attachment often
        // carries. Falling back to the extension is what keeps a CSV visible instead of
        // being sent to a program that does not exist.
        assertEquals(ViewerTarget.InAppText, targetFor("export.csv", "application/octet-stream"))
    }

    @Test
    fun `a source file opens in the text viewer`() {
        for (name in listOf("Main.kt", "app.ts", "script.py", "query.sql", "conf.toml")) {
            assertEquals(
                ViewerTarget.InAppText,
                targetFor(name, "application/octet-stream"),
                "$name should be text",
            )
        }
    }

    // ── External ────────────────────────────────────────────────────────────────

    @Test
    fun `a pdf goes to the platform`() {
        assertEquals(ViewerTarget.External, targetFor("contract.pdf", "application/pdf"))
    }

    @Test
    fun `a docx goes to the platform`() {
        assertEquals(
            ViewerTarget.External,
            targetFor(
                "memo.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ),
        )
    }

    @Test
    fun `a zip goes to the platform`() {
        assertEquals(ViewerTarget.External, targetFor("archive.zip", "application/zip"))
    }

    @Test
    fun `an audio file goes to the platform`() {
        assertEquals(ViewerTarget.External, targetFor("memo.mp3", "audio/mpeg"))
    }

    // ── Missing and unknown inputs ──────────────────────────────────────────────

    @Test
    fun `a null mime type falls back to the extension`() {
        assertEquals(ViewerTarget.InAppImage, targetFor("photo.png", null))
        assertEquals(ViewerTarget.InAppText, targetFor("notes.txt", null))
        assertEquals(ViewerTarget.External, targetFor("archive.zip", null))
    }

    @Test
    fun `a blank mime type is treated as absent`() {
        assertEquals(ViewerTarget.InAppImage, targetFor("photo.png", ""))
        assertEquals(ViewerTarget.InAppImage, targetFor("photo.png", "   "))
    }

    @Test
    fun `an unknown extension goes out rather than being dropped`() {
        // Exhaustiveness: an unrecognised type still has a destination. There is no
        // "nothing happens" outcome here.
        assertEquals(ViewerTarget.External, targetFor("thing.qqq", "application/x-unknown"))
    }

    @Test
    fun `an empty file name with no mime type goes out`() {
        assertEquals(ViewerTarget.External, targetFor(null, null))
        assertEquals(ViewerTarget.External, targetFor("", null))
    }

    @Test
    fun `a file name with no extension and a generic mime goes out`() {
        assertEquals(ViewerTarget.External, targetFor("Makefile", "application/octet-stream"))
    }

    @Test
    fun `a dotfile is not treated as having an extension`() {
        assertEquals(ViewerTarget.External, targetFor(".gitignore", null))
    }

    // ── The reported type ───────────────────────────────────────────────────────

    @Test
    fun `the route reports the mime type it decided on`() {
        assertEquals("image/png", AttachmentViewerRoute.routeFor("a.png", "image/png").mimeType)
    }

    @Test
    fun `the route derives a mime type when none was stored`() {
        // The fallback screens explain *why* a file went out, and that explanation
        // needs a type to name.
        assertEquals("image/png", AttachmentViewerRoute.routeFor("a.png", null).mimeType)
    }

    @Test
    fun `a route with nothing known reports the octet-stream fallback`() {
        assertEquals("application/octet-stream", AttachmentViewerRoute.routeFor(null, null).mimeType)
    }

    // ── No branch may be unreachable ────────────────────────────────────────────

    @Test
    fun `every viewer target is reachable`() {
        val seen = setOf(
            targetFor("a.png", null),
            targetFor("a.txt", null),
            targetFor("a.pdf", "application/pdf"),
        )
        assertEquals(ViewerTarget.entries.toSet(), seen, "some target cannot be produced")
    }

    @Test
    fun `routing is stable for the same input`() {
        val first = AttachmentViewerRoute.routeFor("a.png", "image/png")
        val second = AttachmentViewerRoute.routeFor("a.png", "image/png")
        assertEquals(first, second)
        assertNotEquals(
            first,
            AttachmentViewerRoute.routeFor("a.txt", "text/plain"),
        )
    }
}
