package com.singularity.todo.core.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * One change in the backup's own history: the rows that differ from the base it
 * applies to.
 *
 * ## What a delta holds
 *
 * A delta holds **whole rows**, not patches. A patch says "this field changed to
 * that value" and needs the row it applies to in order to mean anything; a delta
 * that travels on its own has to say what the row *is*. So a row that changed is
 * carried whole — the same shape the base carries — and applying a delta is an
 * upsert and nothing else.
 *
 * ## What a delta does not hold
 *
 * It does not hold **removals**. A row that is gone cannot be carried by a set of
 * rows that are here: the delta would have to say "this row is not here", which is
 * a statement about the base, not a row. So the export that notices a removal does
 * not write a delta at all — it re-seals a fresh base, and the base is the snapshot
 * that has the removal in it. That is the whole reason a chain has a base.
 *
 * ## The cursor
 *
 * [cursor] says where in the backup's own history the delta stands — the moment the
 * export that produced it wrote the snapshot beside it.
 *
 * It is the **backup's own** position and not the sync engine's: `sync_state.lastLsn`
 * moves when the profile changes or the sync state is cleared, and a delta that
 * follows a position the backup never held is a delta that claims to follow a base
 * it does not follow. The backup keeps its own.
 */
@Serializable
data class BackupDelta(
    /** The shape of the rows this delta carries — [BackupFormat.SCHEMA_VERSION] when it was written. */
    val schemaVersion: Int,
    /** When the export that wrote this delta stood — the backup's own position, in epoch millis. */
    val cursor: Long,
    /** The rows that changed: whole rows, keyed by the same keys the base uses. */
    val payload: BackupPayload,
)

/**
 * [delta] applied to [base]: the state the two together describe.
 *
 * ## The rule
 *
 * **One row, one key.** Every kind of row has a key — `id` for the rows that have
 * one, and a pair of ids for the link tables, whose rows have no id of their own:
 * a task's link to a tag is the pair (`taskId`, `tagId`), a dependency edge is
 * (`taskId`, `dependsOnTaskId`), a project's link to a tag group is (`projectId`,
 * `tagGroupId`).
 *
 * A row in [delta] with a key that [base] already holds **replaces** it. A row in
 * [delta] with a key that [base] does not hold is **added**. A row in [base] that
 * [delta] says nothing about is **kept** — that is what makes a delta a delta and
 * not a snapshot.
 *
 * ## What it does not do
 *
 * It does not remove anything. A removal is not a row, and a chain that could
 * remove rows would need the removals to travel; the export that notices one writes
 * a fresh base instead.
 */
fun merge(base: BackupPayload, delta: BackupPayload): BackupPayload = BackupPayload(
    schemaVersion = maxOf(base.schemaVersion, delta.schemaVersion),
    tasks = mergeRows(base.tasks, delta.tasks) { it.id },
    notes = mergeRows(base.notes, delta.notes) { it.id },
    projects = mergeRows(base.projects, delta.projects) { it.id },
    tags = mergeRows(base.tags, delta.tags) { it.id },
    attachments = mergeRows(base.attachments, delta.attachments) { it.id },
    attachmentAnnotations = mergeRows(base.attachmentAnnotations, delta.attachmentAnnotations) { it.id },
    taskTags = mergeRows(base.taskTags, delta.taskTags) { it.taskId.link(it.tagId) },
    taskDependencies = mergeRows(base.taskDependencies, delta.taskDependencies) {
        it.taskId.link(it.dependsOnTaskId)
    },
    agendaViews = mergeRows(base.agendaViews, delta.agendaViews) { it.id },
    taskReminders = mergeRows(base.taskReminders, delta.taskReminders) { it.id },
    projectReminders = mergeRows(base.projectReminders, delta.projectReminders) { it.id },
    checklistItems = mergeRows(base.checklistItems, delta.checklistItems) { it.id },
    tagGroups = mergeRows(base.tagGroups, delta.tagGroups) { it.id },
    projectTagGroups = mergeRows(base.projectTagGroups, delta.projectTagGroups) {
        it.projectId.link(it.tagGroupId)
    },
    savedSearches = mergeRows(base.savedSearches, delta.savedSearches) { it.id },
    timeEntries = mergeRows(base.timeEntries, delta.timeEntries) { it.id },
    profiles = mergeRows(base.profiles, delta.profiles) { it.id },
)

/**
 * Whether the payload holds no rows at all.
 *
 * A change with nothing in it is not a change: writing it would make the chain
 * longer without making the account any newer.
 */
internal fun BackupPayload.hasNoRows(): Boolean =
    tasks.isEmpty() && notes.isEmpty() && projects.isEmpty() && tags.isEmpty() &&
        attachments.isEmpty() && attachmentAnnotations.isEmpty() && taskTags.isEmpty() &&
        taskDependencies.isEmpty() && agendaViews.isEmpty() && taskReminders.isEmpty() &&
        projectReminders.isEmpty() && checklistItems.isEmpty() && tagGroups.isEmpty() &&
        projectTagGroups.isEmpty() && savedSearches.isEmpty() && timeEntries.isEmpty() &&
        profiles.isEmpty()

/**
 * What an export has to write beside its snapshot: a fresh base, or an entry.
 *
 * The two are not a preference — they are the only two things that can be written.
 * A chain that carries an entry says "the base plus this"; a chain without one
 * says "the base is the whole truth", and the second is what an export writes
 * whenever the change cannot be carried as a set of rows.
 */
internal sealed interface BackupDeltaPlan {

    /** The snapshot is the base of a new chain, and no entry is written. */
    data class FreshBase(val reason: Reason) : BackupDeltaPlan

    /** The rows that changed: an entry to seal beside the snapshot. */
    data class Entry(val payload: BackupPayload) : BackupDeltaPlan

    /** Why a fresh base is what the export writes. */
    enum class Reason {
        /** There was no previous export: the snapshot is the beginning of the chain. */
        FIRST_EXPORT,

        /** A row is gone since the last export, and a delta cannot carry a removal. */
        ROW_REMOVED,

        /**
         * An attachment changed, and a delta carries no bytes.
         *
         * The attachment's file is in the snapshot, not in the entry, so a changed
         * attachment cannot be carried by one — and a restored archive with the
         * previous file under the attachment's name is a lie about the account.
         * The snapshot that has the new file in it is the base, and the chain
         * restarts.
         */
        ATTACHMENT_CHANGED,
    }
}

/**
 * What the export writes, given what the last export wrote.
 *
 * The rules are short, and each of them is a thing a delta cannot express:
 *
 * - a **removal** — a row that was there and is not — is not a row, so it cannot
 *   travel in a set of rows: the snapshot becomes the base;
 * - an **attachment** carries bytes, and an entry carries rows: the snapshot that
 *   has the bytes in it becomes the base;
 * - everything else is a row that is new or that changed, and travels whole.
 */
internal object BackupDeltaPlanner {

    /**
     * What [payload] means, given what [previous] held.
     *
     * [current] is the index of [payload] — the same index the export keeps for
     * the next time — so the rows are compared once and written once.
     */
    fun plan(
        previous: BackupRowIndex?,
        current: BackupRowIndex,
        payload: BackupPayload,
    ): BackupDeltaPlan {
        if (previous == null) return BackupDeltaPlan.FreshBase(BackupDeltaPlan.Reason.FIRST_EXPORT)

        for (kind in BackupEntityKind.entries) {
            val before = previous[kind].orEmpty()
            val now = current[kind].orEmpty()
            if (before.keys.any { it !in now }) {
                return BackupDeltaPlan.FreshBase(BackupDeltaPlan.Reason.ROW_REMOVED)
            }
        }

        if (previous[BackupEntityKind.ATTACHMENTS] != current[BackupEntityKind.ATTACHMENTS]) {
            return BackupDeltaPlan.FreshBase(BackupDeltaPlan.Reason.ATTACHMENT_CHANGED)
        }

        return BackupDeltaPlan.Entry(payload = changedRows(previous, payload))
    }

    /**
     * The rows that changed: the new ones and the ones whose content changed.
     *
     * The link tables are carried **whole**: they are small, and their rows are
     * pairs rather than records, so a row that changed is a pair that is not there
     * any more — which is a removal, and a removal cannot travel.
     */
    private fun changedRows(previous: BackupRowIndex, current: BackupPayload): BackupPayload = BackupPayload(
        schemaVersion = BackupFormat.SCHEMA_VERSION,
        tasks = changed(previous, BackupEntityKind.TASKS, current.tasks, TaskDto.serializer()) { it.id },
        notes = changed(previous, BackupEntityKind.NOTES, current.notes, NoteDto.serializer()) { it.id },
        projects = changed(previous, BackupEntityKind.PROJECTS, current.projects, ProjectDto.serializer()) { it.id },
        tags = changed(previous, BackupEntityKind.TAGS, current.tags, TagDto.serializer()) { it.id },
        attachments = emptyList(),
        attachmentAnnotations = changed(
            previous,
            BackupEntityKind.ATTACHMENT_ANNOTATIONS,
            current.attachmentAnnotations,
            AttachmentAnnotationDto.serializer(),
        ) { it.id },
        taskTags = current.taskTags,
        taskDependencies = current.taskDependencies,
        agendaViews = changed(
            previous,
            BackupEntityKind.AGENDA_VIEWS,
            current.agendaViews,
            AgendaViewDto.serializer(),
        ) { it.id },
        taskReminders = changed(
            previous,
            BackupEntityKind.TASK_REMINDERS,
            current.taskReminders,
            TaskReminderDto.serializer(),
        ) { it.id },
        projectReminders = changed(
            previous,
            BackupEntityKind.PROJECT_REMINDERS,
            current.projectReminders,
            ProjectReminderDto.serializer(),
        ) { it.id },
        checklistItems = changed(
            previous,
            BackupEntityKind.CHECKLIST_ITEMS,
            current.checklistItems,
            ChecklistItemDto.serializer(),
        ) { it.id },
        tagGroups = changed(
            previous,
            BackupEntityKind.TAG_GROUPS,
            current.tagGroups,
            TagGroupDto.serializer(),
        ) { it.id },
        projectTagGroups = current.projectTagGroups,
        savedSearches = changed(
            previous,
            BackupEntityKind.SAVED_SEARCHES,
            current.savedSearches,
            SavedSearchDto.serializer(),
        ) { it.id },
        timeEntries = changed(
            previous,
            BackupEntityKind.TIME_ENTRIES,
            current.timeEntries,
            TimeEntryDto.serializer(),
        ) { it.id },
        profiles = changed(previous, BackupEntityKind.PROFILES, current.profiles, ProfileDto.serializer()) { it.id },
    )

    /** The rows of [current] of one kind that are not the rows [previous] held. */
    private fun <T> changed(
        previous: BackupRowIndex,
        kind: BackupEntityKind,
        current: List<T>,
        serializer: KSerializer<T>,
        key: (T) -> String,
    ): List<T> = BackupRows.changed(previous[kind], current, serializer, key)
}

/**
 * The rows of [base] with the rows of [delta] upserted into them.
 *
 * The order is the base's order, with the new rows at the end: a restore that
 * reorders the rows it writes is a restore that makes a diff of its own.
 */
private fun <T> mergeRows(base: List<T>, delta: List<T>, key: (T) -> String): List<T> {
    if (delta.isEmpty()) return base
    val merged = LinkedHashMap<String, T>(base.size + delta.size)
    base.forEach { merged[key(it)] = it }
    delta.forEach { merged[key(it)] = it }
    return merged.values.toList()
}

/**
 * Two ids, joined into one key.
 *
 * The separator is a character that cannot occur in an id, so that `("ab", "c")`
 * and `("a", "bc")` are different keys — and not a character that can occur in an
 * id *pair*, so that the key of one pair cannot be mistaken for the key of another.
 */
private fun String.link(other: String): String = "$this$KEY_SEPARATOR$other"

/** The character that joins the two halves of a link table's key. */
private const val KEY_SEPARATOR = "\u0000"
