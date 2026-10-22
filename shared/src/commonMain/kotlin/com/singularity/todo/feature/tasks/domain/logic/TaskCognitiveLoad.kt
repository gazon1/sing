package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.TaskPriority

/**
 * How much thinking a task asks for, how much stress it creates, and whether the two
 * are out of proportion.
 *
 * ## What this is derived from
 *
 * Nothing here is stored. The load is derived from the task's own size — the estimate
 * and the number of steps it is broken into. The stress adds what makes a task hard to
 * leave alone: what it waits for, and how long it has sat untouched. The value is the
 * priority — how much the task matters in the user's own words.
 *
 * That follows the rule [TaskComputed] already states: derived values are computed on
 * read, never stored in Room.
 *
 * ## Why load and value are two axes, not one number
 *
 * A task that takes four hours of thinking and matters enormously is not the same as a
 * task that takes four hours of thinking and matters not at all. Multiplying them into
 * one score would hide exactly the distinction the user needs: the first is a big task,
 * the second is a burden. So the two are reported separately and the judgment is made by
 * a rule on top of them.
 *
 * ## Why a rule and not a scale
 *
 * [TaskHealth] classifies a task into buckets rather than giving it a score, for the
 * same reason: a number needs a weighting nobody has agreed on, and it hides the reason.
 * So toxicity is a question — "is the stress out of proportion to the value?" — and
 * the answer is yes or no, with the two measurements that produced it alongside.
 *
 * ## Why priority `None` is never toxic on its own
 *
 * `None` means "the user did not say this matters", not "this matters not at all". A
 * forgotten inbox is full of tasks nobody ever prioritised, and calling all of them
 * toxic would make the word mean nothing. So a task with no stated value is left out of
 * the verdict — it can still be reported as heavy, but it is not called a burden.
 *
 * ## The thresholds are parameters
 *
 * Every number this class uses is an argument, not a constant — the same reason
 * [TaskHealth.DEFAULT_STAGNATION_WINDOW] is one. The thresholds are a judgement about
 * people, and a judgement belongs to the caller that knows the user.
 */
data class TaskCognitiveLoad(
    /** How much thinking the task asks for: its size plus the depth of its steps. */
    val load: Int,
    /** How much the task weighs on the user: the load, plus what blocks it, plus its age. */
    val stress: Int,
    /** How much the task matters, from the priority the user gave it. */
    val value: Int,
    /** Whether the stress is out of proportion to the value. */
    val isToxic: Boolean,
)

/**
 * The numbers this class weighs with.
 *
 * The defaults are a starting point, not a verdict: a caller that knows its user
 * passes its own.
 */
data class TaskCognitiveLoadThresholds(
    /** What one step of a task is worth in minutes of thinking. */
    val minutesPerStep: Int = 30,
    /** What waiting on something else adds to the stress. */
    val blockedStress: Int = 50,
    /** What one day of sitting untouched adds to the stress. */
    val stressPerStaleDay: Int = 1,
    /** The stress at which a task weighs on the user. */
    val toxicStress: Int = 100,
    /** The value at or below which a heavy task counts as a burden. */
    val toxicValue: Int = 1,
)

/**
 * The arithmetic behind [TaskCognitiveLoad] — pure, and with no opinion of its own
 * beyond the thresholds it is given.
 */
object TaskCognitiveLoadCalculator {

    /**
     * How much thinking the task asks for.
     *
     * The size of the task and the depth of its steps are the same kind of thing — both
     * are "how much is in here" — so they add up. The priority does not: value is a
     * separate axis, and mixing it in here would make a task feel heavier for mattering
     * more, which is the opposite of what a burden is.
     *
     * @param estimateMinutes the user's own estimate of the work, if there is one.
     * @param stepCount how many steps the task is broken into.
     */
    fun loadOf(
        estimateMinutes: Int?,
        stepCount: Int,
        thresholds: TaskCognitiveLoadThresholds = TaskCognitiveLoadThresholds(),
    ): Int = (estimateMinutes ?: 0) + stepCount * thresholds.minutesPerStep

    /**
     * How much the task matters, from the priority the user gave it.
     *
     * `None` is zero — not "the least", but "not stated" — and the rule below keeps it
     * out of the verdict.
     */
    fun valueOf(priority: TaskPriority): Int = when (priority) {
        TaskPriority.None -> 0
        TaskPriority.Low -> 1
        TaskPriority.Medium -> 2
        TaskPriority.High -> 3
        TaskPriority.Urgent -> 4
    }

    /**
     * How much the task weighs on the user.
     *
     * @param blocked whether the task waits on something unfinished.
     * @param staleDays how many days the task has sat without movement.
     */
    fun stressOf(
        load: Int,
        blocked: Boolean,
        staleDays: Long,
        thresholds: TaskCognitiveLoadThresholds = TaskCognitiveLoadThresholds(),
    ): Int {
        val blockedPart = if (blocked) thresholds.blockedStress else 0
        val agePart = (staleDays * thresholds.stressPerStaleDay).toInt()
        return load + blockedPart + agePart
    }

    /**
     * Whether the stress is out of proportion to the value.
     *
     * The rule is two-sided on purpose. A heavy task that matters is a big task, not a
     * burden. A light task that matters not at all is not a burden either — there is
     * nothing to be weighed down by. A task only becomes one when it is heavy
     * *and* the user did not say it matters.
     */
    fun isToxic(
        stress: Int,
        value: Int,
        thresholds: TaskCognitiveLoadThresholds = TaskCognitiveLoadThresholds(),
    ): Boolean = stress >= thresholds.toxicStress && value in 1..thresholds.toxicValue

    /**
     * The whole reading of one task.
     *
     * @param estimateMinutes the user's own estimate of the work, if there is one.
     * @param stepCount how many steps the task is broken into.
     * @param blocked whether the task waits on something unfinished.
     * @param staleDays how many days the task has sat without movement.
     * @param priority the priority the user gave the task.
     */
    fun of(
        estimateMinutes: Int?,
        stepCount: Int,
        blocked: Boolean,
        staleDays: Long,
        priority: TaskPriority,
        thresholds: TaskCognitiveLoadThresholds = TaskCognitiveLoadThresholds(),
    ): TaskCognitiveLoad {
        val load = loadOf(estimateMinutes, stepCount, thresholds)
        val stress = stressOf(load, blocked, staleDays, thresholds)
        val value = valueOf(priority)
        return TaskCognitiveLoad(
            load = load,
            stress = stress,
            value = value,
            isToxic = isToxic(stress, value, thresholds),
        )
    }
}
