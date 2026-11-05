package com.singularity.todo.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
     * Every field the chain writes is a field the row it writes it on can read.
     *
     * A key no DTO carries is dropped on decode — `ignoreUnknownKeys` is on — so
     * the payload would promise a value the restore never gets, while a test that
     * reads the migrated JSON stays green. This one compares what the chain added
     * on each row against what that row declares it can read.
     */
    @Test
    fun everyFieldTheChainWritesIsAFieldItsRowCanRead() {
        val before =
            payload(
                """{"schemaVersion":1,"tasks":[{"id":"t1","title":"A task","createdAt":1,"updatedAt":1}],""" +
                    """"notes":[{"id":"n1","title":"A note","createdAt":1,"updatedAt":1}],""" +
                    """"taskDependencies":[{"taskId":"t1","dependsOnTaskId":"t2"}]}""",
            )
        val after = BackupMigrations.migrate(before, 1)

        val writtenTasks = after.taskAt(0).keys - before.taskAt(0).keys
        assertEquals(
            emptySet(),
            writtenTasks - TaskDto.readableFields(),
            "the chain wrote a task field the payload's task row does not carry, so a restore would drop it",
        )
        assertTrue(
            "parentTaskId" in writtenTasks,
            "the parent link is one of the fields the chain exists to state",
        )

        val writtenNotes = after.noteAt(0).keys - before.noteAt(0).keys
        assertEquals(
            emptySet(),
            writtenNotes - NoteDto.readableFields(),
            "the chain wrote a note field the payload's note row does not carry, so a restore would drop it",
        )

        val writtenEdges = after.dependencyAt(0).keys - before.dependencyAt(0).keys
        assertEquals(
            emptySet(),
            writtenEdges - TaskDependencyDto.readableFields(),
            "the chain wrote an edge field the payload's edge row does not carry, so a restore would drop it",
        )
    }

    /** The field names [this] serializer decodes — what the row can actually hear. */
    private fun kotlinx.serialization.KSerializer<*>.readableFields(): Set<String> {
        val descriptor = descriptor
        return (0 until descriptor.elementsCount).mapTo(HashSet()) { descriptor.getElementName(it) }
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
}
