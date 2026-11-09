package com.singularity.todo.importer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LegacyBackup(val version: Int = 0, val created: String? = null, val data: LegacyData = LegacyData())

@Serializable
data class LegacyData(
    val tags: List<LegacyTag> = emptyList(),
    val tasks: List<LegacyTask> = emptyList(),
    val taskGroups: List<LegacyTaskGroup> = emptyList(),
    val projects: List<LegacyProject> = emptyList(),
    val checklists: List<LegacyChecklist> = emptyList(),
    val notes: List<LegacyNote> = emptyList(),
    val timeStatistics: List<LegacyTimeStat> = emptyList(),
    val files: List<LegacyFile> = emptyList(),
    val integrations: List<LegacyIntegration> = emptyList(),
    val integrationItems: List<LegacyIntegrationItem> = emptyList(),
    val habits: List<LegacyHabit> = emptyList(),
    val habitDailyProgress: List<LegacyHabitProgress> = emptyList(),
    val migrations: List<LegacyMigration> = emptyList(),
    val notify: List<LegacyNotify> = emptyList(),
    val openedItems: List<LegacyOpenedItem> = emptyList(),
    val printTodayTasks: List<LegacyPrintToday> = emptyList(),
    val settings: List<LegacySetting> = emptyList(),
    val kanbanStatuses: List<LegacyKanbanStatus> = emptyList(),
    val kanbanViewSettings: List<LegacyKanbanView> = emptyList(),
    val smartFilters: List<JsonElement> = emptyList(),
    val pageSorts: List<JsonElement> = emptyList(),
    val favorites: List<JsonElement> = emptyList(),
    val tutorial: List<JsonElement> = emptyList(),
    val events: List<JsonElement> = emptyList(),
    val users: List<JsonElement> = emptyList(),
    val sharedItems: List<JsonElement> = emptyList(),
    val sharedRoles: List<JsonElement> = emptyList(),
    val chatMessages: List<JsonElement> = emptyList(),
    val chatSessions: List<JsonElement> = emptyList(),
    val comments: List<JsonElement> = emptyList(),
    val ganttTaskDependencies: List<JsonElement> = emptyList(),
    val ids: List<JsonElement> = emptyList(),
)

@Serializable
data class LegacyModificated(val modificated: Map<String, Long> = emptyMap())

@Serializable
data class LegacyTag(
    val _removed: Int = 0,
    val id: String,
    val title: String = "",
    val color: String = "",
    val parentOrder: Int = 0,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyTask(
    val _removed: Int = 0,
    val id: String,
    val title: String = "",
    val note: String = "",
    val externalId: String? = null,
    val start: String? = null,
    val createdDate: String? = null,
    val journalDate: String? = null,
    val priority: Int = 1,
    val complete: Int = 0,
    val state: Int = 1,
    val checked: Int = 0,
    val showInBasket: Boolean = false,
    val deferred: Boolean = false,
    val tags: List<String> = emptyList(),
    val seenToday: String? = null,
    val useTime: Boolean = false,
    val timeLength: Int? = null,
    val notifies: List<Int>? = null,
    val startNotifiesReaded: List<Int>? = null,
    val deleteDate: String? = null,
    val projectId: String? = null,
    val group: String? = null,
    val deadline: String? = null,
    val deadlineNotifyReaded: Boolean? = null,
    val parent: String? = null,
    val recurrenceGeneratorId: String? = null,
    val recurrence: LegacyRecurrence? = null,
    val scheduleOrder: Int? = null,
    val parentOrder: Int = 0,
    val integrationItemId: String? = null,
    val pomodoroCount: Int? = null,
    val pomodoroTotalTime: Int? = null,
    val _activePlanned: Int? = null,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyRecurrence(
    val ending: LegacyEnding? = null,
    val repeat: LegacyRepeat? = null,
    val paused: Boolean = false,
    val time: String? = null,
    val nextTime: String? = null,
    val startTime: String? = null,
    val onlyOnComplete: Boolean = false,
    val startAfterComplete: Boolean = false,
    val notifies: List<Int>? = null,
    val alarmNotify: Boolean = false,
    val timeLength: Int? = null,
)

@Serializable
data class LegacyEnding(
    val type: String = "NONE",
    val iterations: Int = 0,
    val limit: Int = 0,
    val date: String? = null,
)

@Serializable
data class LegacyRepeat(
    val type: String = "",
    val days: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    val interval: Int = 1,
)

@Serializable
data class LegacyTaskGroup(
    val _removed: Int = 0,
    val id: String,
    val title: String = "",
    val parent: String? = null,
    val parentOrder: Int = 0,
    val fake: Boolean = false,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyProject(
    val _removed: Int = 0,
    val id: String,
    val title: String = "",
    val color: String = "",
    val emoji: String = "",
    val note: String = "",
    val journalDate: String? = null,
    val reviewValidationDate: String? = null,
    val reviewValidationInterval: Int = -1,
    val showInBasket: Boolean = false,
    val start: String = "",
    val tags: List<String> = emptyList(),
    val parent: String? = null,
    val parentOrder: Int = 0,
    val deleteDate: String? = null,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyChecklist(
    val _removed: Int = 0,
    val id: String,
    val done: Boolean = false,
    val title: String = "",
    val parent: String = "",
    val parentOrder: Int = 0,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyNote(
    val _removed: Int = 0,
    val id: String,
    val containerId: String = "",
    val content: String = "",
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyTimeStat(
    val _removed: Int = 0,
    val id: String,
    val relatedTaskId: String = "",
    val start: String = "",
    val end: String = "",
    val secondsPassed: Int = 0,
    val quantity: Int = 0,
    val source: Int = 0,
    val outdated: Boolean = false,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyFile(
    val _removed: Int = 0,
    val id: String,
    val containerId: String = "",
    val name: String = "",
    val size: Long = 0L,
    val cloudStatus: Int = 0,
    val lastModified: Long = 0L,
    val type: String = "",
    val realFileId: String = "",
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyIntegration(
    val _removed: Int = 0,
    val id: String,
    val externalId: String = "",
    val name: String = "",
    val email: String = "",
    // credentials intentionally not modeled as typed; skipped entirely
    val syncId: String? = null,
    val type: Int = 0,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyIntegrationItem(
    val _removed: Int = 0,
    val id: String,
    val externalId: String = "",
    val integrationId: String = "",
    val name: String = "",
    val color: String = "",
    val twoWaySync: Int = 0,
    val timePeriod: Int = 0,
    val taskWithoutTime: Int = 0,
    val onDone: Int = 0,
    val mark: String = "",
    val timezone: String = "",
    val canUseTwoway: Boolean = false,
    val skipSync: Boolean = false,
    val syncId: String? = null,
    val syncDateFrom: Long? = null,
    val twowaySyncDate: Long? = null,
    val sourceProjectId: String? = null,
    val targetProjectId: String? = null,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyHabit(
    val _removed: Int = 0,
    val id: String,
    val title: String = "",
    val description: String = "",
    val color: String = "",
    val status: String = "",
    val order: Int = 0,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyHabitProgress(
    val _removed: Int = 0,
    val id: String,
    val habit: String = "",
    val date: String = "",
    val progress: Int = 0,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyMigration(
    val _removed: Int = 0,
    val id: String,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyNotify(
    val _removed: Int = 0,
    val id: String,
    val read: Boolean = false,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyOpenedItem(
    val _removed: Int = 0,
    val id: String,
    val entityId: String = "",
    val contextId: String = "",
    val opened: Boolean = false,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyPrintToday(
    val _removed: Int = 0,
    val id: String,
    val major: List<String> = emptyList(),
    val onTime: List<String> = emptyList(),
    val highPriority: List<String> = emptyList(),
    val lowPriority: List<String> = emptyList(),
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacySetting(
    val _removed: Int = 0,
    val id: String,
    val value: JsonElement? = null,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyKanbanStatus(
    val _removed: Int = 0,
    val id: String,
    val projectId: String = "",
    val name: String = "",
    val kanbanOrder: Int = 0,
    val numberOfColumns: Int = 0,
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)

@Serializable
data class LegacyKanbanView(
    val _removed: Int = 0,
    val id: String,
    val projectId: String = "",
    val viewType: String = "",
    val modificatedDate: Long = 0L,
    val modificated: Map<String, Long> = emptyMap(),
)
