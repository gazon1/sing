package com.singularity.todo.core.ui

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared formatters, tested directly.
 *
 * These had four duplicate copies in features before 2026-10-04 and no test at
 * the shared level — the only coverage was an `AttachmentDomainTest` case for one
 * of the four. `formatFileSize` in particular had two non-equivalent variants (one
 * handled gigabytes, one stopped at megabytes) and nothing caught the
 * difference.
 */
@Tag("fast")
class FormattersTest {

    @Test
    fun `elapsed under an hour is minutes and seconds`() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:07", formatElapsed(7_000))
        assertEquals("4:07", formatElapsed(247_000))
        assertEquals("59:59", formatElapsed(3_599_000))
    }

    @Test
    fun `elapsed past an hour gains an hour field`() {
        assertEquals("1:00:00", formatElapsed(3_600_000))
        assertEquals("2:03:04", formatElapsed(7_384_000))
    }

    @Test
    fun `elapsed truncates to whole seconds`() {
        assertEquals("0:01", formatElapsed(1_999))
    }

    @Test
    fun `duration is coarse and lossy`() {
        assertEquals("0m", formatDuration(0))
        assertEquals("0m", formatDuration(59_000))
        assertEquals("45m", formatDuration(45 * 60_000))
        assertEquals("2h", formatDuration(120 * 60_000))
        assertEquals("2h 30m", formatDuration(150 * 60_000))
    }

    @Test
    fun `file size steps through every unit`() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("500 B", formatFileSize(500))
        assertEquals("1023 B", formatFileSize(1023))
        assertEquals("1 KB", formatFileSize(1024))
        assertEquals("1 KB", formatFileSize(1500))
        assertEquals("1 MB", formatFileSize(1024L * 1024))
        // KB and MB truncate — only GB carries a decimal. This is pre-existing
        // behaviour from both original copies, preserved deliberately: 1.5 MB
        // renders as "1 MB". Making it round would be a visual change to every
        // attachment and backup size on screen, not a de-duplication.
        assertEquals("1 MB", formatFileSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("1023 MB", formatFileSize(1024L * 1024 * 1024 - 1))
        // The boundary the backup-screen copy of this function got wrong: it
        // stopped at MB, so a 2 GB backup rendered as "2048.0 MB".
        assertEquals("1.0 GB", formatFileSize(1024L * 1024 * 1024))
        assertEquals("2.0 GB", formatFileSize(2L * 1024 * 1024 * 1024))
    }
}
