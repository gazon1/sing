package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.tasks.domain.model.TaskSharedField
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent

/**
 * The one place that knows which field an intent writes, and therefore whether
 * the write belongs to the whole series or to one occurrence.
 *
 * ## Why it is one function and not a list of rules
 *
 * The rule "title describes the work, completion describes the occurrence" has
 * to be applied by two different slots — the entity slot writes priority,
 * project and tags; the draft slot writes title and description. If each slot
 * kept its own list, the lists would drift, and a field added to one and not to
 * the other would be written to one occurrence while the user was told it
 * applies to the series.
 *
 * So the mapping lives here, and both slots ask it.
 *
 * ## The rule itself
 *
 * A field that describes *the work* is shared: editing it edits the whole
 * series. A field that describes *this occurrence* — the date, the time of
 * day, the completion state, the pin, the checklist — stays with the
 * occurrence, because "the same for all of them" would be a lie there.
 *
 * Everything that is not listed below therefore writes one occurrence: the
 * dates, the times, the kind, the pin, the dependencies, the completion state.
 *
 * ## The tags are in the list, but the slot does not write them
 *
 * The tag intents map to [TaskSharedField.Tags] because the rule is the rule:
 * the tags describe the work, so every member of a series carries them. The
 * entity slot does not write them through [sharedFieldOf] though — it writes
 * them through `setTags`, the path that owns the cross-ref table the tags live
 * in. The series is repaired there: the row whose tags changed says what the
 * work is tagged with, and the other members follow it.
 *
 * The mapping stays because the rule is unchanged, and it is what states it:
 * a field that is a shared field of a series is a shared field of a series,
 * whatever path writes it.
 *
 * @see TaskSharedField
 */
internal fun sharedFieldOf(intent: TaskDetailIntent.Domain): TaskSharedField? = when (intent) {
    is TaskDetailIntent.Domain.TitleChanged -> TaskSharedField.Title
    is TaskDetailIntent.Domain.DescriptionChanged -> TaskSharedField.Description
    is TaskDetailIntent.Domain.SetPriority -> TaskSharedField.Priority
    is TaskDetailIntent.Domain.SetProject -> TaskSharedField.Project
    is TaskDetailIntent.Domain.SetTags -> TaskSharedField.Tags
    is TaskDetailIntent.Domain.RemoveTag -> TaskSharedField.Tags
    is TaskDetailIntent.Domain.SetRecurrence -> TaskSharedField.Recurrence
    is TaskDetailIntent.Domain.SetEstimate -> TaskSharedField.Estimate
    else -> null
}
