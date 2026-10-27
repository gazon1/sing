package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId

/**
 * The shape of a series: which rows form one, and whether a row may carry a
 * repeat rule at all.
 *
 * This is the structural half of [TaskSeriesInvariants]. The shared-field half
 * answers "do the rows of this series say the same thing about the work"; this
 * one answers "is this batch one series", "is this row allowed to carry a rule",
 * and "which row is the work's source when a batch is written".
 *
 * The two moved apart when the object reached the function ceiling the detekt
 * rule sets: the two questions change for different reasons — a new shared
 * field touches one, a new kind of write touches the other — and keeping them in
 * one object made every addition a reason to raise the ceiling.
 */
object TaskSeriesShape {

    /**
     * Whether a row carries a repeat rule without belonging to a series.
     *
     * This is the forbidden shape (FR-001): the rule's dates *are* the series'
     * occurrences, so a rule on a row that stands alone promises a repeat
     * nothing materialises. The predicate is the one statement of the rule: the
     * repository's refusal and the sync writer's decision both read it.
     */
    fun ruleLivesOutsideSeries(task: Task): Boolean = task.recurrence != null && task.seriesId == null

    /**
     * Refuses a row that carries a repeat rule without belonging to a series.
     *
     * The two UI doors uphold the rule by materialising the series, but they are
     * not the only writers — an AI tool, the MCP server and a sync pull all reach
     * the repository directly. So the refusal lives where every writer passes. A
     * writer that means to set a rule must mint the series id and materialise the
     * window
     * ([com.singularity.todo.feature.tasks.domain.usecase.MaterializeRecurringSeriesUseCase]);
     * a writer that forgot fails loudly instead of storing the forbidden shape,
     * which the launch repair would otherwise have to adopt.
     */
    fun requireRuleLivesInSeries(task: Task) {
        require(!ruleLivesOutsideSeries(task)) {
            "A repeating rule must belong to a series: task ${task.id.value} carries a rule but no series id"
        }
    }

    /**
     * Whether a batch of rows is one series.
     *
     * A batch that carries no series id at all is not a series — it is a batch of
     * tasks, and the caller is free to write it. A batch that carries two
     * different ids claims to be two series in one write, which is the mistake
     * the write path refuses.
     */
    fun isOneSeries(tasks: List<Task>): Boolean {
        val ids = tasks.mapNotNull { it.seriesId }.distinct()
        return ids.size <= 1
    }

    /**
     * Whether a batch is either one series or a batch of tasks that stand alone.
     *
     * The two are told apart by the id every member carries: a series is a batch
     * whose members all carry the same id, and a batch of tasks that stand alone
     * is a batch whose members carry none. A batch whose members disagree about
     * that — one carries the id, the other does not — is the mistake the write
     * path refuses: it is a series and a task in one write, and no rule can say
     * which of the two stories was meant.
     */
    fun isOneSeriesOrStandalone(tasks: List<Task>): Boolean {
        if (!isOneSeries(tasks)) return false
        val ids = tasks.mapNotNull { it.seriesId }.distinct()
        return ids.isEmpty() || tasks.all { it.seriesId != null }
    }

    /**
     * The members with the shared fields of the chosen source written onto them,
     * when the batch is one series.
     *
     * The source is chosen by a canonical rule rather than by list position, so
     * the same batch in any order resolves to the same member: the one with the
     * latest `updatedAt`, and, when two members share that time, the one with the
     * smallest id. A batch that stands alone keeps what each row says about
     * itself — aligning it to a source would give every task in it that task's
     * title, which is a different work entirely.
     */
    fun alignedIfOneSeries(members: List<Task>): List<Task> {
        if (seriesIdOf(members) == null) return members
        val latest = members.maxOf { it.updatedAt }
        val source = members.filter { it.updatedAt == latest }.minBy { it.id.value }
        return TaskSeriesInvariants.alignedTo(members, source)
    }

    /**
     * The one series id of a batch, if it has one.
     */
    fun seriesIdOf(tasks: List<Task>): TaskSeriesId? = tasks.mapNotNull { it.seriesId }.distinct().singleOrNull()
}
