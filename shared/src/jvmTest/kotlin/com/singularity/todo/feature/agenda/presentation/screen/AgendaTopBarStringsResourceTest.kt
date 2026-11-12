package com.singularity.todo.feature.agenda.presentation.screen

import com.singularity.todo.core.ui.bundleString
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The agenda top bar's labels are translated strings, and not literals.
 *
 * ## What this pins, and why it is not a Compose test
 *
 * The overflow menu's rows take their text from `Res.string.agenda_save_current`
 * and `Res.string.agenda_multi_date`, so what a device shows is whatever the
 * bundle for its language holds. "A Russian device shows a Russian label" is
 * therefore a claim about the **bundle**: the composable can only be as
 * translated as the resources behind it.
 *
 * Asserting it by rendering would mean setting the process's default locale and
 * hoping the resource cache had not already been filled by another test — a
 * global, order-dependent change to every test in the module. Reading the two
 * files the build feeds into that cache says the same thing without touching the
 * process, and it fails on exactly the mistakes worth catching: a key missing
 * from a bundle, or carrying the English word.
 *
 * The bundle read has one owner ([bundleString]); this test owns the English
 * wording of the four labels, and the desktop flow test owns the claim that the
 * rows show a label at all.
 */
@Tag("fast")
class AgendaTopBarStringsResourceTest {
    @Test
    fun `every agenda top-bar label is defined, and translated for russian`() {
        LABELS.forEach { (key, english) ->
            val default = bundleString("values", key)
            assertTrue(
                default.isNotEmpty(),
                "values/strings.xml must define the `$key` label the agenda top bar wears",
            )
            assertEquals(
                english,
                default,
                "the default bundle's `$key` label changed; this test owns the English wording",
            )

            val russian = bundleString("values-ru", key)
            assertTrue(russian.isNotEmpty(), "values-ru/strings.xml must translate the `$key` label")
            assertNotEquals(
                english,
                russian,
                "the Russian bundle carries the English `$key` label, so a Russian device reads English",
            )
            assertTrue(
                russian.any { it in 'а'..'я' || it == 'ё' || it == 'Ё' },
                "the Russian `$key` label '$russian' has no Cyrillic in it",
            )
        }
    }

    private companion object {
        /** The labels the agenda top bar wears, and their English text. */
        val LABELS: List<Pair<String, String>> =
            listOf(
                "agenda_saved_views" to "Saved views",
                "agenda_save_current" to "Save current agenda",
                "agenda_multi_date" to "Task on several dates",
                "agenda_more_actions" to "More actions",
            )
    }
}
