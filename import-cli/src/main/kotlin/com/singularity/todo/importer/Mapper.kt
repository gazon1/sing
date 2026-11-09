package com.singularity.todo.importer

import com.singularity.todo.core.backup.BackupFormat
import com.singularity.todo.core.backup.BackupPayload
import com.singularity.todo.core.backup.ChecklistItemDto
import com.singularity.todo.core.backup.NoteDto
import com.singularity.todo.core.backup.ProjectDto
import com.singularity.todo.core.backup.TagDto
import com.singularity.todo.core.backup.TaskDto
import com.singularity.todo.core.backup.TaskTagDto
import com.singularity.todo.core.backup.TimeEntryDto
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceTermination
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

object Mapper {
    val json = Json { ignoreUnknownKeys = true }

    fun parseInstantMs(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { Instant.parse(s).toEpochMilliseconds() }.getOrElse {
            runCatching { Instant.parse("${s}T00:00:00Z").toEpochMilliseconds() }.getOrNull()
        }
    }

    fun colorHexToInt(hex: String): Int {
        if (hex.isBlank()) return 0
        val h = hex.trim().removePrefix("#")
        return runCatching {
            val v = h.toLong(16)
            when (h.length) {
                6 -> (0xFF000000 or v).toInt()
                8 -> v.toInt()
                else -> 0
            }
        }.getOrDefault(0)
    }

    /**
     * Legacy icons are one or more hex code points joined by `-` (a flag, a
     * profession, an emoji with a variation selector). Every part becomes a
     * character, so the whole sequence survives instead of failing on the first
     * `-` and leaving the raw hex in the icon field.
     */
    fun emojiCodepointToIcon(code: String): String? {
        if (code.isBlank()) return null
        val icon =
            code
                .split('-')
                .mapNotNull { part ->
                    val point = part.toIntOrNull(16) ?: return@mapNotNull null
                    if (!Character.isValidCodePoint(point)) return@mapNotNull null
                    String(Character.toChars(point))
                }.joinToString("")
        return icon.ifEmpty { null }
    }

    fun wordCount(s: String): Int = s.split(Regex("\\s+")).count { it.isNotBlank() }

    fun charCount(s: String): Int = s.length

    fun map(
        legacy: LegacyBackup,
        markCompleted: Boolean,
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): MapperResult {
        val data = legacy.data
        val tasks = data.tasks.filter { it._removed == 0 }
        val projects = data.projects.filter { it._removed == 0 }
        val notes = data.notes.filter { it._removed == 0 }
        val tags = data.tags.filter { it._removed == 0 }
        val tasksById = tasks.associateBy { it.id }
        val projectsById = projects.associateBy { it.id }

        val payload =
            BackupPayload(
                schemaVersion = BackupFormat.SCHEMA_VERSION,
                tasks =
                    mapTasks(
                        tasks,
                        tasksById,
                        projectsById,
                        notes.associateBy { it.id },
                        markCompleted,
                        onProgress,
                    ),
                notes = mapNotes(notes, tasksById, projectsById),
                projects = mapProjects(projects, projectsById, notes.associateBy { it.id }),
                tags = mapTags(tags),
                taskTags = taskTagsOf(tasks, tags.associateBy { it.id }),
                checklistItems = mapChecklists(data.checklists, tasksById),
                timeEntries = mapTimeEntries(data.timeStatistics, tasksById),
            )

        val completedCandidates = tasks.count { it.checked == 1 && it.state == 0 }
        return MapperResult(payload = payload, skipped = skippedCounts(data, completedCandidates))
    }
}

private fun mapTags(tags: List<LegacyTag>): List<TagDto> =
    tags.map { t ->
        TagDto(
            id = t.id,
            name = t.title,
            color = Mapper.colorHexToInt(t.color),
            createdAt = t.modificatedDate.takeIf { it > 0 } ?: now(),
            updatedAt = t.modificatedDate.takeIf { it > 0 } ?: now(),
            sortOrder = t.parentOrder,
        )
    }

private fun mapNotes(
    notes: List<LegacyNote>,
    tasksById: Map<String, LegacyTask>,
    projectsById: Map<String, LegacyProject>,
): List<NoteDto> =
    notes
        .filter { n ->
            (n.containerId.startsWith("T-") && n.containerId in tasksById) ||
                (n.containerId.startsWith("P-") && n.containerId in projectsById)
        }.map { n ->
            val text = LegacyText.toPlainText(n.content)
            NoteDto(
                id = n.id,
                taskId = n.containerId.takeIf { it.startsWith("T-") },
                projectId = n.containerId.takeIf { it.startsWith("P-") },
                bodyHtml = LegacyText.toHtml(n.content),
                title = "",
                wordCount = Mapper.wordCount(text),
                charCount = Mapper.charCount(text),
                createdAt = n.modificatedDate.takeIf { it > 0 } ?: now(),
                updatedAt = n.modificatedDate.takeIf { it > 0 } ?: now(),
            )
        }

/** The legacy app puts a project away with deleteDate, which is our archive. */
private fun mapProjects(
    projects: List<LegacyProject>,
    projectsById: Map<String, LegacyProject>,
    notesById: Map<String, LegacyNote>,
): List<ProjectDto> =
    projects.map { p ->
        ProjectDto(
            id = p.id,
            name = titleText(p.title),
            color = Mapper.colorHexToInt(p.color),
            icon = Mapper.emojiCodepointToIcon(p.emoji),
            description = projectDescription(p.note, notesById),
            createdAt = Mapper.parseInstantMs(p.journalDate) ?: p.modificatedDate.takeIf { it > 0 } ?: now(),
            updatedAt = p.modificatedDate.takeIf { it > 0 } ?: now(),
            isDeleted = false,
            deletedAt = null,
            parentId = p.parent?.takeIf { it in projectsById },
            sortOrder = p.parentOrder,
            dueDate = p.start.takeIf { it.isNotBlank() }?.substringBefore('T'),
            closedAt = Mapper.parseInstantMs(p.deleteDate),
        )
    }

private fun mapTasks(
    tasks: List<LegacyTask>,
    tasksById: Map<String, LegacyTask>,
    projectsById: Map<String, LegacyProject>,
    notesById: Map<String, LegacyNote>,
    markCompleted: Boolean,
    onProgress: (String, Int, Int) -> Unit,
): List<TaskDto> {
    val mapped = mutableListOf<TaskDto>()
    tasks.forEachIndexed { idx, tk ->
        val updatedAt = tk.modificatedDate.takeIf { it > 0 } ?: now()
        val completedAt = if (markCompleted && tk.checked == 1 && tk.state == 0) updatedAt else null
        val dueDate = tk.start?.takeIf { it.isNotBlank() }?.substringBefore('T')
        mapped.add(
            TaskDto(
                id = tk.id,
                title = titleText(tk.title),
                description = notesById[tk.note]?.let { LegacyText.toPlainText(it.content).takeIf(String::isNotBlank) },
                priority =
                    when (tk.priority) {
                        0 -> "low"
                        2 -> "high"
                        else -> "medium"
                    },
                kind = "task",
                // A subtask that names no project belongs to its parent's: the
                // legacy app showed it inside that project, and a null here puts
                // it in the Inbox, away from the task it hangs off.
                projectId =
                    tk.projectId?.takeIf { it in projectsById } ?: inheritedProject(
                        tk,
                        tasksById,
                        projectsById,
                    ),
                parentTaskId = tk.parent?.takeIf { it in tasksById },
                dueDate = dueDate,
                dueTime = timeOf(tk.start, tk.useTime),
                startDate = null,
                startTime = null,
                endDate = tk.deadline?.takeIf { it.isNotBlank() }?.substringBefore('T'),
                endTime = timeOf(tk.deadline, tk.useTime),
                accentColor = null,
                emoji = null,
                completedAt = completedAt,
                someday = tk.deferred,
                // The legacy app dates a deletion with deleteDate; showInBasket is
                // a display flag that the same action happens to set, so it is not
                // the field to read (8 deleted tasks do not carry it).
                archivedAt = Mapper.parseInstantMs(tk.deleteDate),
                isPinned = false,
                estimateMinutes = tk.timeLength,
                recurrenceRule = mapRecurrence(tk.recurrence, dueDate),
                seriesId = tk.recurrenceGeneratorId,
                workflowState = if (tk._activePlanned == 1) "next_up" else "open",
                outgoingLinks = emptyList(),
                aiSuppressedTagIds = emptyList(),
                createdAt = Mapper.parseInstantMs(tk.createdDate) ?: updatedAt,
                updatedAt = updatedAt,
            ),
        )
        if (idx % 500 == 0 || idx == tasks.lastIndex) onProgress("tasks", idx + 1, tasks.size)
    }
    return mapped
}

private fun mapChecklists(
    checklists: List<LegacyChecklist>,
    tasksById: Map<String, LegacyTask>,
): List<ChecklistItemDto> =
    checklists
        .filter { it._removed == 0 && it.parent in tasksById }
        .map { c ->
            ChecklistItemDto(
                id = c.id,
                taskId = c.parent,
                title = LegacyText.toPlainText(c.title),
                isCompleted = c.done,
                sortOrder = c.parentOrder,
                createdAt = c.modificatedDate.takeIf { it > 0 } ?: now(),
                updatedAt = c.modificatedDate.takeIf { it > 0 } ?: now(),
            )
        }

private fun mapTimeEntries(
    stats: List<LegacyTimeStat>,
    tasksById: Map<String, LegacyTask>,
): List<TimeEntryDto> =
    stats
        .filter { it._removed == 0 && it.relatedTaskId in tasksById }
        .map { ts ->
            TimeEntryDto(
                id = ts.id,
                taskId = ts.relatedTaskId,
                startedAt = Mapper.parseInstantMs(ts.start) ?: 0L,
                endedAt = Mapper.parseInstantMs(ts.end),
                kind = "Work",
                source =
                    when (ts.source) {
                        0 -> "Timer"
                        1 -> "Manual"
                        2 -> "Pomodoro"
                        else -> "Manual"
                    },
                note = null,
                createdAt = ts.modificatedDate.takeIf { it > 0 } ?: now(),
                updatedAt = ts.modificatedDate.takeIf { it > 0 } ?: now(),
                deletedAt = ts.modificatedDate.takeIf { ts.outdated && it > 0 },
            )
        }

private fun taskTagsOf(
    tasks: List<LegacyTask>,
    tagsById: Map<String, LegacyTag>,
): List<TaskTagDto> =
    tasks.flatMap { tk ->
        tk.tags.filter { it in tagsById }.map { tagId -> TaskTagDto(taskId = tk.id, tagId = tagId) }
    }

private fun skippedCounts(
    data: LegacyData,
    completedCandidates: Int,
): Map<String, Int> =
    mapOf(
        "taskGroups" to data.taskGroups.size,
        "habits" to data.habits.size,
        "habitDailyProgress" to data.habitDailyProgress.size,
        "kanbanStatuses" to data.kanbanStatuses.size,
        "kanbanViewSettings" to data.kanbanViewSettings.size,
        "notify" to data.notify.size,
        "openedItems" to data.openedItems.size,
        "printTodayTasks" to data.printTodayTasks.size,
        "settings" to data.settings.size,
        "migrations" to data.migrations.size,
        "integrationItems" to data.integrationItems.size,
        "integrations" to data.integrations.size,
        "files" to data.files.size,
        "projectTags" to data.projects.sumOf { it.tags.size },
        "completedCandidates" to completedCandidates,
    )

/**
 * The legacy rule as one of ours, or null when the legacy type has no
 * counterpart. The repeat's own day numbers are the source of truth; a rule
 * that carries none falls back to the task's due date.
 */
private fun mapRecurrence(
    r: LegacyRecurrence?,
    dueDate: String?,
): String? {
    val repeat = r?.repeat ?: return null
    val base =
        if (r.startAfterComplete) {
            RecurrenceSpec.RecurrenceBase.FROM_COMPLETION
        } else {
            RecurrenceSpec.RecurrenceBase.FROM_DUE
        }
    val termination = terminationOf(r)
    val due = dueDate?.let { parseDate(it) }
    val firstDay = repeat.days.firstOrNull()
    val spec =
        when (repeat.type) {
            "EVERYDAY" -> RecurrenceSpec.Interval(base, repeat.interval.coerceAtLeast(1), DateTimeUnit.DAY, termination)
            "EVERYWEEK" -> weekSpec(repeat, base, termination)
            "EVERYMONTH" -> monthSpec(firstDay, due, base, termination)
            "EVERYYEAR" -> yearSpec(firstDay, due, base, termination)
            else -> null
        }
    return spec?.let { StableJson.encodeToString(RecurrenceSpec.serializer(), it) }
}

private fun weekSpec(
    repeat: LegacyRepeat,
    base: RecurrenceSpec.RecurrenceBase,
    termination: RecurrenceTermination?,
): RecurrenceSpec {
    val days = repeat.days.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
    val interval = RecurrenceSpec.Interval(base, repeat.interval.coerceAtLeast(1), DateTimeUnit.WEEK, termination)
    return if (days.isEmpty()) {
        interval
    } else {
        RecurrenceSpec.Weekly(base, days.map(::weekdayOf).toSet(), termination)
    }
}

/** The legacy app numbers Sunday 0; ours numbers it 7. */
private fun weekdayOf(legacy: Int): Int = if (legacy == 0) 7 else legacy

private fun monthSpec(
    firstDay: JsonElement?,
    due: LocalDate?,
    base: RecurrenceSpec.RecurrenceBase,
    termination: RecurrenceTermination?,
): RecurrenceSpec? {
    val day = (firstDay?.intField("dayNumber") ?: due?.day) ?: return null
    return RecurrenceSpec.Monthly(base, day.coerceIn(1, 31), termination)
}

private fun yearSpec(
    firstDay: JsonElement?,
    due: LocalDate?,
    base: RecurrenceSpec.RecurrenceBase,
    termination: RecurrenceTermination?,
): RecurrenceSpec? {
    val month = (firstDay?.intField("monthNumber") ?: due?.monthNumber) ?: return null
    val day = (firstDay?.intField("dayNumber") ?: due?.day) ?: return null
    return RecurrenceSpec.Yearly(base, month.coerceIn(1, 12), day.coerceIn(1, 31), termination)
}

private fun terminationOf(r: LegacyRecurrence): RecurrenceTermination? =
    r.ending?.date?.let { d -> RecurrenceTermination(endDate = parseDate(d.substringBefore('T'))) }

private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

/**
 * A project's `note` is an id that points at one of the note bodies; the
 * description is that body's words. A value that is not an id is plain text
 * from an older export and is read as it is.
 */
private fun projectDescription(
    noteRef: String,
    notesById: Map<String, LegacyNote>,
): String? {
    if (noteRef.isBlank()) return null
    val note = notesById[noteRef]
    // A ref whose body is not in the archive (the note was deleted) is not a
    // description; printing the id would be the bug this function exists to fix.
    if (note == null && noteRef.startsWith("N-")) return null
    val raw = note?.content ?: noteRef
    return LegacyText.toPlainText(raw).takeIf { it.isNotBlank() }
}

/** A title is one line of words: the legacy file sometimes stored HTML in it. */
private fun titleText(raw: String): String = LegacyText.toPlainText(raw).replace("\n", " ").trim()

/**
 * The project a subtask inherits from its parent, when it names none itself.
 *
 * The legacy app showed a subtask inside its parent's project; a null project
 * here would put it in the Inbox, away from the task it hangs off.
 */
private fun inheritedProject(
    task: LegacyTask,
    tasksById: Map<String, LegacyTask>,
    projectsById: Map<String, LegacyProject>,
): String? = task.parent?.let { tasksById[it]?.projectId }?.takeIf { it in projectsById }

/** The time part of a legacy timestamp, when the task uses times at all. */
private fun timeOf(
    timestamp: String?,
    useTime: Boolean,
): LocalTime? {
    if (!useTime || timestamp.isNullOrBlank()) return null
    val time = timestamp.substringAfter('T', "").removeSuffix("Z")
    return runCatching { LocalTime.parse(time.take(5)) }.getOrNull()
}

private fun JsonElement.intField(name: String): Int? = ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.intOrNull

private fun now(): Long = System.currentTimeMillis()

data class MapperResult(val payload: BackupPayload, val skipped: Map<String, Int>)
