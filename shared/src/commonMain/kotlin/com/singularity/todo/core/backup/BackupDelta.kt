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
 * ## What a delta holds about removals
 *
 * It holds the **keys** of the rows that are gone (see
 * [BackupPayload.removals]). A removal has no row to carry — a row that is not
 * there cannot be sent — so what travels is the statement "this key is not here
 * any more", and the restore takes the row out.
 *
 * The alternative used to be that a removal ended the chain: the export wrote a
 * fresh base instead of a change, so one deleted task cost the whole snapshot.
 * A removal is a change like any other, and a change can carry it.
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
 * ## The removals
 *
 * A row whose key [delta] lists as removed is **taken out**. The removal is
 * applied after the upserts of the same delta, so a delta that both carries a row
 * and says it is gone ends with the row gone — the two never overlap in practice,
 * because a row that is gone cannot also have changed.
 *
 * ## What the merged payload does not hold
 *
 * The removals. A merged payload is a **state**, and a state says what is there —
 * the removal has already been applied to it. That is also what keeps a chain
 * honest: merging the next change on top of a merged payload must not carry the
 * earlier removals along, or a row that came back would be taken out again.
 */
fun merge(base: BackupPayload, delta: BackupPayload): BackupPayload = BackupPayload(
    schemaVersion = maxOf(base.schemaVersion, delta.schemaVersion),
    tasks = mergeRows(base.tasks, delta.tasks, delta.removed(BackupEntityKind.TASKS)) { it.id },
    notes = mergeRows(base.notes, delta.notes, delta.removed(BackupEntityKind.NOTES)) { it.id },
    projects = mergeRows(base.projects, delta.projects, delta.removed(BackupEntityKind.PROJECTS)) { it.id },
    tags = mergeRows(base.tags, delta.tags, delta.removed(BackupEntityKind.TAGS)) { it.id },
    attachments = mergeRows(
        base.attachments,
        delta.attachments,
        delta.removed(BackupEntityKind.ATTACHMENTS),
    ) { it.id },
    attachmentAnnotations = mergeRows(
        base.attachmentAnnotations,
        delta.attachmentAnnotations,
        delta.removed(BackupEntityKind.ATTACHMENT_ANNOTATIONS),
    ) { it.id },
    taskTags = mergeRows(
        base.taskTags,
        delta.taskTags,
        delta.removed(BackupEntityKind.TASK_TAGS),
    ) { it.taskId.link(it.tagId) },
    taskDependencies = mergeRows(
        base.taskDependencies,
        delta.taskDependencies,
        delta.removed(BackupEntityKind.TASK_DEPENDENCIES),
    ) {
        it.taskId.link(it.dependsOnTaskId)
    },
    agendaViews = mergeRows(
        base.agendaViews,
        delta.agendaViews,
        delta.removed(BackupEntityKind.AGENDA_VIEWS),
    ) { it.id },
    taskReminders = mergeRows(
        base.taskReminders,
        delta.taskReminders,
        delta.removed(BackupEntityKind.TASK_REMINDERS),
    ) { it.id },
    projectReminders = mergeRows(
        base.projectReminders,
        delta.projectReminders,
        delta.removed(BackupEntityKind.PROJECT_REMINDERS),
    ) { it.id },
    checklistItems = mergeRows(
        base.checklistItems,
        delta.checklistItems,
        delta.removed(BackupEntityKind.CHECKLIST_ITEMS),
    ) { it.id },
    tagGroups = mergeRows(
        base.tagGroups,
        delta.tagGroups,
        delta.removed(BackupEntityKind.TAG_GROUPS),
    ) { it.id },
    projectTagGroups = mergeRows(
        base.projectTagGroups,
        delta.projectTagGroups,
        delta.removed(BackupEntityKind.PROJECT_TAG_GROUPS),
    ) {
        it.projectId.link(it.tagGroupId)
    },
    savedSearches = mergeRows(
        base.savedSearches,
        delta.savedSearches,
        delta.removed(BackupEntityKind.SAVED_SEARCHES),
    ) { it.id },
    timeEntries = mergeRows(
        base.timeEntries,
        delta.timeEntries,
        delta.removed(BackupEntityKind.TIME_ENTRIES),
    ) { it.id },
    profiles = mergeRows(
        base.profiles,
        delta.profiles,
        delta.removed(BackupEntityKind.PROFILES),
    ) { it.id },
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
 * Whether the payload says nothing at all: no rows, and no removals.
 *
 * A change that only removes rows carries no rows of its own, and it is still a
 * change — so "no rows" is not enough to decide that there is nothing to write.
 */
internal fun BackupPayload.hasNoChanges(): Boolean = hasNoRows() && removals.isEmpty()

/**
 * The keys of the rows this payload says are gone, of one kind.
 *
 * The empty set when the kind is not mentioned: a change that says nothing about
 * a kind removes nothing of it.
 */
private fun BackupPayload.removed(kind: BackupEntityKind): Set<String> =
    removals[kind].orEmpty().toSet()

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

        /**
         * An attachment changed, and a delta carries no bytes.
         *
         * The attachment's file is in the snapshot, not in the entry, so a changed
         * attachment cannot be carried by one — and a restored archive with the
         * previous file under the attachment's name is a lie about the account.
         * The snapshot that has the new file in it is the base, and the chain
         * restarts.
         *
         * A removed attachment lands here too: the file is in the snapshot, and a
         * delta cannot take a file back out of it.
         */
        ATTACHMENT_CHANGED,
    }
}

/**
 * What the export writes, given what the last export wrote.
 *
 * The rules are short, and each of them is a thing a delta cannot express:
 *
 * - an **attachment** carries bytes, and an entry carries rows: the snapshot that
 *   has the bytes in it becomes the base;
 * - everything else is a row that is new, that changed, or that is gone, and all
 *   three travel: a removal travels as the key of the row that is not there.
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

        if (previous[BackupEntityKind.ATTACHMENTS] != current[BackupEntityKind.ATTACHMENTS]) {
            return BackupDeltaPlan.FreshBase(BackupDeltaPlan.Reason.ATTACHMENT_CHANGED)
        }

        return BackupDeltaPlan.Entry(
            payload = changedRows(previous, payload, removedRows(previous, current)),
        )
    }

    /**
     * The rows that changed: the new ones and the ones whose content changed.
     *
     * The link tables are carried **whole**: they are small, and their rows are
     * pairs rather than records. A pair that is not there any more is a removal,
     * and a removal travels as a key — see [removedRows].
     */
    private fun changedRows(
        previous: BackupRowIndex,
        current: BackupPayload,
        removals: Map<BackupEntityKind, List<String>>,
    ): BackupPayload = BackupPayload(
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
        removals = removals,
    )

    /**
     * The rows that are gone: the keys [previous] held and [current] does not.
     *
     * The key is the whole of it — a removed row has no content to carry, and the
     * index never held one: it holds a fingerprint per row, and the fingerprint of
     * a row that is not there is nothing. So what the change says is "this key is
     * not here any more", and the restore takes it out.
     *
     * The kinds are the ones the index uses, so a removal is looked up under the
     * same key the row itself would have been — including the link tables, whose
     * key is the pair of ids the row joins.
     */
    private fun removedRows(
        previous: BackupRowIndex,
        current: BackupRowIndex,
    ): Map<BackupEntityKind, List<String>> = BackupEntityKind.entries.mapNotNull { kind ->
        val before = previous[kind].orEmpty()
        val now = current[kind].orEmpty()
        val removed = before.keys.filter { it !in now }
        if (removed.isEmpty()) null else kind to removed
    }.toMap()

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
 * The rows of [base] with the rows of [delta] upserted into them, and the rows
 * whose keys [removed] names taken out.
 *
 * The order is the base's order, with the new rows at the end: a restore that
 * reorders the rows it writes is a restore that makes a diff of its own.
 *
 * The removals are applied last: they are the last word about a row, and a delta
 * that both carries a row and says it is gone ends with the row gone.
 */
private fun <T> mergeRows(
    base: List<T>,
    delta: List<T>,
    removed: Set<String>,
    key: (T) -> String,
): List<T> {
    if (delta.isEmpty() && removed.isEmpty()) return base
    val merged = LinkedHashMap<String, T>(base.size + delta.size)
    base.forEach { merged[key(it)] = it }
    delta.forEach { merged[key(it)] = it }
    removed.forEach { merged.remove(it) }
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

/**
 * The two ids a link table's key joins, as they were joined.
 *
 * The separator is the same one the row index joins its link keys with, so a key
 * that came from the index comes apart here. A key that was not joined — a key
 * that never came from a link table — comes apart into the key and nothing, so
 * the caller deletes nothing rather than something it did not mean.
 */
internal fun String.linkParts(): Pair<String, String> {
    val separator = indexOf(KEY_SEPARATOR)
    if (separator < 0) return this to ""
    return substring(0, separator) to substring(separator + 1)
}

/** The character that joins the two halves of a link table's key. */
private const val KEY_SEPARATOR = "\u0000"
