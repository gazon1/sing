package com.singularity.todo.feature.proposals.domain.logic

import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.TaskField

/**
 * The changes an agent may propose for a task.
 *
 * ## Why a named vocabulary and not the kind itself
 *
 * The kinds the proposal machinery understands — [ProposalItemKind] — are a domain
 * vocabulary, and a domain vocabulary is not a wire format. An agent reading a tool
 * description needs to know what to *type*, and "set_title" is something it can type;
 * "SetTaskField(field=Title, value=...)" is a Kotlin type it has to know the shape of.
 *
 * So the tool speaks in operation names, and the mapping from a name to a kind lives
 * here — one place, so that the list of operations and the list of things the proposal
 * machinery can do cannot drift apart.
 *
 * ## Why the operations are strings and not an enum on the tool's input
 *
 * An enum in the tool's input would put the vocabulary in the tool's schema, and the
 * schema is generated from the type. That works, but it puts a proposal vocabulary in
 * a class that is about talking to a model, and the same vocabulary is wanted by the
 * server's tools, which do not share the app's tool classes. A named list here is
 * shareable.
 *
 * ## Why the operations are tables and not a `when`
 *
 * The two kinds of operation are different in shape: one sets a field to a value, the
 * other adds or removes a list. A table per shape says that once, and the operation
 * name → kind mapping is a lookup rather than a branch per name — so adding an
 * operation does not make the function more complex.
 */
object ProposalOperation {

    /** The operations that set a field, and the field each one sets. */
    private val FIELD_OPERATIONS: Map<String, TaskField> = linkedMapOf(
        "set_title" to TaskField.Title,
        "set_description" to TaskField.Description,
        "set_priority" to TaskField.Priority,
        "set_due_date" to TaskField.DueDate,
        "set_estimate" to TaskField.EstimateMinutes,
        "set_status" to TaskField.Status,
    )

    /** The operations that add or remove entries, and the kind each one makes. */
    private val LIST_OPERATIONS: Map<String, (List<String>) -> ProposalItemKind> = linkedMapOf(
        "add_tags" to { items -> ProposalItemKind.AddTags(items) },
        "remove_tags" to { items -> ProposalItemKind.RemoveTags(items) },
        "add_checklist" to { items -> ProposalItemKind.AddChecklistItems(items) },
        "add_subtasks" to { items -> ProposalItemKind.AddSubtasks(items) },
    )

    /**
     * Every operation, in the order they are offered to a reader.
     *
     * The order is the order a reader thinks in: set the fields first, then add to
     * the task. It is also the order the description lists them in, so the two cannot
     * disagree by accident.
     */
    val NAMES: List<String> = FIELD_OPERATIONS.keys.toList() + LIST_OPERATIONS.keys.toList()

    /**
     * The kind [operation] asks for, or null when the operation is not one of ours.
     *
     * The null is not an error the caller has to guess about: the caller says which
     * operations exist, and the answer here is "not one of them".
     *
     * @param value the new value, for the operations that set a field.
     * @param items the entries to add or remove, for the operations that take a list.
     */
    fun toItemKind(
        operation: String,
        value: String?,
        items: List<String>,
    ): ProposalItemKind? {
        val name = operation.trim().lowercase()
        FIELD_OPERATIONS[name]?.let { field ->
            return value?.let { ProposalItemKind.SetTaskField(field, it) }
        }
        LIST_OPERATIONS[name]?.let { build ->
            return items.takeIf { it.isNotEmpty() }?.let(build)
        }
        return null
    }

    /**
     * Whether [operation] needs a value.
     *
     * The caller uses it to say what is missing, instead of proposing a change whose
     * value is absent and letting it fail later, in the confirmation screen, where the
     * user is the one looking at it.
     */
    fun needsValue(operation: String): Boolean = FIELD_OPERATIONS.containsKey(operation.trim().lowercase())

    /**
     * Whether [operation] needs a list of items.
     */
    fun needsItems(operation: String): Boolean = LIST_OPERATIONS.containsKey(operation.trim().lowercase())
}
