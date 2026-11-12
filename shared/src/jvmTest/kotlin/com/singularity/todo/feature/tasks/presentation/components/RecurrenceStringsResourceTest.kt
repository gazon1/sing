package com.singularity.todo.feature.tasks.presentation.components

import com.singularity.todo.core.ui.bundlePlural
import com.singularity.todo.core.ui.bundleString
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The recurrence sentence is a translated string, and not a literal.
 *
 * ## What this pins, and why it is not a Compose test
 *
 * The words of the sentence come from `Res.string` and `Res.plurals`, so what a
 * device shows is whatever the bundle for its language holds. "A Russian device
 * shows a Russian rule" is therefore a claim about the **bundle**: the
 * composable can only be as translated as the resources behind it.
 *
 * Reading the two files the build feeds into the resource cache says that
 * without touching the process locale — setting the default locale would be a
 * global, order-dependent change to every test in the module.
 */
@Tag("fast")
class RecurrenceStringsResourceTest {
    @Test
    fun `every word of the recurrence sentence is translated for russian`() {
        STRINGS.forEach { (key, english) ->
            assertEquals(
                english,
                bundleString("values", key),
                "the default bundle's `$key` changed; this test owns the English wording",
            )

            val russian = bundleString("values-ru", key)
            assertTrue(russian.isNotEmpty(), "values-ru/strings.xml must translate the `$key`")
            assertNotEquals(
                english,
                russian,
                "the Russian bundle carries the English `$key`, so a Russian device reads English",
            )
            assertTrue(
                russian.any { it in 'а'..'я' || it == 'ё' || it == 'Ё' },
                "the Russian `$key` label '$russian' has no Cyrillic in it",
            )
        }
    }

    @Test
    fun `the russian count of days takes the forms russian uses`() {
        assertEquals(
            "Каждые 3 дня",
            bundlePlural("values-ru", "recurrence_every_days", "few", 3),
        )
        assertEquals(
            "Каждые 5 дней",
            bundlePlural("values-ru", "recurrence_every_days", "many", 5),
        )
        assertEquals(
            "Каждый 21 день",
            bundlePlural("values-ru", "recurrence_every_days", "one", 21),
        )
        assertEquals(
            "Every 3 days",
            bundlePlural("values", "recurrence_every_days", "other", 3),
        )
    }

    private companion object {
        /** The words of the recurrence sentence, and their English text. */
        val STRINGS: List<Pair<String, String>> =
            listOf(
                "recurrence_every_day" to "Every day",
                "recurrence_every_week" to "Every week",
                "recurrence_every_month" to "Every month",
                "recurrence_every_year" to "Every year",
                "recurrence_weekly" to "Weekly on %1\$s",
                "recurrence_monthly" to "Monthly on day %1\$d",
                "recurrence_yearly" to "Yearly on %1\$s %2\$d",
                "recurrence_from_due" to "from the due date",
                "recurrence_from_completion" to "after completion",
                "recurrence_catch_up" to "catch-up, filling the gaps",
                "recurrence_starting" to "starting %1\$s",
                "recurrence_until" to "until %1\$s",
            )
    }
}
