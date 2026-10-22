package com.singularity.todo.feature.ai.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How much of a task a read hands back.
 *
 * A summary is what a list of tasks needs: enough to tell them apart and to decide
 * which one to look at. A full card is what one task needs: everything about it, so
 * that the reader does not have to make a second call to find out what they just
 * found.
 *
 * The two are named, rather than one of them being the default with a flag, because
 * they are different questions: "which tasks" and "what is this task".
 */
@Serializable
enum class TaskDetailLevel {
    /** Enough to tell the tasks apart. */
    @SerialName("summary")
    SUMMARY,

    /** Everything about the task. */
    @SerialName("full")
    FULL,
}

/**
 * A task, short.
 *
 * [status] and [health] answer two different questions and are kept apart: a task can
 * be open *and* overdue, and a reader that is told only "overdue" has been told only
 * half of it.
 */
@Serializable
data class TaskSummary(
    val id: String,
    val title: String,
    val projectId: String? = null,
    val dueDate: String? = null,
    val priority: String = "None",
    val tags: List<String> = emptyList(),
    /** `active` or `completed`. */
    val status: String = "active",
    /**
     * `fine`, `overdue`, `blocked` or `stagnant`; null when the reader did not assess
     * it — a tool that does not have the whole list cannot say, and saying "fine"
     * because there was nothing to compare against would be a claim it cannot support.
     */
    val health: String? = null,
    /** How much thinking the task asks for: its size plus the depth of its steps. */
    val load: Int = 0,
    /** Whether the task weighs on the user more than it matters to them. */
    val toxic: Boolean = false,
    val estimateMinutes: Int? = null,
    val isPinned: Boolean = false,
)

/**
 * A task, whole.
 *
 * The same fields a [TaskSummary] carries, plus what only a single task can be asked
 * about: its description, its subtasks, its checklist, and what it is waiting on.
 */
@Serializable
data class TaskDetail(
    val id: String,
    val title: String,
    val description: String? = null,
    val projectId: String? = null,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val priority: String = "None",
    val tags: List<String> = emptyList(),
    /** `active` or `completed`. */
    val status: String = "active",
    /** `fine`, `overdue`, `blocked` or `stagnant`. */
    val health: String = "fine",
    /** How much thinking the task asks for: its size plus the depth of its steps. */
    val load: Int = 0,
    /** How much the task weighs on the user: the load, plus what blocks it, plus its age. */
    val stress: Int = 0,
    /** How much the task matters, from the priority the user gave it. */
    val value: Int = 0,
    /** Whether the task weighs on the user more than it matters to them. */
    val toxic: Boolean = false,
    val estimateMinutes: Int? = null,
    val isPinned: Boolean = false,
    val subtaskCount: Int = 0,
    val checklistDone: Int = 0,
    val checklistTotal: Int = 0,
    /** The titles of the tasks this one is waiting on. */
    val blockers: List<String> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * How the selected tasks are doing.
 *
 * The counts are the ones the app shows, so a reader is told the same thing the
 * statistics screen would tell them.
 */
@Serializable
data class TaskHealthSummary(
    val totalOpen: Int = 0,
    val overdue: Int = 0,
    val blocked: Int = 0,
    val stagnant: Int = 0,
    /** How many of the open tasks weigh on the user more than they matter to them. */
    val toxic: Int = 0,
    /** How much thinking the open tasks ask for, on average. */
    val averageLoad: Int = 0,
)
