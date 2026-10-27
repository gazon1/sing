package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceTermination
import com.singularity.todo.feature.tasks.domain.model.isExhaustedBy
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule's own value and storage semantics.
 *
 * These tests used to live in `CompleteRecurringTaskUseCaseTest`, where they had
 * nothing to do with closing an occurrence — the file mixed two subjects, and a
 * failure in the storage of a rule read as a failure of the close. They belong
 * with the rule.
 *
 * Termination is a constructor property rather than something derived, so two
 * rules that differ only by their end date are different rules — which is what
 * makes editing a series' end date a change worth re-planning.
 */
@Tag("fast")
class RecurrenceSpecTest {

    private val today = LocalDate(2026, 6, 15)
    private val weekly = RecurrenceSpec.Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK)

    // ─── Value semantics — the reason termination is a constructor property ───

    @Test
    fun `two specs differing only by end date are not equal`() {
        val bounded = weekly.copy(termination = RecurrenceTermination(today))
        assertNotEquals(weekly, bounded)
        assertNotEquals(bounded.hashCode(), weekly.hashCode())
    }

    @Test
    fun `copy preserves the termination`() {
        val bounded = weekly.copy(termination = RecurrenceTermination(today))
        val changed = bounded.copy(amount = 2)

        assertEquals(bounded.termination, changed.termination)
        assertEquals(2, changed.amount)
    }

    @Test
    fun `every variant carries a termination`() {
        val term = RecurrenceTermination(today)
        val specs = listOf(
            RecurrenceSpec.Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK, term),
            RecurrenceSpec.Weekly(RecurrenceBase.FROM_DUE, setOf(1), term),
            RecurrenceSpec.Monthly(RecurrenceBase.FROM_DUE, 15, term),
            RecurrenceSpec.Yearly(RecurrenceBase.FROM_DUE, 6, 15, term),
        )
        specs.forEach { assertEquals(term, it.termination, "${it::class.simpleName} lost its termination") }
        assertTrue(specs.all { it.isExhaustedBy(today.plus(1, DateTimeUnit.DAY)) })
    }

    // ─── Storage — no migration, old rules keep decoding ──────────────────────

    @Test
    fun `a rule written before this field decodes with no termination`() {
        val legacy = """{"_type":"com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval",""" +
            """"base":"FROM_DUE","amount":1,"unit":{"_type":"kotlinx.datetime.DayBased","days":7}}"""

        val decoded = StableJson.decodeFromString(RecurrenceSpec.serializer(), legacy)

        assertNull(decoded.termination, "a stored rule without the field must decode as unbounded")
        assertEquals(1, (decoded as RecurrenceSpec.Interval).amount)
    }

    @Test
    fun `a bounded rule round-trips through storage`() {
        val bounded = weekly.copy(termination = RecurrenceTermination(today))
        val encoded = StableJson.encodeToString(RecurrenceSpec.serializer(), bounded)
        val decoded = StableJson.decodeFromString(RecurrenceSpec.serializer(), encoded)

        assertEquals(bounded, decoded)
        assertEquals(RecurrenceTermination(today), decoded.termination)
    }

    @Test
    fun `an unbounded rule carries no termination wrapper`() {
        val encoded = StableJson.encodeToString(RecurrenceSpec.serializer(), weekly)
        assertFalse(
            encoded.contains("\"termination\":{"),
            "an unbounded series must not carry an empty termination wrapper, got $encoded",
        )
        assertNull(StableJson.decodeFromString(RecurrenceSpec.serializer(), encoded).termination)
    }
}
