package com.singularity.todo.feature.proposals.domain.logic

import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ReadResult
import com.singularity.todo.feature.proposals.domain.model.TaskValue

/**
 * The changes an agent may propose for a task.
 *
 * ## Why a named vocabulary and not the kind itself
 *
 * The kinds the proposal machinery understands — [ProposalItemKind] — are a domain
 * vocabulary, and a domain vocabulary is not a wire format. An agent reading a tool
 * description needs to know what to *type*, and "set_title" is something it can type;
 * "SetTaskField(TaskValue.Title(...))" is a Kotlin type it has to know the shape of.
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

    /**
     * The operations that set a field, and the value each one reads.
     *
     * The reader is the *same* reader the proposal machinery uses — [TaskValue.read] —
     * so an operation cannot accept a value the proposal would later refuse to apply.
     */
    private val FIELD_OPERATIONS: Map<String, (String) -> ReadResult<TaskValue>> = linkedMapOf(
        "set_title" to { text -> ReadResult.Ok(TaskValue.Title(text)) },
        "set_description" to { text -> ReadResult.Ok(TaskValue.Description(text)) },
        "set_priority" to { text -> TaskValue.read("Priority", text) },
        "set_due_date" to { text -> TaskValue.read("DueDate", text) },
        "set_estimate" to { text -> TaskValue.read("EstimateMinutes", text) },
        "set_status" to { text -> TaskValue.read("Status", text) },
        "set_kind" to { text -> TaskValue.read("Kind", text) },
        "set_due_time" to { text -> TaskValue.read("DueTime", text) },
        "set_project" to { text -> ReadResult.Ok(TaskValue.Project(text.trim())) },
        "set_someday" to { text -> TaskValue.read("Someday", text) },
    )

    /**
     * The operations that add or remove entries, and the kind each one makes.
     *
     * An operation with nothing to say is not a change: "add_tags" with no
     * entries proposes nothing, and the tool says so rather than storing an
     * empty change.
     */
    private val LIST_OPERATIONS: Map<String, (List<String>) -> ProposalItemKind> = linkedMapOf(
        "add_tags" to { items -> ProposalItemKind.AddTags(items) },
        "remove_tags" to { items -> ProposalItemKind.RemoveTags(items) },
        "set_tags" to { items -> ProposalItemKind.SetTaskTags(items) },
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
     * What [operation] asks for.
     *
     * Three answers, because the caller has three different things to say: the
     * change, "that is not an operation", and "that value is not one the task can
     * hold". The last one is worth its own answer — it is the model's mistake to
     * correct, and it has to be reported as such.
     */
    fun toItem(operation: String, value: String?, items: List<String>): Outcome {
        val name = operation.trim().lowercase()
        FIELD_OPERATIONS[name]?.let { read ->
            val text = value ?: return Outcome.MissingValue
            return when (val read = read(text)) {
                is ReadResult.Ok -> Outcome.Proposed(ProposalItemKind.SetTaskField(read.value))
                is ReadResult.Rejected -> Outcome.UnreadableValue(read.reason)
            }
        }
        LIST_OPERATIONS[name]?.let { build ->
            return items.takeIf { it.isNotEmpty() }
                ?.let { Outcome.Proposed(build(it)) }
                ?: Outcome.EmptyChange
        }
        return Outcome.UnknownOperation
    }

    /** What an operation asks for. */
    sealed interface Outcome {
        /** The change the operation proposes. */
        data class Proposed(val kind: ProposalItemKind) : Outcome

        /** The operation is not one of ours. */
        data object UnknownOperation : Outcome

        /** The operation needs a value, and none was given. */
        data object MissingValue : Outcome

        /** The operation was given a value that does not read. */
        data class UnreadableValue(val reason: String) : Outcome

        /** The operation was given nothing to say. */
        data object EmptyChange : Outcome
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
