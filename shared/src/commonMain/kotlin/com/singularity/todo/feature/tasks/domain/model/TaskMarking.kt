package com.singularity.todo.feature.tasks.domain.model

/**
 * What a task is marked with.
 *
 * A marking is a fact shown on a task row. It is either a fact about the task
 * itself — it has no date, it repeats, the user marked it as the one they will
 * do next — or a fact about the task *and its neighbours* — it waits for
 * something, it follows up on something.
 *
 * ## A set, not a value
 *
 * The repository used to answer "what is this task marked with?" with a single
 * value, chosen by a fixed priority: the first matching rule won and the rest
 * were dropped. A task that was both blocked and overdue showed only one of
 * the two, and the other was lost — the priority table did not rank the facts,
 * it hid them.
 *
 * So the answer is a **set**. A task carries every marking that applies to it,
 * and the row shows all of them at once. Nothing is ranked out of existence.
 *
 * ## Where the vocabulary lives
 *
 * Here, in the tasks feature, and not in the agenda. The agenda is one of the
 * surfaces that shows a task, and the task list is another; the vocabulary
 * cannot belong to one of them, because the other would have to depend on it.
 *
 * @see com.singularity.todo.feature.tasks.domain.logic.TaskMarkingPolicy
 */
enum class TaskMarking {
    /**
     * A prerequisite that blocks this task is not finished.
     *
     * Derived from the blocking graph, so it clears the moment the prerequisite
     * is finished.
     */
    Blocked,

    /**
     * This task follows up on another task.
     *
     * Not a blocker: a task that follows up on another is not waiting for it.
     * The two conditions are mutually exclusive, because only the blocking
     * graph makes a task wait.
     */
    FollowUp,

    /** The stored workflow state is "the one I will do next". */
    NextUp,

    /** The stored workflow state is "the one I am doing now". */
    InProgress,

    /** The due date is before today and the task is not finished. */
    Overdue,

    /** The task has no due date. */
    NoDate,

    /** The task is pinned. */
    Pinned,

    /** The task repeats. */
    Recurring,

    /** The task is finished. */
    Completed,
}
