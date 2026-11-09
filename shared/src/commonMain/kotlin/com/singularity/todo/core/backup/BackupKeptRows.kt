package com.singularity.todo.core.backup

/*
 * The rows a restore left alone, and the payload without them.
 *
 * These are the importer's own bookkeeping, not the restore's pipeline: they
 * answer "what did the account already have, and what travels anyway", and
 * nothing here touches the database.
 */

// Keep in sync with BackupEntityKind.entries / existingRowKeys / BackupKeptRows.counts.

/**
 * [payload], less the rows the account already holds.
 *
 * A row whose key is in [existing] is left out and counted as kept; the rest
 * travel. The removals are dropped whole: a restore that keeps what is there
 * cannot take a row out, because the row it would take out is one of the
 * rows it was asked to leave alone.
 */
internal fun withoutExistingRows(
    payload: BackupPayload,
    existing: Map<BackupEntityKind, Set<String>>,
    kept: BackupKeptRows,
): BackupPayload =
    payload.copy(
        tasks = keepNewRows(payload.tasks, BackupEntityKind.TASKS, existing, kept) { it.id },
        notes = keepNewRows(payload.notes, BackupEntityKind.NOTES, existing, kept) { it.id },
        projects = keepNewRows(payload.projects, BackupEntityKind.PROJECTS, existing, kept) { it.id },
        tags = keepNewRows(payload.tags, BackupEntityKind.TAGS, existing, kept) { it.id },
        attachments = keepNewRows(payload.attachments, BackupEntityKind.ATTACHMENTS, existing, kept) { it.id },
        attachmentAnnotations =
            keepNewRows(
                payload.attachmentAnnotations,
                BackupEntityKind.ATTACHMENT_ANNOTATIONS,
                existing,
                kept,
            ) { it.id },
        taskTags =
            keepNewRows(payload.taskTags, BackupEntityKind.TASK_TAGS, existing, kept) {
                it.taskId.link(it.tagId)
            },
        taskDependencies =
            keepNewRows(payload.taskDependencies, BackupEntityKind.TASK_DEPENDENCIES, existing, kept) {
                it.taskId.link(it.dependsOnTaskId)
            },
        agendaViews = keepNewRows(payload.agendaViews, BackupEntityKind.AGENDA_VIEWS, existing, kept) { it.id },
        taskReminders =
            keepNewRows(payload.taskReminders, BackupEntityKind.TASK_REMINDERS, existing, kept) { it.id },
        projectReminders =
            keepNewRows(payload.projectReminders, BackupEntityKind.PROJECT_REMINDERS, existing, kept) { it.id },
        checklistItems =
            keepNewRows(payload.checklistItems, BackupEntityKind.CHECKLIST_ITEMS, existing, kept) { it.id },
        tagGroups = keepNewRows(payload.tagGroups, BackupEntityKind.TAG_GROUPS, existing, kept) { it.id },
        projectTagGroups =
            keepNewRows(payload.projectTagGroups, BackupEntityKind.PROJECT_TAG_GROUPS, existing, kept) {
                it.projectId.link(it.tagGroupId)
            },
        savedSearches =
            keepNewRows(payload.savedSearches, BackupEntityKind.SAVED_SEARCHES, existing, kept) { it.id },
        timeEntries = keepNewRows(payload.timeEntries, BackupEntityKind.TIME_ENTRIES, existing, kept) { it.id },
        profiles = keepNewRows(payload.profiles, BackupEntityKind.PROFILES, existing, kept) { it.id },
        removals = emptyMap(),
    )

/** The rows of [rows] the account does not have, with each kept row recorded once. */
internal fun <T> keepNewRows(
    rows: List<T>,
    kind: BackupEntityKind,
    existing: Map<BackupEntityKind, Set<String>>,
    kept: BackupKeptRows,
    key: (T) -> String,
): List<T> {
    if (rows.isEmpty()) return rows
    val present = existing[kind].orEmpty()
    return rows.filterNot { row ->
        val rowKey = key(row)
        when {
            // The account held it at restore start: leave it alone and count it once.
            rowKey in present -> {
                kept.add(kind, rowKey)
                true
            }

            // An earlier payload in this same restore wrote it (e.g. the base): do
            // not re-insert — plain @Insert link rows would collide on their PK.
            kept.wasWritten(kind, rowKey) -> {
                true
            }

            else -> {
                kept.markWritten(kind, rowKey)
                false
            }
        }
    }
}

/**
 * The rows a restore left alone, by kind.
 *
 * A set per kind, not a counter: a row that travels in the base and again in
 * a change is matched twice and is still one kept row. The counts are the
 * sizes of the sets.
 */
internal class BackupKeptRows {
    private val keys = mutableMapOf<BackupEntityKind, MutableSet<String>>()
    private val written = mutableMapOf<BackupEntityKind, MutableSet<String>>()

    // Compile canary: if a BackupEntityKind is added, update this exhaustive when,
    // existingRowKeys(), withoutExistingRows(), counts(), and the DAO/export reads.
    @Suppress("unused")
    private fun allKindsHandled(kind: BackupEntityKind): Unit =
        when (kind) {
            BackupEntityKind.TASK_TAGS,
            BackupEntityKind.TASK_DEPENDENCIES,
            BackupEntityKind.CHECKLIST_ITEMS,
            BackupEntityKind.PROJECT_TAG_GROUPS,
            BackupEntityKind.ATTACHMENT_ANNOTATIONS,
            BackupEntityKind.ATTACHMENTS,
            BackupEntityKind.AGENDA_VIEWS,
            BackupEntityKind.SAVED_SEARCHES,
            BackupEntityKind.TASK_REMINDERS,
            BackupEntityKind.PROJECT_REMINDERS,
            BackupEntityKind.TIME_ENTRIES,
            BackupEntityKind.TAGS,
            BackupEntityKind.TAG_GROUPS,
            BackupEntityKind.TASKS,
            BackupEntityKind.NOTES,
            BackupEntityKind.PROJECTS,
            BackupEntityKind.PROFILES,
            -> Unit
        }

    fun add(
        kind: BackupEntityKind,
        key: String,
    ) {
        keys.getOrPut(kind) { mutableSetOf() }.add(key)
    }

    fun markWritten(
        kind: BackupEntityKind,
        key: String,
    ) {
        written.getOrPut(kind) { mutableSetOf() }.add(key)
    }

    fun wasWritten(
        kind: BackupEntityKind,
        key: String,
    ): Boolean = written[kind]?.contains(key) == true

    fun counts(): EntityCounts {
        val c = BackupEntityKind.entries.associateWith { size(it) }
        // Exhaustive: a new BackupEntityKind must be mapped here or this won't compile-cleanly by intent?
        // Build explicitly so every kind is named.
        return EntityCounts(
            tasks = c.value(BackupEntityKind.TASKS),
            notes = c.value(BackupEntityKind.NOTES),
            projects = c.value(BackupEntityKind.PROJECTS),
            tags = c.value(BackupEntityKind.TAGS),
            attachments = c.value(BackupEntityKind.ATTACHMENTS),
            attachmentAnnotations = c.value(BackupEntityKind.ATTACHMENT_ANNOTATIONS),
            taskTags = c.value(BackupEntityKind.TASK_TAGS),
            taskDependencies = c.value(BackupEntityKind.TASK_DEPENDENCIES),
            agendaViews = c.value(BackupEntityKind.AGENDA_VIEWS),
            taskReminders = c.value(BackupEntityKind.TASK_REMINDERS),
            projectReminders = c.value(BackupEntityKind.PROJECT_REMINDERS),
            checklistItems = c.value(BackupEntityKind.CHECKLIST_ITEMS),
            tagGroups = c.value(BackupEntityKind.TAG_GROUPS),
            projectTagGroups = c.value(BackupEntityKind.PROJECT_TAG_GROUPS),
            savedSearches = c.value(BackupEntityKind.SAVED_SEARCHES),
            timeEntries = c.value(BackupEntityKind.TIME_ENTRIES),
            profiles = c.value(BackupEntityKind.PROFILES),
        ).also { assertAllKindsCounted(c) }
    }

    private fun assertAllKindsCounted(c: Map<BackupEntityKind, Int>) {
        for (kind in BackupEntityKind.entries) {
            require(
                kind in c,
            ) { "BackupEntityKind.$kind missing from kept counts" }
        }
    }

    private fun Map<BackupEntityKind, Int>.value(kind: BackupEntityKind): Int = getValue(kind)

    private fun size(kind: BackupEntityKind): Int = keys[kind]?.size ?: 0
}
