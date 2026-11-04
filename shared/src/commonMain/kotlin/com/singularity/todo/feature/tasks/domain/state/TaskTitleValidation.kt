package com.singularity.todo.feature.tasks.domain.state

/**
 * The one rule a task title has to satisfy, and the wording that says so.
 *
 * It lives beside [TaskDraft] in the shared module, not under `shared-ui/.../presentation/state`,
 * because it is a pure answer about a string: no Compose, no ViewModel, no platform. That is what
 * makes it testable next to the draft it judges, and what lets anything in `shared` ask the same
 * question the editors ask. The file used to sit in the presentation tree while declaring a
 * `domain.state` package, which put a domain rule in a module that no domain code can see.
 *
 * Both editors — the create screen's ViewModel and the detail screen — ask this object, so the rule
 * and its message exist once. Two copies of "Title is required" would be two answers to one
 * question, and only the copy somebody remembered would be edited.
 *
 * Nothing else is validated here, and nothing is localized: the message is the one the editors
 * already show, and localizing the task editor is its own work.
 */
object TaskTitleValidation {
    /** The message shown under the title field when the title cannot be saved. */
    const val REQUIRED_MESSAGE: String = "Title is required"

    /**
     * The message for a title that cannot be saved, or `null` for one that can.
     *
     * A title made only of whitespace is blank: a task called "   " is a task with no name, and the
     * list would show a row that says nothing.
     */
    fun error(title: String): String? = if (title.isBlank()) REQUIRED_MESSAGE else null
}
