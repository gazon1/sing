package com.singularity.todo.feature.tasks.presentation.components.detail

/**
 * The optional properties of a task that the detail screen can show or hide.
 *
 * The list is the vocabulary of the `+ Ещё` sheet: it names the properties that
 * are not on the screen and the ones that are. It is one enum because the same
 * property is a row, a section and a sheet item — three faces of one thing — and
 * three enums would drift.
 *
 * @param hideable Whether the property can be absent from the screen. The
 *        checklist and the subtasks cannot: their header is the way to write into
 *        them, so a hidden header would take the way in with it.
 */
enum class TaskOptionalProperty(val label: String, val hideable: Boolean) {
    Checklist("Checklist", hideable = false),
    Subtasks("Subtasks", hideable = false),
    Attachments("Attachments", hideable = true),
    Logbook("Logbook", hideable = true),
    RelatedTasks("Related tasks", hideable = true),
    TimeTracking("Time", hideable = true),
    WorkflowState("State", hideable = true),
}

/**
 * The optional properties that are not on the screen right now.
 *
 * The list is derived, never a static one: a property that has content is on the
 * screen and does not belong here, and a property the user has added through the
 * `+ Ещё` sheet belongs to the screen even while it is still empty.
 *
 * @param hasContent The properties that have something to show. The caller
 *        decides what "something" is — an attachment, a logbook entry, a
 *        relation, a tracked time, a claimed state.
 * @param revealed The properties the user has added through the `+ Ещё` sheet.
 *        They stay on the screen from then on.
 */
internal fun hiddenOptionalProperties(
    hasContent: Set<TaskOptionalProperty>,
    revealed: Set<TaskOptionalProperty>,
): List<TaskOptionalProperty> =
    TaskOptionalProperty.entries.filter { property ->
        property.hideable && property !in hasContent && property !in revealed
    }
