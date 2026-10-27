package com.singularity.todo.core.backup

import com.singularity.todo.core.serialization.StableJson
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A change that says a row is gone.
 *
 * ## What these tests are about
 *
 * A removal used to end the chain: the export that noticed a deleted row wrote
 * a fresh base instead of a change, so one deleted task cost the whole snapshot
 * — and the person who deleted a task could not back up without starting over.
 *
 * A removal is a change like any other, and a change can carry it. These tests
 * cover the three claims that makes:
 *
 * - the payload **can** hold the rows that are gone;
 * - the merge **does** take them out;
 * - an archive written before the field existed **still reads**.
 */
@Tag("fast")
class BackupRemovalTest {

    /** A task, with a title that says which one it is. */
    private fun task(id: String, title: String) = TaskDto(
        id = id,
        title = title,
        createdAt = 1L,
        updatedAt = 1L,
    )

    /**
     * The payload holds the keys of the rows that are gone, per kind.
     *
     * The key is the whole of it: a removed row has no content to carry, so what
     * travels is the statement "this key is not here any more" — and the kind is
     * the one the row index uses, so the removal is looked up where the row would
     * have been.
     */
    @Test
    fun `the payload holds the rows that are gone`() {
        val payload = BackupPayload(
            schemaVersion = BackupFormat.SCHEMA_VERSION,
            removals = mapOf(BackupEntityKind.TASKS to listOf("t-1", "t-2")),
        )

        assertEquals(
            listOf("t-1", "t-2"),
            payload.removals[BackupEntityKind.TASKS],
            "the removal must name the rows that are gone",
        )
    }

    /**
     * The removals survive the trip through the archive's own format.
     *
     * A change is written as JSON and read back by another build, so a removal
     * that does not survive the encoding is a removal that never arrives.
     */
    @Test
    fun `the removals survive the trip through the archive's format`() {
        val payload = BackupPayload(
            schemaVersion = BackupFormat.SCHEMA_VERSION,
            removals = mapOf(BackupEntityKind.TASKS to listOf("t-1")),
        )

        val bytes = StableJson.encodeToString(BackupPayload.serializer(), payload).encodeToByteArray()
        val read = StableJson.decodeFromString(BackupPayload.serializer(), bytes.decodeToString())

        assertEquals(
            listOf("t-1"),
            read.removals[BackupEntityKind.TASKS],
            "the removal must arrive with the change",
        )
    }

    /**
     * The merge takes the rows the change says are gone out.
     *
     * The upserts and the removals are the two halves of one change: a row that
     * changed is written over, a row that is gone is taken out, and a row the
     * change says nothing about is kept.
     */
    @Test
    fun `the merge takes the rows the change says are gone out`() {
        val base = BackupPayload(
            schemaVersion = 1,
            tasks = listOf(task("t-1", "before"), task("t-2", "untouched")),
        )
        val delta = BackupPayload(
            schemaVersion = 1,
            tasks = listOf(task("t-3", "new")),
            removals = mapOf(BackupEntityKind.TASKS to listOf("t-1")),
        )

        val merged = merge(base, delta)

        assertEquals(listOf("t-2", "t-3"), merged.tasks.map { it.id })
        assertEquals("untouched", merged.tasks.first().title)
    }

    /**
     * The merged payload is a state, and a state says what is there.
     *
     * The removal has been applied to it, so it does not carry it along: a chain
     * that merged the next change on top of a merged payload would otherwise
     * take the row out again — including a row that came back.
     */
    @Test
    fun `the merged payload does not carry the removals along`() {
        val base = BackupPayload(schemaVersion = 1, tasks = listOf(task("t-1", "before")))
        val delta = BackupPayload(
            schemaVersion = 1,
            removals = mapOf(BackupEntityKind.TASKS to listOf("t-1")),
        )

        val merged = merge(base, delta)

        assertTrue(merged.removals.isEmpty(), "the removal has been applied; it is not a state")
        assertTrue(merged.tasks.isEmpty(), "the row the change says is gone is not there")
    }

    /**
     * An archive written before the removals field existed still reads.
     *
     * The field is optional, and a payload that has no such field says what it
     * always said: the rows it holds are the whole account, and nothing was
     * removed. The old archives are the ones people already have on their
     * devices — they must keep restoring.
     */
    @Test
    fun `a payload written before the removals field existed still reads`() {
        val written = """{"schemaVersion":6,"tasks":[]}"""

        val element = StableJson.parseToJsonElement(written).jsonObject
        val migrated = BackupMigrations.migrate(element, 6)
        val bytes = StableJson.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            migrated,
        ).encodeToByteArray()
        val payload = StableJson.decodeFromString(BackupPayload.serializer(), bytes.decodeToString())

        assertTrue(
            payload.removals.isEmpty(),
            "an old archive says nothing was removed — it cannot say anything else",
        )
        assertEquals(
            BackupFormat.SCHEMA_VERSION,
            payload.schemaVersion,
            "the migrated payload is the payload this build writes",
        )
    }
}
