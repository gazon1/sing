package com.singularity.todo.core.sync

import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.singularity.todo.feature.tags.Tag as SyncTagEntity

/**
 * Verifies that [SyncContract] is the genuine single source of truth for the
 * sync protocol contract, and that the Kotlin sync implementation conforms to it.
 *
 * The field lists are derived from the models, so the interesting question is
 * not "does the list match the model" — it is derived from it — but "does the
 * wire carry exactly what the list says", which pins the encoder, and "is the
 * hand-written part of the contract, the fields the server owns, saying a name
 * the model has".
 *
 * Two failure modes this test catches:
 * 1. The wire and the wire's list are not the same set — the encoder stopped
 *    writing a field the model has, and the device would send a document
 *    without it while the list says it is there.
 * 2. A subtraction of a name the model does not have — a name the server would
 *    try to subtract from a list that does not hold it, leaving the model's own
 *    bookkeeping writable.
 *
 * It also catches [SyncContract.CURRENT_PROTOCOL_VERSION] diverging from the
 * version embedded in the production [SupabaseSyncApiClient].
 *
 * Runs as part of `:shared:jvmTest` ([Tag.fast]).
 */
@Tag("fast")
class SyncContractIsTheSourceOfTruthTest {
    companion object {
        /** Clock for deterministic test timestamps. */
        private val FIXED_INSTANT: kotlin.time.Instant = kotlin.time.Instant.parse("2026-01-01T00:00:00Z")
    }

    // ── 1. Protocol version ───────────────────────────────────────────────────

    @Test
    fun `CURRENT_PROTOCOL_VERSION matches what SupabaseSyncApiClient sends`() {
        // SupabaseSyncApiClient.batchPush builds a BatchPushRequest with
        // protocolVersion = SyncProtocol.CURRENT_PROTOCOL_VERSION.
        // We verify that the version string embedded in the serialised request
        // equals SyncContract.CURRENT_PROTOCOL_VERSION.
        val request =
            BatchPushRequest(
                deviceId = "test-device",
                profileId = "test-profile",
                patches = emptyList(),
            )
        val json = StableJson.encodeToString(BatchPushRequest.serializer(), request)
        assertTrue(
            json.contains(""""protocolVersion":${SyncContract.CURRENT_PROTOCOL_VERSION}"""),
            "protocolVersion in serialised BatchPushRequest must match SyncContract.CURRENT_PROTOCOL_VERSION",
        )
    }

    // ── 2. DocType coverage ───────────────────────────────────────────────────

    @Test
    fun `every DocType in SYNCABLE_DOC_TYPES has a corresponding Kotlin entity`() {
        val knownEntities =
            setOf(
                DocType.Task to Task::class.java,
                DocType.Note to Note::class.java,
                DocType.Project to Project::class.java,
                DocType.Tag to SyncTagEntity::class.java,
                DocType.TagGroup to TagGroup::class.java,
                DocType.TimeEntry to TimeEntry::class.java,
            )
        val missing = SyncContract.SYNCABLE_DOC_TYPES.filter { dt -> knownEntities.none { it.first == dt } }
        assertTrue(missing.isEmpty(), "DocTypes with no Kotlin entity: $missing")
    }

    // ── 3. The field lists are derived from the models ────────────────────────

    @Test
    fun `the wire carries exactly the fields the contract derives`() {
        assertWireConformance(minimalTask().toJson(), DocType.Task)
    }

    @Test
    fun `a note carries exactly the fields the contract derives`() {
        assertWireConformance(minimalNote().toJson(), DocType.Note)
    }

    @Test
    fun `a project carries exactly the fields the contract derives`() {
        assertWireConformance(minimalProject().toJson(), DocType.Project)
    }

    @Test
    fun `a tag carries exactly the fields the contract derives`() {
        assertWireConformance(minimalTag().toJson(), DocType.Tag)
    }

    @Test
    fun `a tag group carries exactly the fields the contract derives`() {
        assertWireConformance(minimalTagGroup().toJson(), DocType.TagGroup)
    }

    @Test
    fun `a time entry carries exactly the fields the contract derives`() {
        assertWireConformance(minimalTimeEntry().toJson(), DocType.TimeEntry)
    }

    @Test
    fun `every field the server owns is a field every model has`() {
        for (docType in DocType.entries) {
            val modelFields = SyncContract.wireFieldsFor(docType)
            val unknown = SyncContract.SERVER_OWNED.filter { it !in modelFields }
            assertTrue(
                unknown.isEmpty(),
                "DocType.$docType does not have the fields the server owns: $unknown — " +
                    "the server owns them the same way for every type, so a model that " +
                    "does not have one is a model whose own bookkeeping the server would " +
                    "write",
            )
        }
    }

    @Test
    fun `the server's fields are the wire's fields less what the server owns`() {
        for (docType in DocType.entries) {
            val wireFields = SyncContract.wireFieldsFor(docType)
            assertEquals(
                wireFields - SyncContract.SERVER_OWNED,
                SyncContract.serverFieldsFor(docType),
                "DocType.$docType: the server's fields must be the wire's fields less the " +
                    "fields the server owns",
            )
        }
    }

    // ── 4. Positive control ──────────────────────────────────────────────────

    /**
     * Positive control: prove that the check has teeth.
     *
     * A check that cannot fail is not a check. The check under test is the same
     * function the assertions above use — the mismatch between what the wire
     * carries and what the list says — run here against a list that was
     * doctored, so a failure of the real check is a failure of this one.
     */
    @Test
    fun `a list that does not match the wire is caught`() {
        val docType = DocType.Tag
        val wireFields = SyncContract.wireFieldsFor(docType)
        val probe = wireFields.first()
        val shrunk = wireFields - probe

        val mismatch = wireMismatch(minimalTag().toJson(), shrunk)

        assertTrue(
            probe in mismatch,
            "Probe failed: a list without '$probe' must be reported as not matching the " +
                "wire. The check saw: $mismatch",
        )
    }

    @Test
    fun `a field the encoder stops writing is caught`() {
        val docType = DocType.Tag
        val json = minimalTag().toJson()
        val withoutTheField = JsonObject(json.filterKeys { it != "name" })

        val mismatch = wireMismatch(withoutTheField, SyncContract.wireFieldsFor(docType))

        assertTrue(
            "name" in mismatch,
            "Probe failed: a wire that stopped writing 'name' must be reported. " +
                "The check saw: $mismatch",
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * The names that do not match: what the list has and the wire does not
     * carry, and what the wire carries and the list does not have.
     *
     * The check under test, as a function, so that the positive controls can run
     * it against a doctored list instead of re-implementing it.
     */
    private fun wireMismatch(
        json: JsonObject,
        wireFields: List<String>,
    ): List<String> {
        val onTheWire = json.keys
        return wireFields.filter { it !in onTheWire } + onTheWire.filter { it !in wireFields }
    }

    /**
     * Asserts that the fields the wire carries and the wire's derived list are
     * the same set.
     *
     * Not "the wire's fields are in the list" — the same set. The wire is the
     * model, serialised: the device writes every field the model has, and the
     * list is derived from the same model. So the equality holds by
     * construction, and what it pins is the encoder: a field the encoder stops
     * writing — because `encodeDefaults` was turned off, or a custom
     * serializer left one out — is a field the device sends no more and the
     * server never hears of, and this is where that is said.
     */
    private fun assertWireConformance(
        json: JsonObject,
        docType: DocType,
    ) {
        val wireFields = SyncContract.wireFieldsFor(docType)
        val mismatch = wireMismatch(json, wireFields)
        assertTrue(
            mismatch.isEmpty(),
            buildString {
                appendLine("DocType.$docType and the contract's derived list disagree:")
                for (key in mismatch.sorted()) {
                    appendLine("  \"$key\" is on one side and not on the other")
                }
                append(
                    "The wire is the model, serialised: the encoder writes every field the " +
                        "model has, and the list is derived from the same model. A field " +
                        "that is on one side only is the encoder and the model disagreeing.",
                )
            },
        )
    }

    // ── Minimal entity factories ───────────────────────────────────────────────
    //
    // Minimum viable entities to produce a valid JSON for allowlist checking.
    // All non-nullable fields are filled with stable dummy values.

    private fun minimalTask(): Task =
        Task(
            id = TaskId("00000000-0000-0000-0000-000000000001"),
            title = "Test task",
            description = null,
            priority = TaskPriority.None,
            kind = TaskKind.Task,
            projectId = null,
            parentTaskId = null,
            tags = emptyList(),
            dueDate = null,
            dueTime = null,
            startDate = null,
            startTime = null,
            endDate = null,
            endTime = null,
            accentColor = null,
            emoji = null,
            estimateMinutes = null,
            completedAt = null,
            someday = false,
            archivedAt = null,
            isPinned = false,
            blockedBy = emptySet(),
            aiSuppressedTagIds = emptySet(),
            recurrence = null,
            createdAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
            userId = UserId("test-user"),
        )

    private fun minimalNote(): Note =
        Note(
            id = NoteId(UUID.randomUUID().toString()),
            userId = UserId("test-user"),
            title = "Test note",
            bodyMarkdown = null,
            bodyHtml = null,
            isFolder = false,
            kind = com.singularity.todo.feature.notes.NoteKind.Plain,
            parentNoteId = null,
            isPinned = false,
            pinnedAt = null,
            color = null,
            sortOrder = 0,
            wordCount = 0,
            charCount = 0,
            outgoingLinks = emptyList(),
            taskId = null,
            createdAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
            deletedAt = null,
            archivedAt = null,
        )

    private fun minimalProject(): Project =
        Project(
            id = ProjectId(UUID.randomUUID().toString()),
            name = "Test project",
            color = 0xFF000000.toInt(),
            icon = null,
            description = null,
            createdAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
            isDefault = false,
            dueDate = null,
            team = null,
            isDeleted = false,
            deletedAt = null,
            parentId = null,
            sortOrder = 0,
            idempotencyKey = null,
            externalId = null,
            userId = UserId("test-user"),
            inheritedTagGroupIds = emptySet(),
        )

    private fun minimalTag(): SyncTagEntity =
        SyncTagEntity(
            id = TagId(UUID.randomUUID().toString()),
            name = "Test tag",
            color = 0xFF000000.toInt(),
            createdAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
            groupId = null,
            sortOrder = 0,
            deletedAt = null,
            userId = UserId("test-user"),
        )

    private fun minimalTagGroup(): TagGroup =
        TagGroup(
            id = TagGroupId(UUID.randomUUID().toString()),
            name = "Test tag group",
            color = 0xFF000000.toInt(),
            createdAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
            userId = UserId("test-user"),
            deletedAt = null,
        )

    private fun minimalTimeEntry(): TimeEntry =
        TimeEntry(
            id = TimeEntryId(UUID.randomUUID().toString()),
            taskId = TaskId("00000000-0000-0000-0000-000000000001"),
            userId = UserId("test-user"),
            startedAt = FIXED_INSTANT,
            endedAt = FIXED_INSTANT,
            kind = TimeEntryKind.Work,
            source = TimeEntrySource.Manual,
            note = null,
            createdAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
            deletedAt = null,
        )
}
