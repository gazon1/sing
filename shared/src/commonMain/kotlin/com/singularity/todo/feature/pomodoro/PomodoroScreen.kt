package com.singularity.todo.feature.pomodoro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.refresh.RefreshableContent
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.time.Instant

@Composable
fun PomodoroScreen(timer: PomodoroTimer, taskListProvider: PomodoroTaskListProvider) {
    val state by timer.state.collectAsStateWithLifecycle()
    val tasks by taskListProvider.tasks().collectAsStateWithLifecycle()

    val phaseColor = phaseColorOf(state.phase)
    val config = timer.config
    val totalSeconds = config.phaseSecondsOf(state.phase)
    val progress = state.remainingSeconds.toFloat() / totalSeconds.toFloat()

    RefreshableContent {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                // The screen had no vertical scroll, and the refresh gesture
                // needs something to drag. The scroll goes on the screen's own
                // root, innermost, in the order the rest of the codebase uses:
                // the column fills the screen, and its children are measured
                // with an unbounded height so the content can be taller than
                // the screen and still be reachable.
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TaskChipRow(
                tasks = tasks,
                selectedTaskId = state.taskId,
                isSupported = taskListProvider.isSupported,
                onTaskSelected = { timer.start(it) },
            )

            Spacer(Modifier.height(8.dp))

            val selectedTask = tasks.find { it.id.value == state.taskId }
            if (selectedTask != null) {
                Text(
                    text = "Focus: ${selectedTask.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.Pomodoro.FOCUS_CHIP),
                )
                Spacer(Modifier.height(8.dp))
            }

            PhaseLabel(
                phase = state.phase,
                phaseColor = phaseColor,
            )

            Spacer(Modifier.height(32.dp))

            CircularProgressRing(
                progress = progress,
                phaseColor = phaseColor,
                remainingSeconds = state.remainingSeconds,
                completedCycles = state.completedCycles,
            )

            Spacer(Modifier.height(48.dp))

            PomodoroControls(
                isRunning = state.isRunning,
                phaseColor = phaseColor,
                onStop = { timer.stop() },
                onPlayPause = { if (state.isRunning) timer.pause() else timer.resume() },
                onSkip = { timer.skip() },
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Pomodoro: ${state.completedCycles} completed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ===== Extracted components =====

@Composable
private fun phaseColorOf(phase: PomodoroPhase): Color = when (phase) {
    PomodoroPhase.Work -> MaterialTheme.colorScheme.error
    PomodoroPhase.ShortBreak -> MaterialTheme.colorScheme.primary
    PomodoroPhase.LongBreak -> MaterialTheme.colorScheme.tertiary
}

@Composable
private fun TaskChipRow(
    tasks: ImmutableList<com.singularity.todo.feature.tasks.domain.model.Task>,
    selectedTaskId: String?,
    isSupported: Boolean,
    onTaskSelected: (String) -> Unit,
) {
    if (tasks.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tasks.forEach { task ->
                FilterChip(
                    selected = selectedTaskId == task.id.value,
                    onClick = { onTaskSelected(task.id.value) },
                    modifier = Modifier.testTag(TestTags.pomodoroTaskChip(task.title)),
                    label = {
                        Text(
                            task.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    } else if (!isSupported) {
        SelectableText(
            text = "Focus tasks are not available on desktop",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(TestTags.Pomodoro.TASKS_UNSUPPORTED),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PhaseLabel(phase: PomodoroPhase, phaseColor: Color) {
    Text(
        text = phase.name.replace(Regex("([A-Z])"), " $1").trim(),
        style = MaterialTheme.typography.titleLarge,
        color = phaseColor,
        modifier = Modifier.testTag(TestTags.Pomodoro.PHASE_LABEL),
    )
}

@Composable
private fun CircularProgressRing(
    progress: Float,
    phaseColor: Color,
    remainingSeconds: Int,
    completedCycles: Int,
) {
    Box(
        modifier = Modifier.size(240.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val strokeWidth = 12.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            drawArc(
                color = Color.Gray.copy(alpha = 0.3f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = phaseColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            Text(
                text = "%02d:%02d".format(minutes, seconds),
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.testTag(TestTags.Pomodoro.TIMER_LABEL),
            )
            Text(
                text = "Cycle ${completedCycles + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.Pomodoro.CYCLE_LABEL),
            )
        }
    }
}

@Composable
private fun PomodoroControls(
    isRunning: Boolean,
    phaseColor: Color,
    onStop: () -> Unit,
    onPlayPause: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onStop,
            modifier = Modifier.testTag(TestTags.Pomodoro.STOP_BUTTON),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = "Stop")
        }

        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(72.dp)
                .testTag(
                    if (isRunning) {
                        TestTags.Pomodoro.PAUSE_BUTTON
                    } else {
                        TestTags.Pomodoro.PLAY_BUTTON
                    },
                ),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = phaseColor,
            ),
        ) {
            Icon(
                if (isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isRunning) "Pause" else "Resume",
                modifier = Modifier.size(36.dp),
            )
        }

        IconButton(
            onClick = onSkip,
            modifier = Modifier.testTag(TestTags.Pomodoro.SKIP_BUTTON),
        ) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Skip")
        }
    }
}

// ===== Preview =====

@Composable
private fun PomodoroContentPreview(
    pomodoroState: PomodoroState,
    tasks: ImmutableList<com.singularity.todo.feature.tasks.domain.model.Task> = persistentListOf(),
) {
    val phaseColor = phaseColorOf(pomodoroState.phase)
    val defaultConfig = PomodoroConfig()
    val totalSeconds = defaultConfig.phaseSecondsOf(pomodoroState.phase)
    val progress = pomodoroState.remainingSeconds.toFloat() / totalSeconds.toFloat()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TaskChipRow(
            tasks = tasks,
            selectedTaskId = pomodoroState.taskId,
            isSupported = true,
            onTaskSelected = { noopClick },
        )

        Spacer(Modifier.height(8.dp))

        val selectedTask = tasks.find { it.id.value == pomodoroState.taskId }
        if (selectedTask != null) {
            Text(
                text = "Focus: ${selectedTask.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.Pomodoro.FOCUS_CHIP),
            )
            Spacer(Modifier.height(8.dp))
        }

        PhaseLabel(
            phase = pomodoroState.phase,
            phaseColor = phaseColor,
        )

        Spacer(Modifier.height(32.dp))

        CircularProgressRing(
            progress = progress,
            phaseColor = phaseColor,
            remainingSeconds = pomodoroState.remainingSeconds,
            completedCycles = pomodoroState.completedCycles,
        )

        Spacer(Modifier.height(48.dp))

        PomodoroControls(
            isRunning = pomodoroState.isRunning,
            phaseColor = phaseColor,
            onStop = noopClick,
            onPlayPause = noopClick,
            onSkip = noopClick,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Pomodoro: ${pomodoroState.completedCycles} completed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PomodoroScreenWorkPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    PomodoroContentPreview(
        pomodoroState = PomodoroState(
            phase = PomodoroPhase.Work,
            remainingSeconds = 15 * 60, // 15 minutes remaining
            completedCycles = 2,
            isRunning = true,
            taskId = "t1",
        ),
        tasks = persistentListOf(
            com.singularity.todo.feature.tasks.domain.model.Task(
                id = TaskId("t1"),
                title = "Write documentation",
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
                userId = com.singularity.todo.core.ids.UserId.anonymous,
            ),
            com.singularity.todo.feature.tasks.domain.model.Task(
                id = TaskId("t2"),
                title = "Review PRs",
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
                userId = com.singularity.todo.core.ids.UserId.anonymous,
            ),
        ),
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PomodoroScreenBreakPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    PomodoroContentPreview(
        pomodoroState = PomodoroState(
            phase = PomodoroPhase.ShortBreak,
            remainingSeconds = 4 * 60, // 4 minutes remaining
            completedCycles = 1,
            isRunning = false,
        ),
        tasks = persistentListOf(),
    )
}
