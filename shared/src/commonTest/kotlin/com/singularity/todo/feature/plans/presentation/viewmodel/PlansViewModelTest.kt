@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.plans.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import com.singularity.todo.feature.plans.domain.port.PlansSettingsRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderType
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeReminderScheduler
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TEST_TZ
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The plan screen's ViewModel.
 *
 * The screen rests on two claims: that the sections follow the tasks, and that
 * they are rebuilt when the tasks or the granularity change. Both are checked
 * here against the real ViewModel and the real grouper — the fakes stand in for
 * the storage, not for the behaviour.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Tag("fast")
class PlansViewModelTest {

    /** Wednesday 2026-09-16, 10:00 UTC. */
    private val now = Instant.parse("2026-09-16T10:00:00Z")
    private val today = LocalDate(2026, Month.SEPTEMBER, 16)
    private val tomorrow = today.plus(1, DateTimeUnit.DAY)

    @Test
    fun `the sections follow the tasks`() = runTest {
        val repo = FakeTaskRepository(clock = FakeClock(now), timeZone = TEST_TZ)
        repo.seed(task("now", "Due today", today), task("soon", "Due tomorrow", tomorrow))
        val h = newHarness(repo)
        advanceUntilIdle()

        val state = assertIs<PlansUiState.Loaded>(h.vm.stateFlow.value)
        assertEquals(listOf("Today", "Tomorrow"), state.sections.map { it.label })
        h.close()
    }

    @Test
    fun `a task whose date changes moves to another section`() = runTest {
        val repo = FakeTaskRepository(clock = FakeClock(now), timeZone = TEST_TZ)
        repo.seed(task("now", "Due today", today))
        val h = newHarness(repo)
        advanceUntilIdle()

        repo.add(task("now", "Due today", tomorrow))
        advanceUntilIdle()

        val state = assertIs<PlansUiState.Loaded>(h.vm.stateFlow.value)
        assertEquals(listOf("Tomorrow"), state.sections.map { it.label })
        h.close()
    }

    @Test
    fun `a change of granularity re-groups the sections`() = runTest {
        val repo = FakeTaskRepository(clock = FakeClock(now), timeZone = TEST_TZ)
        repo.seed(task("soon", "Due tomorrow", tomorrow))
        val settings = FakePlansSettings()
        val h = newHarness(repo, settings)
        advanceUntilIdle()

        assertEquals(listOf("Tomorrow"), assertIs<PlansUiState.Loaded>(h.vm.stateFlow.value).sections.map { it.label })

        h.vm.onIntent(PlansIntent.SetGranularity(PlansGranularity.Week))
        advanceUntilIdle()

        val state = assertIs<PlansUiState.Loaded>(h.vm.stateFlow.value)
        assertEquals(PlansGranularity.Week, state.granularity)
        assertEquals(listOf("14–20 Sep"), state.sections.map { it.label })
        h.close()
    }

    @Test
    fun `the choice is written to the settings, so it survives the screen`() = runTest {
        val repo = FakeTaskRepository(clock = FakeClock(now), timeZone = TEST_TZ)
        val settings = FakePlansSettings()
        val h = newHarness(repo, settings)
        advanceUntilIdle()

        h.vm.onIntent(PlansIntent.SetGranularity(PlansGranularity.Month))
        advanceUntilIdle()

        assertEquals(PlansGranularity.Month, settings.current)
        h.close()
    }

    @Test
    fun `completing a task takes it off the timeline`() = runTest {
        val repo = FakeTaskRepository(clock = FakeClock(now), timeZone = TEST_TZ)
        repo.seed(task("now", "Due today", today))
        val h = newHarness(repo)
        advanceUntilIdle()

        h.vm.onIntent(PlansIntent.ToggleCompleted(TaskId("now")))
        advanceUntilIdle()

        val state = assertIs<PlansUiState.Loaded>(h.vm.stateFlow.value)
        assertTrue(state.sections.isEmpty())
        h.close()
    }

    @Test
    fun `swiping a task away deletes it and cancels its reminder`() = runTest {
        val repo = FakeTaskRepository(clock = FakeClock(now), timeZone = TEST_TZ)
        repo.seed(task("now", "Due today", today))
        val scheduler = FakeReminderScheduler()
        val reminder = Reminder(
            id = ReminderId("rem-1"),
            taskId = TaskId("now"),
            userId = UserId("test-user"),
            type = ReminderType.Gentle,
            offsetMinutes = 0,
            fireAt = now.toEpochMilliseconds(),
            recurringPattern = null,
        )
        scheduler.schedule(reminder)
        val h = newHarness(repo, scheduler = scheduler)
        advanceUntilIdle()

        h.vm.onIntent(PlansIntent.TaskSwipedAway(TaskId("now")))
        advanceUntilIdle()

        assertTrue(assertIs<PlansUiState.Loaded>(h.vm.stateFlow.value).sections.isEmpty())
        assertEquals(listOf(reminder.id), scheduler.cancelled, "the reminder of a deleted task must be cancelled")
        h.close()
    }

    private fun task(id: String, title: String, due: LocalDate): Task = Task(
        id = TaskId(id),
        title = title,
        dueDate = due,
        kind = TaskKind.Task,
        createdAt = now,
        updatedAt = now,
        userId = UserId("test-user"),
    )

    /** The ViewModel plus the scope it collects on. */
    private class Harness(val vm: PlansViewModel, private val scope: AutoCloseableCoroutineScope) : AutoCloseable {
        override fun close() = scope.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun TestScope.newHarness(
        repo: FakeTaskRepository,
        settings: FakePlansSettings = FakePlansSettings(),
        scheduler: FakeReminderScheduler = FakeReminderScheduler(),
    ): Harness {
        val scope = testScope(this)
        val vm = PlansViewModel(
            deps = PlansDeps(
                taskRepo = repo,
                settings = settings,
                clock = FakeClock(now),
                timeZone = TEST_TZ,
                reminderScheduler = scheduler,
                currentUser = FakeProfileAwareCurrentUser(),
            ),
            scope = scope,
        )
        return Harness(vm, scope)
    }
}

/** The granularity, held where a profile's settings hold it. */
private class FakePlansSettings : PlansSettingsRepository {
    private val state = MutableStateFlow(PlansGranularity.Default)
    override val granularity: Flow<PlansGranularity> = state

    /** What the settings currently hold. */
    val current: PlansGranularity get() = state.value

    override suspend fun setGranularity(granularity: PlansGranularity) {
        state.value = granularity
    }
}
