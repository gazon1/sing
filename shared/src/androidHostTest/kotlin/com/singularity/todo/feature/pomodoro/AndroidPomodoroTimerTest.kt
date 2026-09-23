package com.singularity.todo.feature.pomodoro

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakePomodoroScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private fun interface FakeTaskListProvider : PomodoroTaskListProvider

private fun fakeTaskListProvider(tasks: List<Task> = emptyList()): FakeTaskListProvider =
    object : FakeTaskListProvider {
        private val _tasks = MutableStateFlow(tasks)
        override fun tasks(): StateFlow<List<Task>> = _tasks
    }

class AndroidPomodoroTimerTest {

    private fun testTimer(
        clock: FakeClock = FakeClock(),
        taskListProvider: FakeTaskListProvider = fakeTaskListProvider(),
        scheduler: FakePomodoroScheduler = FakePomodoroScheduler(),
        config: PomodoroConfig = PomodoroConfig(),
        scope: AutoCloseableCoroutineScope,
    ): AndroidPomodoroTimer = AndroidPomodoroTimer(
        clock = clock,
        taskListProvider = taskListProvider,
        alarmScheduler = scheduler,
        config = config,
        scope = scope,
    )

    // ─── start ───────────────────────────────────────────────────────────────

    @Test
    fun `start — transitions to running state with correct duration`() = runTest {
        val clock = FakeClock()
        val scheduler = FakePomodoroScheduler()
        val timer = testTimer(clock, scheduler = scheduler, scope = testScope(backgroundScope))

        timer.start("t1")

        val state = timer.state.value
        assertTrue(state.isRunning)
        assertEquals("t1", state.taskId)
        assertEquals(PomodoroPhase.Work, state.phase)
        assertEquals(25 * 60, state.remainingSeconds)
        assertEquals(25 * 60, state.phaseDurationSeconds)
        assertNotNull(state.phaseStartedAtEpochMs)
    }

    @Test
    fun `start — schedules OS alarm with correct fireAt`() = runTest {
        val clock = FakeClock()
        val scheduler = FakePomodoroScheduler()
        val timer = testTimer(clock, scheduler = scheduler, scope = testScope(backgroundScope))

        timer.start("t1")

        val expectedFireAt = clock.now().toEpochMilliseconds() + (25 * 60 * 1000L)
        assertEquals(expectedFireAt, scheduler.scheduledFireAt)
        assertEquals(PomodoroPhase.Work, scheduler.scheduledPhase)
    }

    @Test
    fun `start — no-op when already running`() = runTest {
        val clock = FakeClock()
        val scheduler = FakePomodoroScheduler()
        val timer = testTimer(clock, scheduler = scheduler, scope = testScope(backgroundScope))

        timer.start("t1")
        val firstFireAt = scheduler.scheduledFireAt
        timer.start("t2") // should be ignored

        assertEquals(firstFireAt, scheduler.scheduledFireAt)
        assertEquals("t1", timer.state.value.taskId)
    }

    // ─── pause / resume ────────────────────────────────────────────────────

    @Test
    fun `pause — stops ticker and cancels OS alarm`() = runTest {
        val clock = FakeClock()
        val scheduler = FakePomodoroScheduler()
        val timer = testTimer(clock, scheduler = scheduler, scope = testScope(backgroundScope))

        timer.start("t1")
        timer.pause()

        assertFalse(timer.state.value.isRunning)
        assertNull(timer.state.value.phaseStartedAtEpochMs)
        assertTrue(scheduler.wasCanceled)
    }

    @Test
    fun `resume — restarts ticker and reschedules OS alarm`() = runTest {
        val clock = FakeClock()
        val scheduler = FakePomodoroScheduler()
        val timer = testTimer(clock, scheduler = scheduler, scope = testScope(backgroundScope))

        timer.start("t1")
        clock.advance(10.seconds)
        timer.pause()
        timer.resume()

        assertTrue(timer.state.value.isRunning)
        assertNotNull(timer.state.value.phaseStartedAtEpochMs)
        assertNotNull(scheduler.scheduledFireAt)
    }

    // ─── skip ──────────────────────────────────────────────────────────────

    @Test
    fun `skip — transitions to next phase immediately`() = runTest {
        val clock = FakeClock()
        val scheduler = FakePomodoroScheduler()
        val timer = testTimer(clock, scheduler = scheduler, scope = testScope(backgroundScope))

        timer.start("t1")
        timer.skip()

        val state = timer.state.value
        assertFalse(state.isRunning)
        assertEquals(PomodoroPhase.ShortBreak, state.phase)
    }

    @Test
    fun `skip — cancels pending OS alarm`() = runTest {
        val clock = FakeClock()
        val scheduler = FakePomodoroScheduler()
        val timer = testTimer(clock, scheduler = scheduler, scope = testScope(backgroundScope))

        timer.start("t1")
        timer.skip()

        assertTrue(scheduler.wasCanceled)
    }

    // ─── stop ──────────────────────────────────────────────────────────────

    @Test
    fun `stop — resets to initial work state`() = runTest {
        val clock = FakeClock()
        val scheduler = FakePomodoroScheduler()
        val timer = testTimer(clock, scheduler = scheduler, scope = testScope(backgroundScope))

        timer.start("t1")
        timer.stop()

        val state = timer.state.value
        assertFalse(state.isRunning)
        assertNull(state.taskId)
        assertEquals(PomodoroPhase.Work, state.phase)
        assertEquals(25 * 60, state.remainingSeconds)
    }

    // ─── race guard ───────────────────────────────────────────────────────

    @Test
    fun `double skip does not double transition`() = runTest {
        val config = PomodoroConfig(workMinutes = 1)
        val clock = FakeClock()
        val scheduler = FakePomodoroScheduler()
        val timer = testTimer(clock, scheduler = scheduler, config = config, scope = testScope(backgroundScope))

        timer.start("t1")
        timer.skip()
        val cyclesAfterFirstSkip = timer.state.value.completedCycles
        timer.skip() // second skip should not increment cycles again

        assertEquals(cyclesAfterFirstSkip, timer.state.value.completedCycles)
    }
}
