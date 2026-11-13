package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The properties the user revealed through the `+ Ещё` sheet survive a
 * recreation of the composition, and belong to one task.
 *
 * ## What this pins
 *
 * The screen keeps the revealed sections in `rememberSaveable`, so a rotation
 * does not take away the section the user just asked for. The unit tests cover
 * the saver — the names in, the names out — but a saver that is never asked to
 * save anything proves nothing about the screen: the value can be restored
 * and still never reach the user.
 *
 * So this drives the real holder against a real saved-state registry and
 * rebuilds the composition the way a rotation does: the composition is thrown
 * away, and a new one is built from what was saved.
 *
 * ## Why the registry is the test's own
 *
 * `StateRestorationTester` cannot do this on the desktop: its save and restore
 * step is a `TODO` on this platform, and the desktop test framework provides no
 * saved-state registry of its own — so `rememberSaveable` there is `remember`
 * with extra steps. The registry is a public API of the runtime, and a test
 * that owns one gets the real thing: the same `consumeRestored` the screen
 * relies on, and the same `performSave` a rotation performs.
 *
 * @see rememberSavedOptionalProperties
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskOptionalPropertyRestorationTest {
    @Test
    fun `the revealed properties come back when the composition is rebuilt`() =
        runIsolatedComposeTest {
            var registry by
                mutableStateOf(SaveableStateRegistry(restoredValues = null, canBeSaved = { true }))
            var mounted by mutableStateOf(true)
            var held: MutableState<Set<TaskOptionalProperty>>? = null

            setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    if (mounted) {
                        HeldProperties { held = it }
                    }
                }
            }
            waitForIdle()

            // The user picks the logbook in the `+ Ещё` sheet.
            held!!.value = held!!.value + TaskOptionalProperty.Logbook
            waitForIdle()

            // What a rotation hands to the next composition.
            val saved = registry.performSave()
            assertTrue(saved.isNotEmpty(), "the revealed properties were not saved")

            // The composition is rebuilt from nothing, the way a rotation rebuilds
            // it: the old one is gone before the new one is built, so the holder
            // is created again and has to ask the registry for its value.
            mounted = false
            waitForIdle()
            registry = SaveableStateRegistry(restoredValues = saved, canBeSaved = { true })
            mounted = true
            waitForIdle()

            assertEquals(
                setOf(TaskOptionalProperty.Logbook),
                held?.value,
                "the revealed property did not come back with the rebuilt composition",
            )
        }

    @Test
    fun `the revealed properties belong to the task that revealed them`() =
        runIsolatedComposeTest {
            var taskId by mutableStateOf(TaskId("task-a"))
            var held: MutableState<Set<TaskOptionalProperty>>? = null

            setContent {
                HeldProperties(taskId = taskId) { held = it }
            }
            waitForIdle()

            held!!.value = held!!.value + TaskOptionalProperty.Logbook
            waitForIdle()

            // Another task on the same screen starts from nothing: what one task's
            // user revealed is not another task's business.
            taskId = TaskId("task-b")
            waitForIdle()

            assertTrue(
                held?.value.isNullOrEmpty(),
                "the revealed property of one task showed up on another: ${held?.value}",
            )
        }

    /**
     * The screen's holder, with the state it holds handed to the test.
     *
     * The callback runs after every successful composition, so the test reads
     * the state the screen currently holds — the same object, not a copy.
     */
    @Composable
    private fun HeldProperties(
        taskId: TaskId = TaskId("task-a"),
        onHeld: (MutableState<Set<TaskOptionalProperty>>) -> Unit,
    ) {
        val held = rememberSavedOptionalProperties(taskId)
        SideEffect { onHeld(held) }
    }
}
