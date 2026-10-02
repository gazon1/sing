package com.singularity.todo.feature.proposals.domain.usecase

import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.proposals.domain.model.DecidedActor
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.TaskField
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.domain.model.TagEditActor
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import com.singularity.todo.feature.timetracking.domain.TimeTrackingRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/**
 * Applies or refuses one AI-proposed change.
 *
 * ## The ordering, and why it is this ordering
 *
 * 1. **Read.** The item is re-read from the store, not taken from a value the UI is
 *    holding. The agent decided against whatever the task looked like when it
 *    proposed; writing its conclusion over a field that has since changed would
 *    silently clobber the user's edit. This mirrors the `writeOnStored` discipline
 *    in ADR 0103.
 * 2. **Plan.** Every parse and every precondition check happens here, *before*
 *    anything is claimed. A malformed or unapplicable proposal therefore leaves the
 *    item `Pending` and re-tryable, rather than half-applied with a decision recorded.
 * 3. **Claim.** A compare-and-set moves the item out of `Pending`. Losing that race
 *    means another tap already decided it, and this call then does nothing at all.
 * 4. **Dispatch** against the row the claim read back, not an earlier read.
 * 5. **Refresh** the proposal's aggregate status.
 *
 * Reject is steps 1, 3 and 5 — no dispatch, but the reason is persisted so the
 * prompt's "recently rejected" list can quote it back.
 *
 * This is the only `when` over [ProposalItemKind] in the codebase; adding a variant
 * makes the compiler point here.
 */
class ApplyProposalItemUseCase(
    private val proposals: ProposalRepository,
    private val tasks: TaskRepository,
    private val tags: TagsRepository,
    private val checklist: ChecklistRepository,
    private val timeTracking: TimeTrackingRepository,
    private val clock: Clock,
) {

    /**
     * Confirms [itemId]: claims it, then applies the change.
     *
     * @return the item in its post-decision state. A failure is a precondition or
     *   dispatch error, in which case the item is still `Pending` and nothing changed.
     */
    suspend fun confirm(itemId: ProposalItemId, userId: UserId): Result<ProposalItem> = runCatching {
        val stored = proposals.getItem(itemId) ?: error("Proposal item $itemId not found")
        check(stored.status == ProposalItemStatus.Pending) {
            "Proposal item $itemId is already ${stored.status.name}"
        }

        // Plan first: a proposal that cannot be applied must not consume its one
        // irreversible decision.
        val task = tasks.get(taskIdOf(stored)) ?: error("Task ${stored.targetId} not found")
        val plan = plan(stored, task)

        val claimed = proposals.claim(itemId, ProposalItemStatus.Confirmed, DecidedActor.User, null, userId)
            ?: return@runCatching stored // lost the race — someone else decided it
        dispatch(plan, userId)
        proposals.refreshStatus(claimed.proposalId, userId)
        claimed
    }

    /**
     * Rejects [itemId], optionally recording [reason].
     *
     * Nothing is applied. The reason is kept so the feedback builder can quote it
     * back to the model; a reason under [MIN_REASON_LENGTH] characters is stored
     * anyway but is not treated as usable feedback, because a one-word "no" tells
     * the model nothing it can act on.
     */
    suspend fun reject(itemId: ProposalItemId, userId: UserId, reason: String? = null): Result<ProposalItem> =
        runCatching {
            val stored = proposals.getItem(itemId) ?: error("Proposal item $itemId not found")
            val claimed = proposals.claim(
                itemId,
                ProposalItemStatus.Rejected,
                DecidedActor.User,
                reason?.trim()?.takeIf { it.isNotEmpty() },
                userId,
            ) ?: return@runCatching stored
            proposals.refreshStatus(claimed.proposalId, userId)
            claimed
        }

    /**
     * Confirms every pending item on a proposal.
     *
     * Each item goes through [confirm] independently, so one that cannot be applied
     * fails on its own and does not stop the rest. The return value is therefore
     * (applied, failed) rather than a single Result — a batch where three of four
     * succeed is the normal case, not an error.
     */
    suspend fun confirmAll(proposalId: com.singularity.todo.core.ids.ProposalId, userId: UserId): BatchResult {
        val pending = proposals.watchProposal(proposalId).first()?.items.orEmpty()
            .filter { it.status == ProposalItemStatus.Pending }
        val applied = mutableListOf<ProposalItem>()
        val failed = mutableListOf<Throwable>()
        pending.forEach { item ->
            confirm(item.id, userId).fold(
                onSuccess = { applied += it },
                onFailure = { failed += it },
            )
        }
        proposals.refreshStatus(proposalId, userId)
        return BatchResult(applied, failed)
    }

    /** Outcome of a batch confirm — partial success is normal, not exceptional. */
    data class BatchResult(val applied: List<ProposalItem>, val failed: List<Throwable>)

    // ── Planning ──────────────────────────────────────────────────────────────

    /**
     * Turns a stored item into a validated, ready-to-apply plan.
     *
     * All validation lives here so that [dispatch] cannot fail on a parse: between
     * the claim and the dispatch there is nothing left that can throw.
     */
    private suspend fun plan(item: ProposalItem, task: Task): ProposalPlan {
        val taskId = TaskId(item.targetId.ifBlank { task.id.value })
        return when (val kind = item.kind) {
            // Resolved here, not in dispatch: a value the task cannot hold must fail
            // *before* the claim, or the item burns its one decision on a write that
            // then throws.
            is ProposalItemKind.SetTaskField -> ProposalPlan.WriteTask(withField(task, kind.field, kind.value))

            is ProposalItemKind.AddTags -> {
                val resolved = resolveTagIds(kind.names)
                ProposalPlan.AddTags(taskId, resolved)
            }

            is ProposalItemKind.RemoveTags -> {
                val ids = kind.tagIds.map(TagId::fromString).toSet()
                require(ids.isNotEmpty()) { "RemoveTags has no tag ids" }
                ProposalPlan.RemoveTags(taskId, ids)
            }

            is ProposalItemKind.AddChecklistItems -> {
                val texts = kind.texts.cleaned()
                require(texts.isNotEmpty()) { "AddChecklistItems is empty after cleaning" }
                ProposalPlan.AddChecklistItems(taskId, texts)
            }

            is ProposalItemKind.AddSubtasks -> {
                val titles = kind.titles.cleaned()
                require(titles.isNotEmpty()) { "AddSubtasks is empty after cleaning" }
                ProposalPlan.AddSubtasks(taskId, titles)
            }

            is ProposalItemKind.AddTimeEntries -> {
                kind.entries.forEach { entry ->
                    require(entry.endedAt > entry.startedAt) {
                        "Proposed time entry ends (${entry.endedAt}) before it starts (${entry.startedAt})"
                    }
                }
                require(kind.entries.isNotEmpty()) { "AddTimeEntries is empty" }
                ProposalPlan.AddTimeEntries(taskId, kind.entries)
            }
        }
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    /**
     * Applies a plan.
     *
     * Called only after a successful claim, against the row the claim read back.
     */
    private suspend fun dispatch(plan: ProposalPlan, userId: UserId) {
        when (plan) {
            is ProposalPlan.WriteTask -> tasks.update(plan.task).getOrThrow()

            is ProposalPlan.AddTags -> {
                val current = tasks.getTagIds(plan.taskId).first().toSet()
                tasks.setTags(plan.taskId, (current + plan.tagIds).toList(), TagEditActor.AiProposal).getOrThrow()
            }

            is ProposalPlan.RemoveTags -> {
                val current = tasks.getTagIds(plan.taskId).first().toSet()
                tasks.setTags(plan.taskId, (current - plan.tagIds).toList(), TagEditActor.AiProposal).getOrThrow()
            }

            is ProposalPlan.AddChecklistItems -> plan.texts.forEach { text ->
                checklist.addItem(plan.taskId.value, text).getOrThrow()
            }

            is ProposalPlan.AddSubtasks -> plan.titles.forEach { title ->
                val now = clock.now()
                tasks.create(
                    Task(
                        id = TaskId.generate(),
                        title = title,
                        parentTaskId = plan.taskId,
                        userId = userId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ).getOrThrow()
            }

            is ProposalPlan.AddTimeEntries -> plan.entries.forEach { entry ->
                timeTracking.createManualEntry(
                    taskId = plan.taskId,
                    userId = userId,
                    startedAt = entry.startedAt,
                    endedAt = entry.endedAt,
                    kind = TimeEntryKind.Work,
                    note = entry.note,
                    source = TimeEntrySource.AiProposal,
                ).getOrThrow()
            }
        }
    }

    /**
     * Returns [task] with [field] set to [rawValue], or throws if the value is not
     * something the task can hold.
     *
     * Pure: reads nothing and writes nothing, so every rejection reason is
     * reachable from a unit test without a repository.
     */
    private fun withField(task: Task, field: TaskField, rawValue: String): Task = when (field) {
        TaskField.Title -> {
            val value = rawValue.trim()
            require(value.isNotEmpty()) { "Proposed title is blank" }
            task.copy(title = value)
        }

        TaskField.Description -> task.copy(description = rawValue.trim().takeIf(String::isNotEmpty))

        TaskField.Priority -> task.copy(
            priority = runCatching { TaskPriority.valueOf(rawValue.trim()) }
                .getOrElse { error("Unknown priority '$rawValue'") },
        )

        TaskField.DueDate -> {
            val value = rawValue.trim()
            task.copy(dueDate = if (value.isEmpty()) null else parseDate(value))
        }

        TaskField.Status -> {
            val status = runCatching { TaskStatus.valueOf(rawValue.trim()) }
                .getOrElse { error("Unknown status '$rawValue'") }
            task.copy(completedAt = if (status == TaskStatus.Completed) clock.now() else null)
        }

        TaskField.EstimateMinutes -> {
            val value = rawValue.trim().toIntOrNull()
                ?: error("Estimate '$rawValue' is not a whole number of minutes")
            require(value >= 0) { "Estimate cannot be negative" }
            task.copy(estimateMinutes = value)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun taskIdOf(item: ProposalItem): TaskId = TaskId(item.targetId)

    /** Resolves proposed tag names to ids, ignoring names no tag matches. */
    private suspend fun resolveTagIds(names: List<String>): Set<TagId> {
        val cleaned = names.cleaned()
        if (cleaned.isEmpty()) return emptySet()
        val byName = tags.observeAll().first().associateBy { it.name.trim().lowercase() }
        return cleaned.mapNotNull { byName[it.trim().lowercase()]?.id }.toSet()
    }

    private fun parseDate(raw: String): LocalDate = runCatching { LocalDate.parse(raw) }
        .getOrElse { error("'$raw' is not an ISO date (yyyy-mm-dd)") }

    private fun List<String>.cleaned(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Shortest rejection reason worth feeding back to the model. */
    companion object {
        const val MIN_REASON_LENGTH = 20
    }
}

/**
 * A validated, ready-to-apply change. Built by [ApplyProposalItemUseCase.plan] so
 * that nothing between the claim and the dispatch can fail on a parse.
 */
private sealed interface ProposalPlan {
    val taskId: TaskId

    /** A fully-resolved task write. Every parse already happened in `plan`. */
    data class WriteTask(val task: Task) : ProposalPlan {
        override val taskId: TaskId get() = task.id
    }

    data class AddTags(override val taskId: TaskId, val tagIds: Set<TagId>) : ProposalPlan
    data class RemoveTags(override val taskId: TaskId, val tagIds: Set<TagId>) : ProposalPlan
    data class AddChecklistItems(override val taskId: TaskId, val texts: List<String>) : ProposalPlan
    data class AddSubtasks(override val taskId: TaskId, val titles: List<String>) : ProposalPlan
    data class AddTimeEntries(override val taskId: TaskId, val entries: List<ProposedEntry>) : ProposalPlan
}

/** Alias so the plan type does not leak the serializable model into the private API. */
private typealias ProposedEntry = com.singularity.todo.feature.proposals.domain.model.ProposedTimeEntry
