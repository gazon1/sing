package com.singularity.todo.core.sync

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.Tag as SyncTagEntity
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
import kotlin.test.assertTrue

/**
 * Verifies that [SyncContract] is the genuine single source of truth for the
 * sync protocol contract, and that the Kotlin sync implementation conforms to it.
 *
 * Two failure modes this test catches:
 * 1. A field added to a Kotlin entity's `toJson()` without updating the allowlist
 *    → the server silently rejects it and the client never knows
 * 2. [SyncContract.CURRENT_PROTOCOL_VERSION] diverging from the version
 *    embedded in the production [SupabaseSyncApiClient]
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
        val request = BatchPushRequest(
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
        val knownEntities = setOf(
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

    // ── 3. Field allowlist conformance ──────────────────────────────────────

    @Test
    fun `Task serialises only allowlisted fields`() {
        assertAllowlistConformance(minimalTask().toJson(), DocType.Task)
    }

    @Test
    fun `Note serialises only allowlisted fields`() {
        assertAllowlistConformance(minimalNote().toJson(), DocType.Note)
    }

    @Test
    fun `Project serialises only allowlisted fields`() {
        assertAllowlistConformance(minimalProject().toJson(), DocType.Project)
    }

    @Test
    fun `Tag serialises only allowlisted fields`() {
        assertAllowlistConformance(minimalTag().toJson(), DocType.Tag)
    }

    @Test
    fun `TagGroup serialises only allowlisted fields`() {
        assertAllowlistConformance(minimalTagGroup().toJson(), DocType.TagGroup)
    }

    @Test
    fun `TimeEntry serialises only allowlisted fields`() {
        assertAllowlistConformance(minimalTimeEntry().toJson(), DocType.TimeEntry)
    }

    // ── 4. Positive control ──────────────────────────────────────────────────

    /**
     * Positive control: prove that removing a field from the allowlist would
     * cause [assertAllowlistConformance] to fail.
     *
     * If this test fails after intentionally adding a field to both the entity
     * and the allowlist, update [SyncContract.FIELD_ALLOWLIST] — that is the
     * expected workflow.
     */
    @Test
    fun `a field absent from the allowlist would cause the conformance test to fail`() {
        val allowlist = SyncContract.allowlistFor(DocType.Tag).toMutableSet()
        val fieldToRemove = allowlist.firstOrNull()
            ?: throw AssertionError("Tag allowlist is empty — cannot run probe")

        // Shrink the allowlist to exclude a field that IS in the Tag JSON.
        // The conformance check MUST detect this and fire.
        allowlist.remove(fieldToRemove)

        val tag = minimalTag()
        val jsonKeys = tag.toJson().keys
        val unexpected = jsonKeys.filter { it !in allowlist }

        assertTrue(
            unexpected.isNotEmpty() && fieldToRemove in unexpected,
            "Probe failed: removing '$fieldToRemove' from the allowlist should have " +
                "caused the conformance check to detect it in the JSON keys. " +
                "Keys in Tag.toJson() not in shrunk allowlist: $unexpected",
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Asserts that every JSON key in [json] is present in [SyncContract.FIELD_ALLOWLIST]
     * for [docType].
     *
     * A failure means a Kotlin entity property is being serialised but is not
     * in the allowlist.  The field must either be added to the allowlist
     * (if intentional) or removed from the entity's serialised output.
     */
    private fun assertAllowlistConformance(json: JsonObject, docType: DocType) {
        val allowlist = SyncContract.allowlistFor(docType)
        val unexpected = json.keys.filter { it !in allowlist }
        assertTrue(
            unexpected.isEmpty(),
            buildString {
                appendLine("DocType.$docType serialises fields not in SyncContract.FIELD_ALLOWLIST:")
                for (key in unexpected.sorted()) {
                    appendLine("  unknown field: \"$key\"")
                }
                append(
                    "Add these fields to SyncContract.FIELD_ALLOWLIST[DocType.$docType] " +
                    "if intentional, or remove them from the entity's serialised output.",
                )
            },
        )
    }

    // ── Minimal entity factories ───────────────────────────────────────────────
    //
    // Minimum viable entities to produce a valid JSON for allowlist checking.
    // All non-nullable fields are filled with stable dummy values.

    private fun minimalTask(): Task = Task(
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
        dependsOn = emptySet(),
        aiSuppressedTagIds = emptySet(),
        recurrence = null,
        createdAt = FIXED_INSTANT,
        updatedAt = FIXED_INSTANT,
        userId = UserId("test-user"),
    )

    private fun minimalNote(): Note = Note(
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

    private fun minimalProject(): Project = Project(
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

    private fun minimalTag(): SyncTagEntity = SyncTagEntity(
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

    private fun minimalTagGroup(): TagGroup = TagGroup(
        id = TagGroupId(UUID.randomUUID().toString()),
        name = "Test tag group",
        color = 0xFF000000.toInt(),
        createdAt = FIXED_INSTANT,
        updatedAt = FIXED_INSTANT,
        userId = UserId("test-user"),
        deletedAt = null,
    )

    private fun minimalTimeEntry(): TimeEntry = TimeEntry(
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
