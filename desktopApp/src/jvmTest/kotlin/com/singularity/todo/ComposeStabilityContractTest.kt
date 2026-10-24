@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The stability contract, proved by behaviour rather than by configuration.
 *
 * `config/compose/stability_config.conf` tells the Compose compiler that `Task`
 * is stable. This test checks what that claim is *for*: a row showing a task
 * that did not change must not recompose when the parent does.
 *
 * The Room mapper builds a new `Task` on every read, so without the contract
 * every row of every list recomposes whenever its parent does.
 *
 * ## What the compiler does with the parameter
 *
 * The Compose compiler compares a parameter of a stable type by `equals`, and
 * a parameter of an unstable type by identity (`===`). The contract is what
 * moves `Task` from the second case to the first — the compiler cannot see
 * `Task`'s body from this module and would otherwise treat it as unstable.
 *
 * ## The shape of the test, and why it has two levels
 *
 * The value goes through a **parent** that holds it as a parameter, and the
 * parent calls the **row** that shows it. That is the shape the app has: a list
 * row is reached through a composable that holds the value.
 *
 * ## The state is written with a referential policy
 *
 * The default policy of `mutableStateOf` is *structural* equality: writing an
 * equal instance would not notify the composition at all, nothing would
 * recompose, and the counter would stay still whether the contract held or not.
 * A referential policy makes the write always notify, and the question becomes
 * the one that matters: does the row skip?
 *
 * ## The negative control is not optional
 *
 * The second test uses a type the compiler knows to be unstable and shows that
 * an equal-but-new instance there *does* recompose the row. Without it, the
 * first test could pass for the wrong reason — a counter that never moves
 * looks exactly like a row that never recomposes.
 *
 * The control type carries a mutable property, and that is what makes the
 * difference: the compiler reads a mutable property as "this value can change
 * under me" and compares such a parameter by identity. A class holding a raw
 * `List` would *not* work as a control — the compiler classifies that as
 * *runtime* (it cannot decide at compile time), and a parameter of that class
 * is compared by value unless the caller flags an instance change. That was
 * measured, not assumed; see the ADR.
 *
 * @see 2026-10-22-compose-stability-contract
 */
@Tag("fast")
class ComposeStabilityContractTest {

    @Test
    fun `a row showing an equal but new task does not recompose`() = runIsolatedComposeTest {
        val task = Task(
            id = TaskId.fromString("t-1"),
            title = "Ship the release",
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
            userId = UserId.fromString("u-1"),
        )
        val current = mutableStateOf(task, referentialEqualityPolicy())
        val rowRecompositions = mutableStateOf(0)

        setContent {
            ContractParent(task = current.value, counter = rowRecompositions)
        }
        waitForIdle()

        val afterFirstComposition = rowRecompositions.value
        assertTrue(afterFirstComposition > 0, "the row must have composed at least once")

        // A new instance with the same contents — what the Room mapper hands
        // over on every read.
        current.value = task.copy()
        waitForIdle()

        assertEquals(
            afterFirstComposition,
            rowRecompositions.value,
            "a row whose task did not change must not recompose; " +
                "if it did, Task is no longer stable — check " +
                "config/compose/stability_config.conf",
        )
    }

    @Test
    fun `a row showing an equal but new unstable value does recompose`() = runIsolatedComposeTest {
        // The control: the same shape, with a type the compiler knows to be
        // unstable. Its comparison falls back to identity, so an equal-but-new
        // instance must recompose the row.
        val value = UnstableValue(id = "v-1")
        val current = mutableStateOf(value, referentialEqualityPolicy())
        val rowRecompositions = mutableStateOf(0)

        setContent {
            UnstableParent(value = current.value, counter = rowRecompositions)
        }
        waitForIdle()

        val afterFirstComposition = rowRecompositions.value
        assertTrue(afterFirstComposition > 0, "the row must have composed at least once")

        current.value = UnstableValue(id = "v-1")
        waitForIdle()

        assertTrue(
            rowRecompositions.value > afterFirstComposition,
            "an unstable value is compared by identity, so an equal-but-new " +
                "instance must recompose the row — if it did not, this control " +
                "would prove nothing and the first test could pass vacuously",
        )
    }
}

/**
 * The parent: it holds the value and shows it through the row.
 *
 * The parent is the level where the difference shows. Its own parameter is
 * unchanged, so it is skipped — and while it is skipped, the compiler still
 * evaluates the arguments of the call inside it. There the comparison is made
 * the way the contract says it should be.
 */
@Composable
private fun ContractParent(task: Task, counter: MutableState<Int>) {
    ContractRow(task = task, counter = counter)
}

@Composable
private fun ContractRow(task: Task, counter: MutableState<Int>) {
    SideEffect { counter.value++ }
    BasicText(task.title)
}

@Composable
private fun UnstableParent(value: UnstableValue, counter: MutableState<Int>) {
    UnstableRow(value = value, counter = counter)
}

@Composable
private fun UnstableRow(value: UnstableValue, counter: MutableState<Int>) {
    SideEffect { counter.value++ }
    BasicText(value.id)
}

/**
 * A type the stability contract does not name, and the compiler cannot call
 * stable: it holds a mutable property, so the value can change under a
 * composable that compares it by value.
 */
private class UnstableValue(val id: String) {
    var revision: Int = 0
}
