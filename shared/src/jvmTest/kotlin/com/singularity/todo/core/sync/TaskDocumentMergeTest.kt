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
import kotlinx.serialization.json.buildJsonArray
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The row's own absences are not deletions.
 *
 * ## The defect this is for
 *
 * A task document is decoded into a [Task], and the fields the model cannot do
 * without have defaults: `seriesId` defaults to no series, `recurrence` to no
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
 */
@Tag("fast")
class TaskDocumentMergeTest {

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

    private fun document(vararg entries: Pair<String, JsonElement>): JsonObject =
        JsonObject(entries.toMap())

    // ── the absences ───────────────────────────────────────────────────────

    @Test
    fun `a document that carries no series id leaves the series of the row alone`() {
        val merged = mergeWithLocalRow(document("id" to JsonPrimitive("t1")), local)

        assertEquals(JsonPrimitive("s1"), merged["seriesId"])
    }

    @Test
    fun `a document that carries no recurrence leaves the recurrence of the row alone`() {
        val merged = mergeWithLocalRow(document("id" to JsonPrimitive("t1")), local)

        assertEquals(local.toJson()["recurrence"], merged["recurrence"])
    }

    @Test
    fun `a document that carries no estimate leaves the estimate of the row alone`() {
        val merged = mergeWithLocalRow(document("id" to JsonPrimitive("t1")), local)

        assertEquals(JsonPrimitive(30), merged["estimateMinutes"])
    }

    // ── the claims ─────────────────────────────────────────────────────────

    @Test
    fun `a document that says the task stands alone takes it out of the series`() {
        // The key is there and it says null: that is a claim, and it wins.
        val merged = mergeWithLocalRow(
            document("id" to JsonPrimitive("t1"), "seriesId" to JsonPrimitive(null as String?)),
            local,
        )

        assertTrue(merged.containsKey("seriesId"), "the document speaks about the series: null is a claim")
        assertEquals(JsonNull, merged["seriesId"], "the document says the task stands alone")
    }

    @Test
    fun `the document wins where it speaks`() {
        val merged = mergeWithLocalRow(
            document("id" to JsonPrimitive("t1"), "title" to JsonPrimitive("Pay the rent in full")),
            local,
        )

        assertEquals(JsonPrimitive("Pay the rent in full"), merged["title"])
    }

    @Test
    fun `a document about a task this device has never seen is the task`() {
        val arriving = document("id" to JsonPrimitive("t9"), "title" to JsonPrimitive("New"))

        assertEquals(arriving, mergeWithLocalRow(arriving, null))
    }

    // ── the parts that live outside the row ────────────────────────────────

    @Test
    fun `the merge does not speak about the tags the document does not carry`() {
        val merged = mergeWithLocalRow(document("id" to JsonPrimitive("t1")), local)

        assertFalse(merged.containsKey("tags"), "the tags are not the row's to claim")
    }

    @Test
    fun `the merge does not speak about the dependencies the document does not carry`() {
        val merged = mergeWithLocalRow(document("id" to JsonPrimitive("t1")), local)

        assertFalse(merged.containsKey("dependsOn"), "the blocking edges are not the row's to claim")
    }

    @Test
    fun `the tags the document carries are the tags of the document`() {
        val tags = buildJsonArray { add(JsonPrimitive("tag-1")) }
        val merged = mergeWithLocalRow(
            document("id" to JsonPrimitive("t1"), "tags" to tags),
            local,
        )

        assertEquals(tags, merged["tags"])
    }
}
