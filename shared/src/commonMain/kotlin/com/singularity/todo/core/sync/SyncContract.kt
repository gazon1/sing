package com.singularity.todo.core.sync

/**
 * Single declarative source of truth for the sync protocol contract.
 *
 * Everything the sync protocol defines — versions, RPC methods, synchronisable
 * document types, and the field allowlist — is declared here.  All other
 * sync code derives from this object rather than repeating the facts.
 *
 * The field allowlist (`FIELD_ALLOWLIST`) must agree with the server-side
 * `sync_field_allowlist` table.  A field that appears in a Kotlin entity's
 * `toJson()` output but is not in the allowlist will be accepted by the
 * client, sent to the server, and silently rejected there.  The JVM test
 * `SyncContractIsTheSourceOfTruthTest` catches this drift.
 *
 * ## Version history
 *
 * - protocol v1: initial release (2026-10-05)
 * - event schema v1: initial release (2026-10-05)
 */
object SyncContract {

    // ── Versions ───────────────────────────────────────────────────────────────

    /** Protocol version sent in every [DeltaPatch]. Events with a higher version are dropped. */
    const val CURRENT_PROTOCOL_VERSION = 1

    /** Event schema version. Events with a higher schema version are dropped. */
    const val EVENT_SCHEMA_VERSION = 1

    // ── Synchronisable document types ─────────────────────────────────────────

    /** Every [DocType] this client can sync. */
    val SYNCABLE_DOC_TYPES: Set<DocType> = DocType.entries.toSet()

    // ── Field allowlist ────────────────────────────────────────────────────────
    //
    // Must agree with the server-side `sync_field_allowlist` table.
    // Each set contains the JSON field names that the server will accept for
    // that DocType.  A name must appear here *before* it is added to a
    // Kotlin entity's `toJson()`, otherwise the JVM test will fail.
    //
    // Naming convention: kotlinx-serialization encodes a Kotlin property named
    // `fooBar` as JSON key `"fooBar"`.  A `value class` property (e.g.
    // `val id: TaskId`) is encoded as the underlying String value under the
    // property name as the key.
    //
    // The allowlist is the UNION of:
    //   (a) fields the server-side sync_field_allowlist table accepts, AND
    //   (b) client-side sync metadata (serverVersion, hlc, userId) that the
    //       server stores without enforcing — see SyncableEntity.toJson
    //
    // ── DocType.Task ──────────────────────────────────────────────────────────
    //
    // Source: feature/tasks/domain/model/Task.kt  (36 fields)

    private val TASK_ALLOWLIST = setOf(
        // Identity
        "id",
        // Core fields
        "title",
        "description",
        "priority",
        "kind",
        "completedAt",
        "someday",
        "archivedAt",
        "isPinned",
        // Relationships
        "projectId",
        "parentTaskId",
        "tags",
        "dependsOn",
        "aiSuppressedTagIds",
        // Dates
        "dueDate",
        "dueTime",
        "startDate",
        "startTime",
        "endDate",
        "endTime",
        // Extra
        "accentColor",
        "emoji",
        "estimateMinutes",
        "recurrence",
        // Metadata
        "createdAt",
        "updatedAt",
        // Sync metadata — client-side fields the server stores but does not
        // enforce in sync_field_allowlist (serverVersion=0, hlc=null for local)
        "serverVersion",
        "hlc",
        "userId",
    )

    // ── DocType.Note ─────────────────────────────────────────────────────────
    //
    // Source: feature/notes/Ids.kt  Note data class

    private val NOTE_ALLOWLIST = setOf(
        // Identity
        "id",
        // Core fields
        "userId",
        "title",
        "bodyMarkdown",
        "bodyHtml",
        "isFolder",
        "kind",
        "parentNoteId",
        "isPinned",
        "pinnedAt",
        "color",
        "sortOrder",
        "wordCount",
        "charCount",
        "outgoingLinks",
        "taskId",
        // Metadata
        "createdAt",
        "updatedAt",
        "deletedAt",
        "archivedAt",
        // Sync metadata
        "serverVersion",
        "hlc",
    )

    // ── DocType.Project ──────────────────────────────────────────────────────
    //
    // Source: feature/projects/domain/model/Project.kt

    private val PROJECT_ALLOWLIST = setOf(
        // Identity
        "id",
        // Core fields
        "name",
        "color",
        "icon",
        "description",
        "isDefault",
        "dueDate",
        "team",
        "isDeleted",
        "parentId",
        "sortOrder",
        "idempotencyKey",
        "externalId",
        "userId",
        // Relationships
        "inheritedTagGroupIds",
        // Metadata
        "createdAt",
        "updatedAt",
        "deletedAt",
        // Sync metadata
        "serverVersion",
        "hlc",
    )

    // ── DocType.Tag ──────────────────────────────────────────────────────────
    //
    // Source: feature/tags/Ids.kt  Tag data class

    private val TAG_ALLOWLIST = setOf(
        // Identity
        "id",
        // Core fields
        "name",
        "color",
        "groupId",
        "sortOrder",
        "deletedAt",
        "userId",
        // Sync metadata
        "createdAt",
        "updatedAt",
        "serverVersion",
        "hlc",
    )

    // ── DocType.TagGroup ─────────────────────────────────────────────────────
    //
    // Source: feature/tags/domain/model/TagGroup.kt

    private val TAG_GROUP_ALLOWLIST = setOf(
        // Identity
        "id",
        // Core fields
        "name",
        "color",
        "deletedAt",
        "userId",
        // Metadata
        "createdAt",
        "updatedAt",
        // Sync metadata
        "serverVersion",
        "hlc",
    )

    // ── DocType.TimeEntry ────────────────────────────────────────────────────
    //
    // Source: feature/timetracking/domain/TimeEntry.kt

    private val TIME_ENTRY_ALLOWLIST = setOf(
        // Identity
        "id",
        // Core fields
        "taskId",
        "userId",
        "startedAt",
        "endedAt",
        "kind",
        "source",
        "note",
        // Metadata
        "createdAt",
        "updatedAt",
        "deletedAt",
        // Sync metadata
        "serverVersion",
        "hlc",
    )

    /**
     * Fields the server will accept for each [DocType].
     *
     * A [SyncableEntity.toJson] that emits a field name not in this map
     * will be accepted by the client, sent to the server, and rejected there.
     * [SyncContractIsTheSourceOfTruthTest] detects this drift at test time.
     */
    val FIELD_ALLOWLIST: Map<DocType, Set<String>> = mapOf(
        DocType.Task to TASK_ALLOWLIST,
        DocType.Note to NOTE_ALLOWLIST,
        DocType.Project to PROJECT_ALLOWLIST,
        DocType.Tag to TAG_ALLOWLIST,
        DocType.TagGroup to TAG_GROUP_ALLOWLIST,
        DocType.TimeEntry to TIME_ENTRY_ALLOWLIST,
    )

    /**
     * Returns the allowlisted field names for [docType], or an empty set if
     * the type is not a known syncable document type.
     */
    fun allowlistFor(docType: DocType): Set<String> =
        FIELD_ALLOWLIST[docType] ?: emptySet()
}
