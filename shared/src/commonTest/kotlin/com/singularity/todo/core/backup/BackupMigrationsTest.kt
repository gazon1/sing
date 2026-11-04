package com.singularity.todo.core.backup

import com.singularity.todo.core.serialization.StableJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("fast")
class BackupMigrationsTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A payload that is already at the version it is asked to migrate to comes
     * back as it went in.
     *
     * The step is not run at all, so nothing about the payload changes — the
     * test says so, rather than only saying that a key is still there.
     */
    @Test
    fun migrateFromEqualsToReturnsThePayloadUnchanged() {
        val original = payload("""{"schemaVersion":3,"tasks":[{"id":"t1","title":"A task"}]}""")
        val result = BackupMigrations.migrate(original, 3, 3)
        assertEquals(
            original,
            result,
            "a payload that is already at the version it is asked for comes back as it went in",
        )
    }

    /**
     * v1 → v2: the fields the first version did not carry are written on every
     * task and every note.
     *
     * A v1 payload says nothing about a task's parent, its estimate, its
     * recurrence, its links, or a note's pin, colour, order or counts. The
     * transform says so on every row, rather than leaving the reader to know
     * the default: the payload that comes out of the migration is the payload
     * that says what its rows mean.
     */
    @Test
    fun theV1ToV2StepFillsInTheFieldsTheFirstVersionDidNotCarry() {
        val text =
            """{"schemaVersion":1,"tasks":[{"id":"t1","title":"A task"}],"notes":[{"id":"n1","title":"A note"}]}"""
        val original = payload(text)
        val result = BackupMigrations.migrate(original, 1, 2)

        assertEquals("2", result.string("schemaVersion"), "the step lands on the version it migrates to")

        val task = result.taskAt(0)
        assertEquals("t1", task.string("id"), "the fields the row already had are still there")
        assertEquals("A task", task.string("title"))
        assertEquals("null", task.string("parentTaskId"))
        assertEquals("null", task.string("estimateMinutes"))
        assertEquals("null", task.string("recurrenceRule"))
        assertEquals(JsonArray(emptyList()), task["outgoingLinks"])
        assertEquals(JsonArray(emptyList()), task["aiSuppressedTagIds"])

        val note = result.noteAt(0)
        assertEquals("n1", note.string("id"))
        assertEquals("false", note.string("isPinned"))
        assertEquals("null", note.string("pinnedAt"))
        assertEquals("null", note.string("color"))
        assertEquals("0", note.string("sortOrder"))
        assertEquals("0", note.string("wordCount"))
        assertEquals("0", note.string("charCount"))
        assertEquals(JsonArray(emptyList()), note["outgoingLinks"])
        assertEquals("null", note.string("taskId"))
    }

    /**
     * v2 → v3: a payload can carry the saved agenda views.
     *
     * A v2 payload knows about the dependency edges and nothing else. The new
     * list defaults to empty in the serializable class, so a v2 payload is
     * restorable on a v3 client without any field-level transformation — but the
     * payload that comes out of this migration says so, rather than leaving the
     * reader to know the default to read it.
     */
    @Test
    fun theV2ToV3StepSaysWhatThePayloadDoesNotCarry() {
        val original = payloadAt(2)
        val result = BackupMigrations.migrate(original, 2, 3)
        assertEquals("3", result.string("schemaVersion"))
        assertEquals(
            JsonArray(emptyList()),
            result["agendaViews"],
            "the payload that comes out of the migration must say what it does not carry",
        )
    }

    /**
     * v3 → v4: the version moves and nothing else does.
     *
     * The eight entity types this version added all default to empty in the
     * serializable class, so a v3 payload is fully restorable on a v4 client
     * without any field-level transformation. The step says so by moving the
     * version and leaving every row it was given as it was.
     */
    @Test
    fun theV3ToV4StepOnlyMovesTheVersion() {
        val original = payloadAt(3)
        val result = BackupMigrations.migrate(original, 3, 4)
        assertEquals("4", result.string("schemaVersion"))
        assertEquals(
            original["tasks"],
            result["tasks"],
            "the step that only moves the version changes nothing else",
        )
    }

    /**
     * v4 → v5: every dependency edge carries its verb.
     *
     * A v4 payload has only the two ends of each edge, so every relation in it
     * is a block. The transform says so on every edge rather than leaving it to
     * the default in the DTO: a regression here would restore every edge of an
     * old backup as a block — a task that followed up on another would come
     * back waiting for it — and the payload would look perfectly well formed.
     */
    @Test
    fun theV4ToV5StepSaysWhatTheEdgesMean() {
        val text =
            """{"schemaVersion":4,"taskDependencies":[{"taskId":"t1","dependsOnTaskId":"t2"},""" +
                """{"taskId":"t2","dependsOnTaskId":"t3"}]}"""
        val original = payload(text)
        val result = BackupMigrations.migrate(original, 4, 5)

        assertEquals("5", result.string("schemaVersion"))

        val edges = result.array("taskDependencies")
        assertEquals(2, edges.size, "the step keeps every edge it was given")
        edges.forEach { edge ->
            assertEquals(
                "BLOCKS",
                (edge as JsonObject).string("verb"),
                "an edge that carries no verb is a block, and says so",
            )
        }
        assertEquals("t1", (edges[0] as JsonObject).string("taskId"))
        assertEquals("t3", (edges[1] as JsonObject).string("dependsOnTaskId"))
    }

    /**
     * v5 → v6: a task record carries the state the user claimed for its work.
     *
     * A v5 payload has no such field, so every task comes back as `Open` — which
     * is what "nothing claimed" means. The transform says so on every task
     * rather than leaving it to the default in the DTO: the payload that is
     * written after the migration is the payload that says what its tasks mean,
     * and a reader that has to know the default to read it is one version away
     * from a wrong answer.
     */
    @Test
    fun theV5ToV6StepSaysWhatTheTasksDoNotCarry() {
        val original = payloadAt(5)
        val result = BackupMigrations.migrate(original, 5, 6)

        assertEquals("6", result.string("schemaVersion"))

        val tasks = result.array("tasks")
        assertEquals(1, tasks.size)
        tasks.forEach { task ->
            assertEquals(
                "Open",
                (task as JsonObject).string("workflowState"),
                "a task that carries no workflow state comes back as Open, and says so",
            )
        }
    }

    /**
     * v6 → v7: the version moves and nothing else does.
     *
     * The new field is optional and defaults to empty, so a v6 payload says
     * everything it always said: the rows it holds are the whole account, and
     * nothing was removed.
     */
    @Test
    fun theV6ToV7StepOnlyMovesTheVersion() {
        val original = payloadAt(6)
        val result = BackupMigrations.migrate(original, 6, 7)
        assertEquals("7", result.string("schemaVersion"))
        assertEquals(
            original["tasks"],
            result["tasks"],
            "the step that only moves the version changes nothing else",
        )
    }

    /**
     * Every version the chain covers reaches the version this build writes.
     *
     * The chain used to skip v2 and v5: a payload written at either version
     * walked over the gap and came out the way it went in, with whatever the
     * DTO defaults happened to be. Nothing said so — the symptom was "my data
     * is wrong", not "this backup is too old".
     */
    @Test
    fun everyVersionReachesTheCurrentOne() {
        for (from in 1..BackupMigrations.CURRENT) {
            val original = payloadAt(from)
            val result = BackupMigrations.migrate(original, from)
            assertEquals(
                BackupMigrations.CURRENT.toString(),
                result.string("schemaVersion"),
                "a payload written at v$from must come out at v${BackupMigrations.CURRENT}",
            )
        }
    }

    /**
     * Every step lands on the version it migrates to.
     *
     * The end-to-end run says the chain arrives; this says each link of it
     * arrives where it claims to, so a step that moved the version by the wrong
     * amount cannot hide behind the steps that follow it. A version added to
     * the chain without a transform fails here rather than at import time.
     */
    @Test
    fun everyStepLandsOnItsOwnVersion() {
        for (from in 1 until BackupMigrations.CURRENT) {
            val result = BackupMigrations.migrate(payloadAt(from), from, from + 1)
            assertEquals(
                (from + 1).toString(),
                result.string("schemaVersion"),
                "the step v$from→v${from + 1} must land on v${from + 1}",
            )
        }
    }

    /**
     * A step that is not registered is an error, not a no-op.
     *
     * The fold used to skip a missing step silently, so a gap in the chain
     * looked like a chain that worked.
     */
    @Test
    fun aMissingStepRaises() {
        val original = payloadAt(1)
        assertFailsWith<IllegalStateException> {
            BackupMigrations.migrate(original, 0)
        }
    }

    /**
     * A payload written at the first version comes out of the whole chain
     * carrying every field the chain adds.
     *
     * This is the run that decides what an old backup restores as: the fields
     * the first version did not have, the verb on every edge, and the workflow
     * state on every task.
     */
    @Test
    fun aFullRunCarriesEveryFieldTheChainAdds() {
        val text =
            """{"schemaVersion":1,"tasks":[{"id":"t1","title":"A task"}],"notes":[{"id":"n1","title":"A note"}],""" +
                """"taskDependencies":[{"taskId":"t1","dependsOnTaskId":"t2"}]}"""
        val original = payload(text)
        val result = BackupMigrations.migrate(original, 1)

        assertEquals(BackupMigrations.CURRENT.toString(), result.string("schemaVersion"))

        val task = result.taskAt(0)
        assertEquals("null", task.string("parentTaskId"), "the first step filled in the fields v1 did not have")
        assertEquals("Open", task.string("workflowState"), "the last step that touches a task said what it means")
        assertEquals("BLOCKS", result.dependencyAt(0).string("verb"), "the edge says what it means")
    }

    /**
     * A payload written at the first version restores as the rows the chain promised.
     *
     * The step tests read the JSON the chain writes; the DTO tests read rows built
     * in code. Neither says what an old backup **restores as**, and the decoder
     * between them is lenient: `StableJson` ignores a key the payload type does not
     * know, so a field a step promises can be dropped there without a word and
     * every other test in the tree stays green.
     *
     * This is the run that closes that gap. The payload is decoded the way the
     * importer decodes it, and the rows that come back are asked what they carry —
     * every task's workflow state, every edge's verb, and the fields the first
     * version did not have. Then the chain's own output is compared with what the
     * payload type reads for those rows, which is what fails when a step writes a
     * field the reader cannot see.
     */
    @Test
    fun aPayloadWrittenAtTheFirstVersionRestoresAsTheRowsTheChainPromised() {
        val payload = decodeMigratedPayload(V1_PAYLOAD_WITH_EDGES.encodeToByteArray(), 1)

        assertEquals(
            BackupMigrations.CURRENT,
            payload.schemaVersion,
            "the reader gets the payload this build writes",
        )
        assertTheRowsTheChainPromised(payload)

        val migrated = BackupMigrations.migrate(payload(V1_PAYLOAD_WITH_EDGES), 1)
        assertEveryWrittenKeyIsRead(migrated, readBack(payload))
    }

    /**
     * The join check notices a field the payload type does not carry.
     *
     * A green join test is worth something only if it can be red. Nothing is
     * subtracted here: the check is asked what the chain writes that the reader
     * does not carry, and the answer is the real gap — `parentTaskId`, which
     * v1→v2 writes and `TaskDto` does not carry (#1998).
     *
     * The join test subtracts exactly that set and finds nothing else, so the two
     * together say the gap is one key wide and no wider. When the payload type
     * learns the field, this fails and the exception goes with it.
     */
    @Test
    fun theJoinCheckNoticesAFieldThePayloadTypeDoesNotCarry() {
        val payload = decodeMigratedPayload(V1_PAYLOAD_WITH_EDGES.encodeToByteArray(), 1)
        val migrated = BackupMigrations.migrate(payload(V1_PAYLOAD_WITH_EDGES), 1)

        assertEquals(
            setOf("parentTaskId"),
            lostKeys(migrated.rows(TASKS), readBack(payload).rows(TASKS)),
            "the check must see the field the chain writes and the payload type does not carry; " +
                "an empty set here means TaskDto carries parentTaskId now (#1998), and NOT_CARRIED goes with it",
        )
    }

    /** A payload as it would have been written at [version]. */
    private fun payloadAt(version: Int): JsonObject =
        payload("""{"schemaVersion":$version,"tasks":[{"id":"t1","title":"A task","createdAt":1,"updatedAt":1}]}""")

    /** The payload written in [text]. */
    private fun payload(text: String): JsonObject = json.decodeFromString(JsonElement.serializer(), text) as JsonObject

    /**
     * The value of a primitive field, or null when the payload does not carry
     * the field at all.
     *
     * A field that is there and carries nothing reads as `"null"`; a field that
     * is not there reads as no value at all — so the two cannot be confused.
     */
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    /** The rows at [key], or none when the payload does not carry them. */
    private fun JsonObject.array(key: String): List<JsonElement> = this[key] as? JsonArray ?: emptyList()

    private fun JsonObject.taskAt(index: Int): JsonObject = array("tasks")[index] as JsonObject

    private fun JsonObject.noteAt(index: Int): JsonObject = array("notes")[index] as JsonObject

    private fun JsonObject.dependencyAt(index: Int): JsonObject = array("taskDependencies")[index] as JsonObject

    /** The rows under [key], as objects. */
    private fun JsonObject.rows(key: String): List<JsonObject> = array(key).map { it as JsonObject }

    /**
     * The payload type's own JSON for [payload]: every field it carries, holding
     * the value the reader actually got.
     *
     * `StableJson` encodes defaults, so this is the payload type's whole field set
     * and not only the fields the chain happened to write — which is what makes it
     * usable as the reader's side of the comparison.
     */
    private fun readBack(payload: BackupPayload): JsonObject =
        payload(StableJson.encodeToString(BackupPayload.serializer(), payload))

    /**
     * The keys the chain writes on a row that the payload type does not read back.
     *
     * [notCarried] is the declared gap, subtracted rather than left out of the
     * comparison so a new uncarried key cannot hide behind the one everybody
     * already knows about.
     */
    private fun lostKeys(
        written: List<JsonObject>,
        read: List<JsonObject>,
        notCarried: Set<String> = emptySet(),
    ): Set<String> =
        written
            .zip(read)
            .flatMap { (writtenRow, readRow) -> writtenRow.keys - readRow.keys - notCarried }
            .toSet()

    /**
     * Every key the chain writes is a key the payload type reads back, with the
     * value the chain wrote.
     *
     * A key the chain writes that [read] does not carry was dropped by the decoder,
     * and the restore never saw it; a key both carry with different values is a
     * record the backup promised one thing and the restore another.
     */
    private fun assertEveryWrittenKeyIsRead(migrated: JsonObject, read: JsonObject) {
        assertEquals(
            emptySet<String>(),
            migrated.keys - read.keys,
            "the chain writes a payload field the payload type does not carry, so the restore drops it",
        )
        for (rowKey in ROW_KEYS) {
            assertEquals(
                emptySet<String>(),
                lostKeys(migrated.rows(rowKey), read.rows(rowKey), NOT_CARRIED[rowKey].orEmpty()),
                "the chain writes a $rowKey field the payload type does not carry, so the value it " +
                    "promised is not the value the reader gets",
            )
            migrated
                .rows(rowKey)
                .zip(read.rows(rowKey))
                .forEach { (writtenRow, readRow) ->
                    writtenRow.forEach { (key, value) ->
                        if (key in readRow) {
                            assertEquals(
                                value,
                                readRow[key],
                                "the $rowKey field `$key` reads back as something other than what the chain wrote",
                            )
                        }
                    }
                }
        }
    }

    /**
     * The rows the decoded payload carries, asked for what the chain promised them.
     */
    private fun assertTheRowsTheChainPromised(payload: BackupPayload) {
        assertEquals(2, payload.tasks.size, "the chain keeps every task it was given")
        payload.tasks.forEach { task ->
            assertEquals(
                "Open",
                task.workflowState,
                "v5→v6 says a task written before the state existed claims nothing, and every row carries Open",
            )
            assertNull(
                task.estimateMinutes,
                "v1→v2 fills in the estimate a v1 task never carried, and every row carries none",
            )
            assertNull(
                task.recurrenceRule,
                "v1→v2 fills in the recurrence a v1 task never carried, and every row carries none",
            )
            assertEquals(
                emptyList<String>(),
                task.outgoingLinks,
                "v1→v2 fills in the links a v1 task never carried, and every row carries none",
            )
            assertEquals(
                emptyList<String>(),
                task.aiSuppressedTagIds,
                "v1→v2 fills in the suppressed tags a v1 task never carried, and every row carries none",
            )
        }

        assertEquals(2, payload.notes.size, "the chain keeps every note it was given")
        payload.notes.forEach { note ->
            assertFalse(note.isPinned, "v1→v2 fills in the pin a v1 note never carried, and no note is pinned")
            assertNull(
                note.pinnedAt,
                "v1→v2 fills in the pin timestamp a v1 note never carried, and none carries one",
            )
            assertNull(note.color, "v1→v2 fills in the colour a v1 note never carried, and none carries one")
            assertEquals(
                0,
                note.sortOrder,
                "v1→v2 fills in the order a v1 note never carried, and each starts at zero",
            )
            assertEquals(
                0,
                note.wordCount,
                "v1→v2 fills in the word count a v1 note never carried, and each starts at zero",
            )
            assertEquals(
                0,
                note.charCount,
                "v1→v2 fills in the character count a v1 note never carried, and each starts at zero",
            )
            assertEquals(
                emptyList<String>(),
                note.outgoingLinks,
                "v1→v2 fills in the links a v1 note never carried, and none carries one",
            )
            assertNull(note.taskId, "v1→v2 fills in the task link a v1 note never carried, and none carries one")
        }

        assertEquals(2, payload.taskDependencies.size, "the chain keeps every edge it was given")
        payload.taskDependencies.forEach { edge ->
            assertEquals(
                "BLOCKS",
                edge.verb,
                "v4→v5 says an edge written without a verb is a block, and every row carries BLOCKS",
            )
        }

        assertTrue(
            payload.agendaViews.isEmpty(),
            "v2→v3 says a v2 payload carries no saved views, and the payload carries none",
        )
        assertTrue(
            payload.removals.isEmpty(),
            "v6→v7 says nothing was removed, and nothing is what the payload carries",
        )
    }

    private companion object {
        /**
         * A payload written at the first version, plus the dependency edges a v1
         * payload could not carry.
         *
         * The edges are artificial and deliberate: the step that writes a verb only
         * touches edges that are already there, so an edge is the only way to see
         * what the chain says an edge of an old backup means. A real v1 payload
         * restores the same way, with no edges to mean anything.
         *
         * Every list carries two rows: a step that writes a field on the first row
         * and forgets the rest is exactly the bug a one-row fixture cannot see.
         */
        const val V1_PAYLOAD_WITH_EDGES =
            """{"schemaVersion":1,""" +
                """"tasks":[{"id":"t1","title":"A task","createdAt":1,"updatedAt":1},""" +
                """{"id":"t2","title":"Another task","createdAt":1,"updatedAt":1}],""" +
                """"notes":[{"id":"n1","title":"A note","createdAt":1,"updatedAt":1},""" +
                """{"id":"n2","title":"Another note","createdAt":1,"updatedAt":1}],""" +
                """"taskDependencies":[{"taskId":"t1","dependsOnTaskId":"t2"},""" +
                """{"taskId":"t2","dependsOnTaskId":"t1"}]}"""

        const val TASKS = "tasks"

        /** The rows the chain writes fields on — every one of them is compared. */
        val ROW_KEYS = listOf(TASKS, "notes", "taskDependencies")

        /**
         * The declared gap, by row kind: `v1ToV2` writes `parentTaskId` on a task and
         * `TaskDto` does not carry it, so a restore drops it — #1998 owns the fix.
         *
         * The exception is one key on one row kind, and the join test's control
         * proves the gap is still a gap: when the payload type learns the field,
         * that test fails and this map goes with it.
         */
        val NOT_CARRIED: Map<String, Set<String>> = mapOf(TASKS to setOf("parentTaskId"))
    }
}
