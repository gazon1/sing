package com.singularity.todo.core.sync

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.test.fakes.TestUsers
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The document wins wherever it speaks; where it says nothing, the row that is
 * already here keeps saying what it says.
 *
 * ## The defect this is for
 *
 * A document is decoded into a row, and the fields the model cannot do without
 * have defaults: `seriesId` defaults to no series, `recurrence` to no
 * recurrence, `estimateMinutes` to no estimate. A document that carries no
 * `seriesId` key therefore decoded into a task that says "this task stands
 * alone" — and the row is written whole, so the task silently left its series
 * on the device that pulled it.
 *
 * The wire is the reason the reading matters. A document carries the fields
 * the sending device knew about: a device that predates the series sends no
 * `seriesId` key, and the allow-list says what a device sends, and it grew.
 *
 * ## The rule
 *
 * The document wins wherever it speaks. Where it says nothing, the row that is
 * already here keeps saying what it says.
 *
 * ## Why the merge is not about one type
 *
 * The row is given as the JSON the model writes — the same names the document
 * uses — so the two are compared key by key and the same rule serves every
 * type that is synced. The fixture here is a task, because the task is where
 * the defect was found; what is under test is the rule and not the task.
 *
 * The parts that live *outside* the row — the tags and the blocking edges —
 * are not the row's to claim, and the merge does not decide that: the caller
 * filters them out before the row arrives here. That is the caller's
 * knowledge, and it is tested where the caller is.
 */
@Tag("fast")
class DocumentMergeTest {

    private val local = Task(
        id = TaskId.fromString("t1"),
        title = "Pay the rent",
        userId = TestUsers.DEFAULT,
        seriesId = TaskSeriesId.fromString("s1"),
        estimateMinutes = 30,
        recurrence = RecurrenceSpec.Interval(
            base = RecurrenceSpec.RecurrenceBase.FROM_DUE,
            amount = 2,
            unit = DateTimeUnit.DAY,
        ),
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    /** The row, as the model writes it — the same names the document uses. */
    private val localRow: JsonObject = local.toJson()

    private fun document(vararg entries: Pair<String, JsonElement>): JsonObject =
        JsonObject(entries.toMap())

    // ── the absences ───────────────────────────────────────────────────────

    @Test
    fun `a document that carries no series id leaves the series of the row alone`() {
        val merged = mergeWithLocalRow(document("id" to JsonPrimitive("t1")), localRow)

        assertEquals(JsonPrimitive("s1"), merged["seriesId"])
    }

    @Test
    fun `a document that carries no recurrence leaves the recurrence of the row alone`() {
        val merged = mergeWithLocalRow(document("id" to JsonPrimitive("t1")), localRow)

        assertEquals(localRow["recurrence"], merged["recurrence"])
    }

    @Test
    fun `a document that carries no estimate leaves the estimate of the row alone`() {
        val merged = mergeWithLocalRow(document("id" to JsonPrimitive("t1")), localRow)

        assertEquals(JsonPrimitive(30), merged["estimateMinutes"])
    }

    // ── the claims ─────────────────────────────────────────────────────────

    @Test
    fun `a document that says the task stands alone takes it out of the series`() {
        // The key is there and it says null: that is a claim, and it wins.
        val merged = mergeWithLocalRow(
            document("id" to JsonPrimitive("t1"), "seriesId" to JsonPrimitive(null as String?)),
            localRow,
        )

        assertTrue(merged.containsKey("seriesId"), "the document speaks about the series: null is a claim")
        assertEquals(JsonNull, merged["seriesId"], "the document says the task stands alone")
    }

    @Test
    fun `the document wins where it speaks`() {
        val merged = mergeWithLocalRow(
            document("id" to JsonPrimitive("t1"), "title" to JsonPrimitive("Pay the rent in full")),
            localRow,
        )

        assertEquals(JsonPrimitive("Pay the rent in full"), merged["title"])
    }

    @Test
    fun `a document about a row this device has never seen is the document`() {
        val arriving = document("id" to JsonPrimitive("t9"), "title" to JsonPrimitive("New"))

        assertEquals(arriving, mergeWithLocalRow(arriving, null))
    }

    // ── the names ──────────────────────────────────────────────────────────

    @Test
    fun `a row that writes a field under another name does not fill it`() {
        // The merge compares key by key, and it does not guess a name. A row
        // that writes the series under another name is a row that says nothing
        // about `seriesId` — and filling it from such a row would be the merge
        // inventing a correspondence that the wire does not make.
        val renamed = JsonObject(localRow - "seriesId" + ("series_id" to JsonPrimitive("s1")))

        val merged = mergeWithLocalRow(document("id" to JsonPrimitive("t1")), renamed)

        assertFalse(merged.containsKey("seriesId"), "the row does not speak about that name")
    }
}
