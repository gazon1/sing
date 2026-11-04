package com.singularity.todo.feature.tasks.domain.state

/**
 * The one rule a task title has to satisfy, and the wording that says so.
 *
 * The create editor validates the title in
 * [com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel]
 * and the detail editor draws the same message under the same field, so the
 * rule and its message live here once. Two copies of "Title is required" would
 * be two answers to one question, and only the copy somebody remembered would
 * be edited.
 *
 * Nothing else is validated here, and nothing is localized: the message is the
 * one the editor already shows, and localizing the task editor is its own work.
 */
object TaskTitleValidation {
    /** The message shown under the title field when the title cannot be saved. */
    const val REQUIRED_MESSAGE: String = "Title is required"

    /**
     * The message for a title that cannot be saved, or `null` for one that can.
     *
     * A title made only of whitespace is blank: a task called "   " is a task
     * with no name, and the list would show a row that says nothing.
     */
    fun error(title: String): String? = if (title.isBlank()) REQUIRED_MESSAGE else null
}
