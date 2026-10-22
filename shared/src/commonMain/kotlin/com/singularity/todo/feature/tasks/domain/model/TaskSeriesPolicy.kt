package com.singularity.todo.feature.tasks.domain.model

/**
 * The fields of a task that describe **the work**, not a particular occurrence of it.
 *
 * ## Why these and not the others
 *
 * A series is several rows that stand for one piece of work. Editing the title of
 * one of them has to change the title of all of them — otherwise the series says two
 * different things about the same job, and there is no rule that says which one is
 * right. The same holds for the project, the tags, the priority and the rest.
 *
 * Everything that is about *when* and *how far along* the work is stays per
 * occurrence: the date itself, the time of day, the active window, the completion
 * state, the pin, the checklist, the attachments, the reminders and the
 * dependencies. Those are exactly the things where "the same for all of them" would
 * be a lie: completing one occurrence must not complete the others.
 *
 * ## One list, not two
 *
 * The rule is expressed as one list of shared fields rather than as a list of
 * per-field scopes. A second list would have to be kept in step with this one, and
 * the moment it drifts, a field gets silently applied to the wrong scope.
 *
 * @see Task.seriesId
 */
enum class TaskSharedField {
    Title,
    Description,
    Priority,
    Project,
    Tags,
    AccentColor,
    Emoji,
    Estimate,
    Recurrence,
}
