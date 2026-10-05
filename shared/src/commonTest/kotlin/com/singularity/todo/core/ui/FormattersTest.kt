package com.singularity.todo.core.ui

import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

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

    /**
     * The gigabyte branch rounds to one decimal, and does it in integer arithmetic.
     *
     * It used to be `"%.1f GB".format(bytes / gibibyte.toDouble())`, which formats in
     * the *device's* locale: a user with a comma decimal separator saw "2,0 GB" on
     * every attachment and backup size in the app. These cases pin the rounding
     * itself, so swapping the arithmetic back to a `Double` cannot silently change
     * what a size reads as.
     */
    @Test
    fun `gigabytes round to one decimal`() {
        val gib = 1024L * 1024 * 1024
        assertEquals("1.5 GB", formatFileSize(gib + gib / 2))
        assertEquals("1.1 GB", formatFileSize(gib + gib / 10 + 1))
        assertEquals("1.0 GB", formatFileSize(gib + gib / 20))
        // Just over the threshold, which is the case a truncating implementation
        // would render "1.0 GB" for every file up to 1.05 GB.
        assertEquals("1.0 GB", formatFileSize(gib + 1))
        // A Long near the ceiling must not overflow the `bytes * 10` a naive
        // implementation would reach for.
        assertEquals("8589934592.0 GB", formatFileSize(Long.MAX_VALUE))
    }

    @Test
    fun `month abbreviations are English three-letter names`() {
        assertEquals("Jan", monthAbbreviation(Month.JANUARY))
        assertEquals("Sep", monthAbbreviation(Month.SEPTEMBER))
        assertEquals("Dec", monthAbbreviation(Month.DECEMBER))
    }

    /**
     * Every month must produce three characters, not a longer name: this replaced
     * `SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())`, and the length is the
     * part that keeps a table row from wrapping.
     */
    @Test
    fun `every month abbreviates to exactly three characters`() {
        Month.entries.forEach { month ->
            assertEquals(3, monthAbbreviation(month).length, month.name)
        }
    }

    @Test
    fun `month day time reads as it did under SimpleDateFormat`() {
        val utc = TimeZone.UTC
        val instant = Instant.parse("2026-11-05T14:30:00Z")

        assertEquals("Nov 5, 14:30", formatMonthDayTime(instant, utc))
    }

    @Test
    fun `month day time zero-pads the clock and leaves the day bare`() {
        val instant = Instant.parse("2026-01-09T09:04:00Z")

        assertEquals("Jan 9, 09:04", formatMonthDayTime(instant, TimeZone.UTC))
    }

    /** A non-UTC zone must actually shift the rendered hour, not be ignored. */
    @Test
    fun `month day time renders in the zone it is given`() {
        val instant = Instant.parse("2026-11-05T23:30:00Z")

        // CET, not CEST: November is an hour ahead, not two.
        assertEquals("Nov 6, 00:30", formatMonthDayTime(instant, TimeZone.of("Europe/Berlin")))
        assertEquals("Nov 5, 23:30", formatMonthDayTime(instant, TimeZone.UTC))
    }
}
