@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceTermination
import com.singularity.todo.feature.tasks.presentation.components.RecurrenceSentence
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.textOfTag
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The recurrence sentence in the two languages the app ships.
 *
 * The other tests say what the sentence is *made of* — which date, which unit,
 * which plural form. This one says what a person reads, in English and in
 * Russian, with the real Compose resources behind it: the bundles are resolved
 * as the app resolves them, and the plural forms come from the locale's own
 * rules rather than from a table in the test.
 *
 * The locale is the JVM default, because that is what the desktop app reads
 * its resources from; it is put back to English after every mount, so a test
 * that asks for Russian does not decide the locale of the tests after it.
 */
@Tag("fast")
class RecurrenceSentenceLocaleTest {
    private val due = LocalDate(2026, 11, 12)

    // ─── Russian ───────────────────────────────────────────────────────────────

    @Test
    fun `a russian interval names its count, its base and its start`() {
        assertEquals(
            "Каждые 3 дня, от даты выполнения, начиная с 12 ноя 2026",
            rendered(Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY), due, RUSSIAN),
        )
    }

    @Test
    fun `a russian rule measured from completion names no date`() {
        assertEquals(
            "Каждые 3 дня, после выполнения",
            rendered(Interval(RecurrenceBase.FROM_COMPLETION, 3, DateTimeUnit.DAY), due, RUSSIAN),
        )
    }

    @Test
    fun `a russian catch-up rule names its base and its start`() {
        assertEquals(
            "Каждые 3 дня, с заполнением пропусков, начиная с 12 ноя 2026",
            rendered(Interval(RecurrenceBase.CATCH_UP, 3, DateTimeUnit.DAY), due, RUSSIAN),
        )
    }

    @Test
    fun `a russian weekly rule names its weekdays`() {
        assertEquals(
            "Еженедельно по Пн, Ср, Пт, от даты выполнения",
            rendered(Weekly(RecurrenceBase.FROM_DUE, setOf(1, 3, 5)), null, RUSSIAN),
        )
    }

    @Test
    fun `a russian monthly rule names its day`() {
        assertEquals(
            "Ежемесячно в 15-й день, от даты выполнения",
            rendered(Monthly(RecurrenceBase.FROM_DUE, 15), null, RUSSIAN),
        )
    }

    @Test
    fun `a russian yearly rule names its month and its day`() {
        assertEquals(
            "Ежегодно, 25 дек, от даты выполнения",
            rendered(Yearly(RecurrenceBase.FROM_DUE, 12, 25), null, RUSSIAN),
        )
    }

    @Test
    fun `a russian rule says when the series stops`() {
        assertEquals(
            "Каждые 3 дня, от даты выполнения, начиная с 12 ноя 2026, до 29 янв 2027",
            rendered(
                Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
                    .copy(termination = RecurrenceTermination(LocalDate(2027, 1, 29))),
                due,
                RUSSIAN,
            ),
        )
    }

    @Test
    fun `a russian count of days takes the forms russian uses`() {
        listOf(
            1 to "Каждый день",
            2 to "Каждые 2 дня",
            3 to "Каждые 3 дня",
            5 to "Каждые 5 дней",
            21 to "Каждый 21 день",
            22 to "Каждые 22 дня",
            25 to "Каждые 25 дней",
        ).forEach { (count, phrase) ->
            assertEquals(
                "$phrase, от даты выполнения",
                rendered(Interval(RecurrenceBase.FROM_DUE, count, DateTimeUnit.DAY), null, RUSSIAN),
                "the russian count of $count days",
            )
        }
    }

    @Test
    fun `a russian rule names every unit it has`() {
        listOf(
            DateTimeUnit.DAY to "Каждый день",
            DateTimeUnit.WEEK to "Каждую неделю",
            DateTimeUnit.MONTH to "Каждый месяц",
            DateTimeUnit.YEAR to "Каждый год",
        ).forEach { (unit, phrase) ->
            assertEquals(
                "$phrase, от даты выполнения",
                rendered(Interval(RecurrenceBase.FROM_DUE, 1, unit), null, RUSSIAN),
                "the russian unit $unit",
            )
        }
    }

    // ─── English ───────────────────────────────────────────────────────────────

    @Test
    fun `an english interval names its count, its base and its start`() {
        assertEquals(
            "Every 3 days, from the due date, starting 12 Nov 2026",
            rendered(Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY), due, ENGLISH),
        )
    }

    @Test
    fun `an english rule measured from completion names no date`() {
        assertEquals(
            "Every 3 days, after completion",
            rendered(Interval(RecurrenceBase.FROM_COMPLETION, 3, DateTimeUnit.DAY), due, ENGLISH),
        )
    }

    @Test
    fun `an english catch-up rule names its base and its start`() {
        assertEquals(
            "Every 3 days, catch-up, filling the gaps, starting 12 Nov 2026",
            rendered(Interval(RecurrenceBase.CATCH_UP, 3, DateTimeUnit.DAY), due, ENGLISH),
        )
    }

    @Test
    fun `an english weekly rule names its weekdays`() {
        assertEquals(
            "Weekly on Mon, Wed, Fri, from the due date",
            rendered(Weekly(RecurrenceBase.FROM_DUE, setOf(1, 3, 5)), null, ENGLISH),
        )
    }

    @Test
    fun `an english rule says when the series stops`() {
        assertEquals(
            "Every 3 days, from the due date, starting 12 Nov 2026, until 29 Jan 2027",
            rendered(
                Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
                    .copy(termination = RecurrenceTermination(LocalDate(2027, 1, 29))),
                due,
                ENGLISH,
            ),
        )
    }

    @Test
    fun `an english count of days takes its two forms`() {
        listOf(
            1 to "Every day",
            2 to "Every 2 days",
        ).forEach { (count, phrase) ->
            assertEquals(
                "$phrase, from the due date",
                rendered(Interval(RecurrenceBase.FROM_DUE, count, DateTimeUnit.DAY), null, ENGLISH),
                "the english count of $count days",
            )
        }
    }

    @Test
    fun `an english rule names every unit it has`() {
        listOf(
            DateTimeUnit.DAY to "Every day",
            DateTimeUnit.WEEK to "Every week",
            DateTimeUnit.MONTH to "Every month",
            DateTimeUnit.YEAR to "Every year",
        ).forEach { (unit, phrase) ->
            assertEquals(
                "$phrase, from the due date",
                rendered(Interval(RecurrenceBase.FROM_DUE, 1, unit), null, ENGLISH),
                "the english unit $unit",
            )
        }
    }

    // ─── The sentence, rendered ────────────────────────────────────────────────

    /**
     * The sentence a rule reads as, in one language.
     *
     * The sentence is rendered by the app's own resources, with the JVM locale
     * set to [locale] for the mount — the same lever the desktop app itself
     * reads its strings by.
     */
    private fun rendered(
        spec: RecurrenceSpec,
        dueDate: LocalDate?,
        locale: Locale,
    ): String {
        var text = ""
        runIsolatedComposeTest(locale = locale) {
            setContent {
                Text(
                    text = RecurrenceSentence(spec, dueDate),
                    modifier = Modifier.testTag(SENTENCE),
                )
            }
            text = textOfTag(SENTENCE) ?: ""
        }
        return text
    }

    private companion object {
        const val SENTENCE = "recurrence_sentence"

        val RUSSIAN: Locale = Locale.forLanguageTag("ru")
        val ENGLISH: Locale = Locale.ENGLISH
    }
}
