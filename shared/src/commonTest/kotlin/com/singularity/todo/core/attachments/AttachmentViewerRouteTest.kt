package com.singularity.todo.core.attachments

import com.singularity.todo.core.files.FileCategory
import com.singularity.todo.core.files.MimeTypes
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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

    companion object {
        private const val DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        @JvmStatic
        fun targetCases(): List<Arguments> = listOf(
            // ── In-app image ─────────────────────────────────────────────────
            Arguments.of("photo.png", "image/png", ViewerTarget.InAppImage),
            Arguments.of("holiday.JPG", "image/jpeg", ViewerTarget.InAppImage),
            Arguments.of("loop.gif", "image/gif", ViewerTarget.InAppImage),
            // ── The SVG asymmetry: no SVG renderer, so it goes out even though image/* ──
            // (the mime-typed case is asserted in `svg goes out even though its mime type says image`)
            Arguments.of("logo.svg", null, ViewerTarget.External),
            // ── In-app text ──────────────────────────────────────────────────
            Arguments.of("notes.txt", "text/plain", ViewerTarget.InAppText),
            Arguments.of("README.md", "text/markdown", ViewerTarget.InAppText),
            Arguments.of("data.json", "application/json", ViewerTarget.InAppText),
            // A restored-from-backup attachment often carries octet-stream; the extension keeps it visible.
            Arguments.of("export.csv", "application/octet-stream", ViewerTarget.InAppText),
            Arguments.of("Main.kt", "application/octet-stream", ViewerTarget.InAppText),
            Arguments.of("app.ts", "application/octet-stream", ViewerTarget.InAppText),
            Arguments.of("script.py", "application/octet-stream", ViewerTarget.InAppText),
            Arguments.of("query.sql", "application/octet-stream", ViewerTarget.InAppText),
            Arguments.of("conf.toml", "application/octet-stream", ViewerTarget.InAppText),
            // ── External ─────────────────────────────────────────────────────
            Arguments.of("contract.pdf", "application/pdf", ViewerTarget.External),
            Arguments.of("memo.docx", DOCX, ViewerTarget.External),
            Arguments.of("archive.zip", "application/zip", ViewerTarget.External),
            Arguments.of("memo.mp3", "audio/mpeg", ViewerTarget.External),
            // ── Missing and unknown inputs ───────────────────────────────────
            Arguments.of("photo.png", null, ViewerTarget.InAppImage),
            Arguments.of("notes.txt", null, ViewerTarget.InAppText),
            Arguments.of("archive.zip", null, ViewerTarget.External),
            Arguments.of("photo.png", "", ViewerTarget.InAppImage),
            Arguments.of("photo.png", "   ", ViewerTarget.InAppImage),
            Arguments.of("thing.qqq", "application/x-unknown", ViewerTarget.External),
            Arguments.of(null, null, ViewerTarget.External),
            Arguments.of("", null, ViewerTarget.External),
            Arguments.of("Makefile", "application/octet-stream", ViewerTarget.External),
            Arguments.of(".gitignore", null, ViewerTarget.External),
        )

        @JvmStatic
        fun reportedMimeCases(): List<Arguments> = listOf(
            Arguments.of("a.png", "image/png", "image/png"),
            // The fallback screens explain why a file went out, and that needs a type to name.
            Arguments.of("a.png", null, "image/png"),
            Arguments.of(null, null, "application/octet-stream"),
        )
    }

    @ParameterizedTest
    @MethodSource("targetCases")
    fun `a file routes to the right viewer`(fileName: String?, mimeType: String?, expected: ViewerTarget) {
        assertEquals(expected, targetFor(fileName, mimeType), "routeFor($fileName, $mimeType)")
    }

    @ParameterizedTest
    @MethodSource("reportedMimeCases")
    fun `the route reports the mime type it decided on`(fileName: String?, stored: String?, expected: String) {
        assertEquals(expected, AttachmentViewerRoute.routeFor(fileName, stored).mimeType)
    }

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
    fun `svg goes out even though its mime type says image`() {
        // There is no SVG renderer in this project, so routing it in-app would promise a
        // viewer that does not exist.
        assertEquals(ViewerTarget.External, targetFor("logo.svg", "image/svg+xml"))
        assertEquals(FileCategory.External, MimeTypes.classifyMime("image/svg+xml"))
    }

    @Test
    fun `routing is stable for the same input`() {
        val first = AttachmentViewerRoute.routeFor("a.png", "image/png")
        val second = AttachmentViewerRoute.routeFor("a.png", "image/png")
        assertEquals(first, second)
        assertNotEquals(first, AttachmentViewerRoute.routeFor("a.txt", "text/plain"))
    }
}
