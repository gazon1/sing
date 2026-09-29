package com.singularity.todo.feature.pomodoro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests the Pomodoro state machine: transitions, cycle counts, and time accounting.
 *
 * The timer itself ([PomodoroTimer]) is an interface implemented per-platform.
 * These tests exercise the pure [PomodoroState] transitions and [nextPhase] logic
 * directly, without coroutines or a test clock.
 *
 * @see PomodoroDomainTest — covers `phaseSecondsOf` and `recomputeRemaining`
 */
class PomodoroStateMachineTest {

    private val config = PomodoroConfig(
        workMinutes = 25,
        shortBreakMinutes = 5,
        longBreakMinutes = 15,
        cyclesBeforeLongBreak = 4,
    )

    // ─── nextPhase — isRunning is always false in the returned state ──────────

    @Test
    fun `nextPhase always returns isRunning false regardless of current isRunning`() {
        val runningWork = PomodoroState(phase = PomodoroPhase.Work, isRunning = true, completedCycles = 0)
        val afterWork = nextPhase(runningWork, config)
        assertFalse(afterWork.isRunning)
    }

    @Test
    fun `nextPhase to ShortBreak has correct duration and increments cycles`() {
        val work = PomodoroState(phase = PomodoroPhase.Work, completedCycles = 0)
        val after = nextPhase(work, config)
        assertEquals(PomodoroPhase.ShortBreak, after.phase)
        assertEquals(5 * 60, after.remainingSeconds)
        assertEquals(1, after.completedCycles)
        assertFalse(after.isRunning)
    }

    // ─── nextPhase — cycle progression through ShortBreaks ───────────────────

    @Test
    fun `after 4th work phase cyclesBeforeLongBreak triggers LongBreak`() {
        // completedCycles=4 means 4 work sessions already done; the 5th work session
        // (cyclesBeforeLongBreak=4) triggers LongBreak and records cycle count as 5.
        val work = PomodoroState(phase = PomodoroPhase.Work, completedCycles = 4)
        val after = nextPhase(work, config)
        assertEquals(PomodoroPhase.LongBreak, after.phase)
        assertEquals(15 * 60, after.remainingSeconds)
        assertEquals(5, after.completedCycles)
    }

    @Test
    fun `after LongBreak cycles reset to 0 and phase becomes Work`() {
        val longBreak = PomodoroState(phase = PomodoroPhase.LongBreak, completedCycles = 4)
        val after = nextPhase(longBreak, config)
        assertEquals(PomodoroPhase.Work, after.phase)
        assertEquals(0, after.completedCycles)
        assertEquals(25 * 60, after.remainingSeconds)
    }

    @Test
    fun `after ShortBreak cycles are preserved and phase becomes Work`() {
        val shortBreak = PomodoroState(phase = PomodoroPhase.ShortBreak, completedCycles = 2)
        val after = nextPhase(shortBreak, config)
        assertEquals(PomodoroPhase.Work, after.phase)
        assertEquals(2, after.completedCycles)
        assertEquals(25 * 60, after.remainingSeconds)
    }

    // ─── Multiple consecutive cycles ─────────────────────────────────────────

    @Test
    fun `full cycle of 4 work phases plus long break resets cycles to 0`() {
        // Simulate: Work → ShortBreak (×3) → Work → LongBreak
        var state = PomodoroState(phase = PomodoroPhase.Work, completedCycles = 0)
        // Cycles 1, 2, 3: work → short break → work
        repeat(3) {
            state = nextPhase(state.copy(phase = PomodoroPhase.Work), config)
            assertEquals(PomodoroPhase.ShortBreak, state.phase)
            state = nextPhase(state.copy(phase = PomodoroPhase.ShortBreak), config)
            assertEquals(PomodoroPhase.Work, state.phase)
        }
        // Cycle 4: work → long break
        state = nextPhase(state, config)
        assertEquals(PomodoroPhase.LongBreak, state.phase)
        assertEquals(4, state.completedCycles)

        // Long break → work: cycles reset
        val afterLongBreak = nextPhase(state, config)
        assertEquals(PomodoroPhase.Work, afterLongBreak.phase)
        assertEquals(0, afterLongBreak.completedCycles)
    }

    // ─── taskId is preserved across phase transitions ───────────────────────

    @Test
    fun `taskId is preserved through nextPhase transitions`() {
        val work = PomodoroState(phase = PomodoroPhase.Work, taskId = "task-123")
        val afterShortBreak = nextPhase(work, config)
        assertEquals("task-123", afterShortBreak.taskId)

        val afterLongBreak = nextPhase(work.copy(completedCycles = 4), config)
        assertEquals("task-123", afterLongBreak.taskId)

        val afterResume = nextPhase(afterShortBreak.copy(phase = PomodoroPhase.ShortBreak), config)
        assertEquals("task-123", afterResume.taskId)
    }

    // ─── recomputeRemaining ─────────────────────────────────────────────────

    @Test
    fun `recomputeRemaining returns remainingSeconds when phaseStartedAtEpochMs is null`() {
        val paused = PomodoroState(phaseStartedAtEpochMs = null, remainingSeconds = 300)
        assertEquals(300, recomputeRemaining(paused, nowEpochMs = 1_000_000_000_000L))
    }

    @Test
    fun `recomputeRemaining computes elapsed seconds correctly`() {
        // Phase started 60 seconds ago → 1500s - 60s = 1440s remaining
        val started = 1_000_000_000_000L - 60_000
        val state = PomodoroState(
            phase = PomodoroPhase.Work,
            remainingSeconds = 1500,
            phaseStartedAtEpochMs = started,
        )
        assertEquals(1440, recomputeRemaining(state, nowEpochMs = 1_000_000_000_000L))
    }

    @Test
    fun `recomputeRemaining clamps to zero`() {
        // Phase started 5000 seconds ago (far longer than 1500s duration)
        val started = 1_000_000_000_000L - 5_000_000
        val state = PomodoroState(
            phase = PomodoroPhase.Work,
            remainingSeconds = 1500,
            phaseStartedAtEpochMs = started,
        )
        assertEquals(0, recomputeRemaining(state, nowEpochMs = 1_000_000_000_000L))
    }

    @Test
    fun `recomputeRemaining returns remainingSeconds when now equals phaseStartedAt`() {
        val epochMs = 1_000_000_000_000L
        val state = PomodoroState(
            phase = PomodoroPhase.Work,
            remainingSeconds = 1500,
            phaseStartedAtEpochMs = epochMs,
        )
        assertEquals(1500, recomputeRemaining(state, nowEpochMs = epochMs))
    }

    // ─── PomodoroState equality ──────────────────────────────────────────────

    @Test
    fun `identical states are equal`() {
        val a = PomodoroState(phase = PomodoroPhase.Work, remainingSeconds = 1500, completedCycles = 0)
        val b = PomodoroState(phase = PomodoroPhase.Work, remainingSeconds = 1500, completedCycles = 0)
        assertEquals(a, b)
    }

    @Test
    fun `different phase produces different state`() {
        val a = PomodoroState(phase = PomodoroPhase.Work, remainingSeconds = 1500)
        val b = PomodoroState(phase = PomodoroPhase.ShortBreak, remainingSeconds = 300)
        assertNotEquals(a, b)
    }
}
