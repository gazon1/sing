package com.singularity.todo.feature.flows.sync

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailDraftState
import com.singularity.todo.test.helpers.TwoInstanceHarness
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

/**
 * Covers `SYNC-INCOMING-01` — see
 * `scripts/src/traceability/scenarios/sync/incoming/SYNC-INCOMING-01.yaml`.
 *
 * The scenario is defined by two devices: one types a title, the other changes
 * the task underneath it. This runs that on two desktop instances against one
 * shared server — two Koin graphs, two databases, one transport — and asserts
 * the outcome the spec names: the text being typed is not overwritten by the
 * incoming change, and the due date still updates.
 *
 * ## What the draft stands for
 *
 * The screen's in-progress edit lives in [TaskDetailDraftState]: the task-detail
 * screen seeds it from the row on every task change and writes the user's
 * keystrokes into it. This test drives that state directly — `seed` and
 * `setTitle` are the two calls the screen makes — because the instance is a
 * database and a graph, not a mounted UI. The two calls are the whole contract
 * between the row and the field, and the assertion is about what they do to
 * each other.
 *
 * ## The order of the round trips
 *
 * A creates the task and pushes it; B pulls it, so B holds the same task; B
 * changes the due date and pushes; A pulls, and the due date lands. Then B
 * changes the title itself — the same field A is typing into — and A pulls
 * again: the saved row takes the remote title, and the typing is still there.
 */
@Tag("slow")
class SyncIncoming01ScenarioTest {
    @Test
    @DisplayName("SYNC-INCOMING-01 Text typed on one device is not overwritten by a change from the other")
    fun text_typed_on_one_device_is_not_overwritten_by_a_change_from_the_other() =
        runTest {
            TwoInstanceHarness.start().use { harness ->
                val deviceA = harness.instanceA
                val deviceB = harness.instanceB

                // Two devices of one account. The session is where the sync scope
                // comes from, so a device that is not signed in has no scope to
                // sync in and the round trip below would quietly do nothing.
                deviceA.koin.get<AuthRepository>().signIn(EMAIL, PASSWORD)
                deviceB.koin.get<AuthRepository>().signIn(EMAIL, PASSWORD)

                val tasksA = deviceA.koin.get<TaskRepository>()
                val tasksB = deviceB.koin.get<TaskRepository>()

                // A creates the task and pushes it to the shared server.
                val taskId = TaskId(TASK_ID)
                tasksA.create(
                    Task(
                        id = taskId,
                        title = ORIGINAL_TITLE,
                        description = null,
                        userId = tasksA.currentUserId(),
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now(),
                    ),
                ).getOrThrow()
                deviceA.koin.get<SyncRepository>().syncOnce()

                // B pulls it, so the two devices hold the same task.
                deviceB.koin.get<SyncRepository>().syncOnce()
                val onB =
                    assertNotNull(
                        tasksB.get(taskId),
                        "the task created on A must reach B through the shared server",
                    )

                // A opens the task and types a title into it. Nothing is saved
                // yet — that is the whole point of the scenario.
                val draft = TaskDetailDraftState()
                draft.seed(ORIGINAL_TITLE, "")
                draft.setTitle(TYPED_TITLE)

                // B changes the due date and pushes the change.
                val dueOnB = LocalDate(2026, 11, 5)
                tasksB.update(onB.copy(dueDate = dueOnB)).getOrThrow()
                deviceB.koin.get<SyncRepository>().syncOnce()

                // A pulls the change.
                deviceA.koin.get<SyncRepository>().syncOnce()
                val onA =
                    assertNotNull(
                        tasksA.get(taskId),
                        "the task must still be on A after the pull",
                    )
                assertEquals(
                    dueOnB,
                    onA.dueDate,
                    "the due date changed on B must reach A",
                )

                // The screen seeds its draft from the row on every task change.
                // The text being typed has to survive that seed.
                draft.seed(onA.title, onA.description ?: "")
                assertEquals(
                    TYPED_TITLE,
                    draft.current.title.value,
                    "the text being typed on A must not be overwritten by the incoming change",
                )

                // A change to the field being typed into has a defined outcome:
                // the saved row takes the remote title, and the in-progress
                // typing is not discarded.
                val titleOnB = "Milk and eggs, renamed on B"
                tasksB.update(onB.copy(dueDate = dueOnB, title = titleOnB)).getOrThrow()
                deviceB.koin.get<SyncRepository>().syncOnce()
                deviceA.koin.get<SyncRepository>().syncOnce()
                val afterTitle =
                    assertNotNull(
                        tasksA.get(taskId),
                        "the task must still be on A after the second pull",
                    )
                assertEquals(
                    titleOnB,
                    afterTitle.title,
                    "the remote change to the same field must reach A's saved task",
                )
                draft.seed(afterTitle.title, afterTitle.description ?: "")
                assertEquals(
                    TYPED_TITLE,
                    draft.current.title.value,
                    "a remote change to the field being typed into must not discard the typing",
                )
            }
        }

    private companion object {
        const val TASK_ID = "sync-incoming-01-task"
        const val ORIGINAL_TITLE = "Buy milk"
        const val TYPED_TITLE = "Buy milk and eggs"
        const val EMAIL = "sync-incoming-01@example.com"
        const val PASSWORD = "not-a-real-password"
    }
}
