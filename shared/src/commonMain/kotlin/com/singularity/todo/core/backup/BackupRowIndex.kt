package com.singularity.todo.core.backup

import com.singularity.todo.core.serialization.StableJson
import kotlinx.serialization.KSerializer

/**
 * The kinds of row a payload carries.
 *
 * The kind is what a row's key is looked up under: the index of the previous
 * export is a map from the kind to the rows of that kind, and a row is "the same
 * row" when it has the same key **within the same kind**. A task and a note can
 * share an id without being each other.
 */
enum class BackupEntityKind {
    TASKS,
    NOTES,
    PROJECTS,
    TAGS,
    ATTACHMENTS,
    ATTACHMENT_ANNOTATIONS,
    TASK_TAGS,
    TASK_DEPENDENCIES,
    AGENDA_VIEWS,
    TASK_REMINDERS,
    PROJECT_REMINDERS,
    CHECKLIST_ITEMS,
    TAG_GROUPS,
    PROJECT_TAG_GROUPS,
    SAVED_SEARCHES,
    TIME_ENTRIES,
    PROFILES,
}

/**
 * What the previous export's rows looked like: the kind, the row's key, and a
 * fingerprint of what the row held.
 */
internal typealias BackupRowIndex = Map<BackupEntityKind, Map<String, Long>>

/**
 * The rows of a payload, and the rows that changed since another one.
 *
 * ## Why a fingerprint and not the row itself
 *
 * The index has to answer one question: **is this row the row the last export
 * wrote?** The index is kept on the device and is compared on every export, so
 * what it holds has to be small — a fingerprint per row, not the row.
 *
 * ## Why the fingerprint is not a hash of the archive
 *
 * It is a **local, non-cryptographic** fingerprint — FNV-1a over the row's JSON.
 * It is not a security boundary and never becomes one: it says "this row is not
 * the row it was", and a collision would cost one row that did not change being
 * carried again, which the next delta carries correctly. What it must never be
 * is mistaken for a checksum that proves anything: the archive's own integrity is
 * the manifest's checksum, and this says nothing about it.
 */
internal object BackupRows {

    /** The kinds whose rows are always carried whole, changed or not. */
    val LINK_KINDS: Set<BackupEntityKind> = setOf(
        BackupEntityKind.TASK_TAGS,
        BackupEntityKind.TASK_DEPENDENCIES,
        BackupEntityKind.PROJECT_TAG_GROUPS,
    )

    /** What the rows of [payload] look like, kind by kind. */
    fun index(payload: BackupPayload): BackupRowIndex = buildMap {
        put(BackupEntityKind.TASKS, rows(payload.tasks, TaskDto.serializer()) { it.id })
        put(BackupEntityKind.NOTES, rows(payload.notes, NoteDto.serializer()) { it.id })
        put(BackupEntityKind.PROJECTS, rows(payload.projects, ProjectDto.serializer()) { it.id })
        put(BackupEntityKind.TAGS, rows(payload.tags, TagDto.serializer()) { it.id })
        put(BackupEntityKind.ATTACHMENTS, rows(payload.attachments, AttachmentDto.serializer()) { it.id })
        put(
            BackupEntityKind.ATTACHMENT_ANNOTATIONS,
            rows(payload.attachmentAnnotations, AttachmentAnnotationDto.serializer()) { it.id },
        )
        put(BackupEntityKind.TASK_TAGS, rows(payload.taskTags, TaskTagDto.serializer()) { it.linkKey() })
        put(
            BackupEntityKind.TASK_DEPENDENCIES,
            rows(payload.taskDependencies, TaskDependencyDto.serializer()) { it.linkKey() },
        )
        put(BackupEntityKind.AGENDA_VIEWS, rows(payload.agendaViews, AgendaViewDto.serializer()) { it.id })
        put(
            BackupEntityKind.TASK_REMINDERS,
            rows(payload.taskReminders, TaskReminderDto.serializer()) { it.id },
        )
        put(
            BackupEntityKind.PROJECT_REMINDERS,
            rows(payload.projectReminders, ProjectReminderDto.serializer()) { it.id },
        )
        put(
            BackupEntityKind.CHECKLIST_ITEMS,
            rows(payload.checklistItems, ChecklistItemDto.serializer()) { it.id },
        )
        put(BackupEntityKind.TAG_GROUPS, rows(payload.tagGroups, TagGroupDto.serializer()) { it.id })
        put(
            BackupEntityKind.PROJECT_TAG_GROUPS,
            rows(payload.projectTagGroups, ProjectTagGroupDto.serializer()) { it.linkKey() },
        )
        put(BackupEntityKind.SAVED_SEARCHES, rows(payload.savedSearches, SavedSearchDto.serializer()) { it.id })
        put(BackupEntityKind.TIME_ENTRIES, rows(payload.timeEntries, TimeEntryDto.serializer()) { it.id })
        put(BackupEntityKind.PROFILES, rows(payload.profiles, ProfileDto.serializer()) { it.id })
    }

    /**
     * The rows of [current] that are not the rows [previous] held: the new ones
     * and the ones whose content changed.
     *
     * A row that [previous] did not hold is new; a row whose fingerprint differs
     * changed. A row that is the same is left out — that is what makes the result
     * a delta rather than a snapshot.
     */
    fun <T> changed(
        previous: Map<String, Long>?,
        current: List<T>,
        serializer: KSerializer<T>,
        key: (T) -> String,
    ): List<T> {
        val before = previous.orEmpty()
        return current.filter { row -> before[key(row)] != fingerprint(json.encodeToString(serializer, row)) }
    }

    /** The rows of [list], keyed and fingerprinted. */
    private fun <T> rows(
        list: List<T>,
        serializer: KSerializer<T>,
        key: (T) -> String,
    ): Map<String, Long> {
        val result = LinkedHashMap<String, Long>(list.size)
        for (row in list) {
            result[key(row)] = fingerprint(json.encodeToString(serializer, row))
        }
        return result
    }

    private val json = StableJson
}

/** The key of a link table's row: the two ids it joins. */
private fun TaskTagDto.linkKey(): String = "$taskId$LINK_SEPARATOR$tagId"

/** The key of a link table's row: the two ids it joins. */
private fun TaskDependencyDto.linkKey(): String = "$taskId$LINK_SEPARATOR$dependsOnTaskId"

/** The key of a link table's row: the two ids it joins. */
private fun ProjectTagGroupDto.linkKey(): String = "$projectId$LINK_SEPARATOR$tagGroupId"

/** The character that joins the two halves of a link table's key. */
private const val LINK_SEPARATOR = "\u0000"

/**
 * A local, non-cryptographic fingerprint of [text].
 *
 * FNV-1a: cheap, stable, and good enough to notice that a row changed. It is not
 * a checksum of anything the archive promises, and it is not a security boundary
 * — see [BackupRows] for what it is and is not.
 */
internal fun fingerprint(text: String): Long {
    var hash = FNV_OFFSET_BASIS
    for (byte in text.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= FNV_PRIME
    }
    return hash
}

/** FNV-1a's offset basis, as a signed 64-bit number. */
private const val FNV_OFFSET_BASIS = -3750763034362895579L

/** FNV-1a's prime. */
private const val FNV_PRIME = 1099511628211L
