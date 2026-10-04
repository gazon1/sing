package com.singularity.todo.feature.pomodoro

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
class PomodoroDomainTest {

    private val config = PomodoroConfig(
        workMinutes = 25,
        shortBreakMinutes = 5,
        longBreakMinutes = 15,
        cyclesBeforeLongBreak = 4,
    )

    // ─── PomodoroConfig.phaseSecondsOf ────────────────────────────────────

    @Test
    fun `phaseSecondsOf returns correct seconds for each phase`() {
        assertEquals(25 * 60, config.phaseSecondsOf(PomodoroPhase.Work))
        assertEquals(5 * 60, config.phaseSecondsOf(PomodoroPhase.ShortBreak))
        assertEquals(15 * 60, config.phaseSecondsOf(PomodoroPhase.LongBreak))
    }

    // ─── nextPhase ────────────────────────────────────────────────────────

    @Test
    fun `nextPhase after Work cycles through ShortBreak until LongBreak`() {
        val workState = PomodoroState(phase = PomodoroPhase.Work, completedCycles = 0)
        val after1 = nextPhase(workState, config)
        assertEquals(PomodoroPhase.ShortBreak, after1.phase)
        assertEquals(5 * 60, after1.remainingSeconds)
        assertEquals(5 * 60, after1.phaseDurationSeconds)

        val afterShortBreak = after1.copy(isRunning = false)
        val after2 = nextPhase(afterShortBreak, config)
        assertEquals(PomodoroPhase.Work, after2.phase)
        assertEquals(25 * 60, after2.phaseDurationSeconds)
        assertEquals(1, after2.completedCycles)
    }

    @Test
    fun `nextPhase after Work cycles-before-long-break triggers LongBreak`() {
        val workState = PomodoroState(phase = PomodoroPhase.Work, completedCycles = 3)
        val after = nextPhase(workState, config)
        assertEquals(PomodoroPhase.LongBreak, after.phase)
        assertEquals(15 * 60, after.remainingSeconds)
        assertEquals(15 * 60, after.phaseDurationSeconds)
        assertEquals(4, after.completedCycles)
    }

    @Test
    fun `nextPhase after ShortBreak returns Work, preserves cycle count`() {
        val shortBreak = PomodoroState(phase = PomodoroPhase.ShortBreak, completedCycles = 1)
        val after = nextPhase(shortBreak, config)
        assertEquals(PomodoroPhase.Work, after.phase)
        assertEquals(25 * 60, after.remainingSeconds)
        assertEquals(25 * 60, after.phaseDurationSeconds)
        assertEquals(1, after.completedCycles)
    }

    @Test
    fun `nextPhase after LongBreak returns Work, resets cycle count to 0`() {
        val longBreak = PomodoroState(phase = PomodoroPhase.LongBreak, completedCycles = 4)
        val after = nextPhase(longBreak, config)
        assertEquals(PomodoroPhase.Work, after.phase)
        assertEquals(0, after.completedCycles)
        assertEquals(25 * 60, after.phaseDurationSeconds)
    }

    // ─── recomputeRemaining ────────────────────────────────────────────────

    @Test
    fun `recomputeRemaining returns state remainingSeconds when phaseStartedAtEpochMs is null`() {
        val paused = PomodoroState(phaseStartedAtEpochMs = null, remainingSeconds = 300)
        assertEquals(300, recomputeRemaining(paused, nowEpochMs = 1_000_000_000_000L))
    }

    @Test
    fun `recomputeRemaining computes elapsed time correctly`() {
        // Phase started 60 seconds ago, original duration 1500s → 1440s remaining
        val started = System.currentTimeMillis() - 60_000
        val state = PomodoroState(
            phase = PomodoroPhase.Work,
            remainingSeconds = 1500,
            phaseStartedAtEpochMs = started,
        )
        val remaining = recomputeRemaining(state, nowEpochMs = System.currentTimeMillis())
        assertEquals(1440, remaining)
    }

    @Test
    fun `recomputeRemaining clamps to zero`() {
        val started = System.currentTimeMillis() - 5_000_000L // 5000s ago
        val state = PomodoroState(
            phase = PomodoroPhase.Work,
            remainingSeconds = 1500,
            phaseStartedAtEpochMs = started,
        )
        assertEquals(0, recomputeRemaining(state, nowEpochMs = System.currentTimeMillis()))
    }
}
