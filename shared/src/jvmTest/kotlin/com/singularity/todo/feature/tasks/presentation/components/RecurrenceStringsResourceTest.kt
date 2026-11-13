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
 * ## What this pins, and what it does not
 *
 * This file checks the **bundles**: that every key the sentence is built from
 * exists in both languages and says what it is supposed to say. It reads the two
 * files the build feeds into the resource cache, which does not touch the
 * process locale — setting the default locale would be a global, order-dependent
 * change to every test in the module.
 *
 * It is a check of resource integrity, not a proof of what a device shows. The
 * sentences themselves are proved by the rendering tests in `desktopApp`, which
 * resolve the real Compose resources in English and in Russian.
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
    fun `every sentence template exists in both bundles`() {
        TEMPLATES.forEach { (key, english) ->
            assertEquals(
                english,
                bundleString("values", key),
                "the default bundle's `$key` changed; this test owns the English wording",
            )
            assertTrue(
                bundleString("values-ru", key).isNotEmpty(),
                "values-ru/strings.xml must carry the `$key` template",
            )
        }
    }

    @Test
    fun `a template that holds a word is written in russian`() {
        // The plain template is the exception: it holds no word of its own, only
        // the two the rule and the base bring, so it is the same in both
        // languages — and that is why it is not in `STRINGS`.
        listOf(
            "recurrence_sentence_with_start",
            "recurrence_sentence_with_end",
            "recurrence_sentence_with_start_and_end",
        ).forEach { key ->
            val russian = bundleString("values-ru", key)
            assertNotEquals(
                bundleString("values", key),
                russian,
                "the Russian bundle carries the English `$key`, so a Russian device reads English",
            )
            assertTrue(
                russian.any { it in 'а'..'я' || it == 'ё' || it == 'Ё' },
                "the Russian `$key` template '$russian' has no Cyrillic in it",
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
            )

        /**
         * The templates the sentence is assembled from.
         *
         * The word order and the punctuation of the whole sentence live here and
         * not in the code, so a language that puts its parts in another order
         * needs its own template rather than another branch in Kotlin.
         */
        val TEMPLATES: List<Pair<String, String>> =
            listOf(
                "recurrence_sentence" to "%1\$s, %2\$s",
                "recurrence_sentence_with_start" to "%1\$s, %2\$s, starting %3\$s",
                "recurrence_sentence_with_end" to "%1\$s, %2\$s, until %3\$s",
                "recurrence_sentence_with_start_and_end" to "%1\$s, %2\$s, starting %3\$s, until %4\$s",
            )
    }
}
