package com.singularity.todo.core.ui

import com.singularity.todo.core.format.LocalTimeFormats
import com.singularity.todo.core.format.formatPadded
import com.singularity.todo.feature.backup.formatBackupDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.parallel.Isolated
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The numbers the app writes are the app's own, whatever the device says.
 *
 * `String.format` without a locale formats in the *device's* locale, and an Arabic
 * phone writes Arabic-Indic digits: "١٤:٣٠:٠٠" where the app means "14:30:00".
 * For the clock on the screen that is a wrong-looking label; for
 * [LocalTimeFormats] it is worse — that string is the wire format the database,
 * the backup and the AI tool inputs read back, and `parse` does not read
 * Arabic-Indic digits at all.
 *
 * So the default locale is switched to Arabic here and the output is checked to
 * still be ASCII. The check is on the characters rather than on a comparison with
 * Arabic digits: it must fail on a JDK whose locale data differs, and it must
 * fail if somebody puts `"%02d".format(...)` back.
 *
 * ## Why the class is isolated
 *
 * `Locale.setDefault` is global state. The suite runs classes and methods
 * concurrently, so a test that changes the default locale without saying so
 * would race every other test in the JVM — and would fail at random rather than
 * for a reason.
 */
@Tag("fast")
@Isolated
class LocaleIndependenceTest {
    @Test
    fun `the clock keeps ASCII digits under an Arabic device locale`() {
        withArabicDefaultLocale {
            assertEquals("14:30:05", LocalTimeFormats.format(LocalTime(14, 30, 5)))
            assertEquals("07", formatPadded(7))
            assertEquals("1.5", formatDecimal(1.5, 1))
        }
    }

    /**
     * The second wire format: the backup stamp is written into the backup file and
     * read back by the importer, so it has to hold the same digits as the clock.
     */
    @Test
    fun `the backup stamp keeps ASCII digits under an Arabic device locale`() {
        withArabicDefaultLocale {
            val millis = Instant.parse("2026-10-30T14:30:00Z").toEpochMilliseconds()
            assertEquals("2026-10-30 14:30", formatBackupDate(millis, TimeZone.UTC))
        }
    }

    /**
     * The precondition of the two tests above.
     *
     * If this JDK's `ar-EG` data does not localize the digits, then `"%02d".format(7)`
     * is "07" whatever the locale is — and the assertions above would pass with the
     * bug present, proving nothing. A JDK whose locale data differs has to say so
     * loudly here instead of letting the test go quietly vacuous.
     */
    @Test
    fun `the Arabic locale localizes the digits, or the other assertions prove nothing`() {
        withArabicDefaultLocale {
            assertNotEquals(
                "07",
                "%02d".format(7),
                "this JDK does not localize digits for ar-EG — the assertions above are vacuous",
            )
        }
    }

    @Test
    fun `the wire format stays readable back`() {
        withArabicDefaultLocale {
            val written = LocalTimeFormats.format(LocalTime(14, 30, 5))
            assertEquals(LocalTime(14, 30, 5), LocalTimeFormats.parse(written))
        }
    }

    /** Runs [block] with Arabic as the device locale, and puts the old one back. */
    private fun withArabicDefaultLocale(block: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** Every character of a rendered number must be an ASCII digit. */
    @Test
    fun `a rendered number holds ASCII digits only`() {
        withArabicDefaultLocale {
            val rendered = LocalTimeFormats.format(LocalTime(14, 30, 5))
            assertTrue(rendered.all { it in '0'..'9' || it == ':' }, rendered)
        }
    }
}
