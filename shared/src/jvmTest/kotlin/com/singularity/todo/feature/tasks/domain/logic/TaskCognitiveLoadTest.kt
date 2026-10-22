package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The load and the toxicity of a task.
 *
 * These are the two things the read surface says about a task that the screen cannot:
 * how much thinking the task asks for, and whether it weighs on the user more than it
 * matters to them.
 */
@Tag("fast")
class TaskCognitiveLoadTest {

    @Test
    fun `the load grows with the estimate`() {
        val small = TaskCognitiveLoadCalculator.loadOf(estimateMinutes = 30, stepCount = 0)
        val large = TaskCognitiveLoadCalculator.loadOf(estimateMinutes = 300, stepCount = 0)

        assertTrue(large > small, "a five-times bigger estimate is a bigger load")
    }

    @Test
    fun `the load grows with the number of steps`() {
        val oneStep = TaskCognitiveLoadCalculator.loadOf(estimateMinutes = null, stepCount = 1)
        val fourSteps = TaskCognitiveLoadCalculator.loadOf(estimateMinutes = null, stepCount = 4)

        assertTrue(fourSteps > oneStep, "four steps are more thinking than one")
    }

    @Test
    fun `the load does not depend on the priority`() {
        val urgent = TaskCognitiveLoadCalculator.of(
            estimateMinutes = 120,
            stepCount = 2,
            blocked = false,
            staleDays = 0,
            priority = TaskPriority.Urgent,
        )
        val unimportant = TaskCognitiveLoadCalculator.of(
            estimateMinutes = 120,
            stepCount = 2,
            blocked = false,
            staleDays = 0,
            priority = TaskPriority.None,
        )

        assertEquals(urgent.load, unimportant.load, "value is a separate axis")
    }

    @Test
    fun `a task nobody prioritised is not called a burden`() {
        val reading = TaskCognitiveLoadCalculator.of(
            estimateMinutes = 60,
            stepCount = 0,
            blocked = false,
            staleDays = 60,
            priority = TaskPriority.None,
        )

        assertFalse(reading.isToxic, "an old task with no stated value is not a burden")
    }

    @Test
    fun `a blocked task that has sat and matters little is a burden`() {
        val reading = TaskCognitiveLoadCalculator.of(
            estimateMinutes = 120,
            stepCount = 1,
            blocked = true,
            staleDays = 30,
            priority = TaskPriority.Low,
        )

        assertTrue(reading.isToxic, "blocked, stale and of little value")
    }

    @Test
    fun `a heavy task that matters is not a burden`() {
        val reading = TaskCognitiveLoadCalculator.of(
            estimateMinutes = 600,
            stepCount = 4,
            blocked = true,
            staleDays = 30,
            priority = TaskPriority.Urgent,
        )

        assertFalse(reading.isToxic, "big is not the same as burdensome")
    }

    @Test
    fun `the thresholds are the caller's to set`() {
        val lenient = TaskCognitiveLoadThresholds(toxicStress = 1000)
        val strict = TaskCognitiveLoadThresholds(toxicStress = 10)

        val reading = TaskCognitiveLoadCalculator.of(
            estimateMinutes = 60,
            stepCount = 0,
            blocked = false,
            staleDays = 0,
            priority = TaskPriority.Low,
            thresholds = lenient,
        )
        val sameWithStrict = TaskCognitiveLoadCalculator.of(
            estimateMinutes = 60,
            stepCount = 0,
            blocked = false,
            staleDays = 0,
            priority = TaskPriority.Low,
            thresholds = strict,
        )

        assertFalse(reading.isToxic, "the lenient threshold is not reached")
        assertTrue(sameWithStrict.isToxic, "the strict threshold is")
    }

    @Test
    fun `the value follows the priority the user gave`() {
        assertEquals(0, TaskCognitiveLoadCalculator.valueOf(TaskPriority.None))
        assertEquals(1, TaskCognitiveLoadCalculator.valueOf(TaskPriority.Low))
        assertEquals(2, TaskCognitiveLoadCalculator.valueOf(TaskPriority.Medium))
        assertEquals(3, TaskCognitiveLoadCalculator.valueOf(TaskPriority.High))
        assertEquals(4, TaskCognitiveLoadCalculator.valueOf(TaskPriority.Urgent))
    }
}
