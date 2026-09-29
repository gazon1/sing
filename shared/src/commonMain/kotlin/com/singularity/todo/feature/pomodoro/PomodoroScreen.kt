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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlin.time.Instant

@Composable
fun PomodoroScreen(timer: PomodoroTimer, taskListProvider: PomodoroTaskListProvider) {
    val state by timer.state.collectAsStateWithLifecycle()
    val tasks by taskListProvider.tasks().collectAsStateWithLifecycle()

    val phaseColor = when (state.phase) {
        PomodoroPhase.Work -> MaterialTheme.colorScheme.error
        PomodoroPhase.ShortBreak -> MaterialTheme.colorScheme.primary
        PomodoroPhase.LongBreak -> MaterialTheme.colorScheme.tertiary
    }

    val config = timer.config
    val totalSeconds = config.phaseSecondsOf(state.phase)
    val progress = state.remainingSeconds.toFloat() / totalSeconds.toFloat()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Task selection chips
        if (tasks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tasks.forEach { task ->
                    FilterChip(
                        selected = state.taskId == task.id.value,
                        onClick = { timer.start(task.id.value) },
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
        }

        // Selected task name
        val selectedTask = tasks.find { it.id.value == state.taskId }
        if (selectedTask != null) {
            Text(
                text = "Focus: ${selectedTask.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        Text(
            text = state.phase.name.replace(Regex("([A-Z])"), " $1").trim(),
            style = MaterialTheme.typography.titleLarge,
            color = phaseColor,
            modifier = Modifier.testTag(TestTags.Pomodoro.PHASE_LABEL),
        )

        Spacer(Modifier.height(32.dp))

        // Circular progress
        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(240.dp)) {
                val strokeWidth = 12.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2

                // Background arc
                drawArc(
                    color = Color.Gray.copy(alpha = 0.3f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                // Progress arc
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
                val minutes = state.remainingSeconds / 60
                val seconds = state.remainingSeconds % 60
                Text(
                    text = "%02d:%02d".format(minutes, seconds),
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.testTag(TestTags.Pomodoro.TIMER_LABEL),
                )
                Text(
                    text = "Cycle ${state.completedCycles + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.Pomodoro.CYCLE_LABEL),
                )
            }
        }

        Spacer(Modifier.height(48.dp))

        // Controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { timer.stop() },
                modifier = Modifier.testTag(TestTags.Pomodoro.STOP_BUTTON),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop")
            }

            FilledIconButton(
                onClick = { if (state.isRunning) timer.pause() else timer.resume() },
                modifier = Modifier
                    .size(72.dp)
                    .testTag(
                        if (state.isRunning) TestTags.Pomodoro.PAUSE_BUTTON
                        else TestTags.Pomodoro.PLAY_BUTTON
                    ),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = phaseColor,
                ),
            ) {
                Icon(
                    if (state.isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isRunning) "Pause" else "Resume",
                    modifier = Modifier.size(36.dp),
                )
            }

            IconButton(
                onClick = { timer.skip() },
                modifier = Modifier.testTag(TestTags.Pomodoro.SKIP_BUTTON),
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Skip")
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Pomodoro: ${state.completedCycles} completed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ===== Preview =====

@Composable
private fun PomodoroContentPreview(
    pomodoroState: PomodoroState,
    tasks: List<com.singularity.todo.feature.tasks.domain.model.Task> = emptyList(),
) {
    val phaseColor = when (pomodoroState.phase) {
        PomodoroPhase.Work -> MaterialTheme.colorScheme.error
        PomodoroPhase.ShortBreak -> MaterialTheme.colorScheme.primary
        PomodoroPhase.LongBreak -> MaterialTheme.colorScheme.tertiary
    }

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
        if (tasks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tasks.forEach { task ->
                    FilterChip(
                        selected = pomodoroState.taskId == task.id.value,
                        onClick = { },
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
        }

        val selectedTask = tasks.find { it.id.value == pomodoroState.taskId }
        if (selectedTask != null) {
            Text(
                text = "Focus: ${selectedTask.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        Text(
            text = pomodoroState.phase.name.replace(Regex("([A-Z])"), " $1").trim(),
            style = MaterialTheme.typography.titleLarge,
            color = phaseColor,
        )

        Spacer(Modifier.height(32.dp))

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
                val minutes = pomodoroState.remainingSeconds / 60
                val seconds = pomodoroState.remainingSeconds % 60
                Text(
                    text = "%02d:%02d".format(minutes, seconds),
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "Cycle ${pomodoroState.completedCycles + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(48.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { }) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop")
            }

            FilledIconButton(
                onClick = { },
                modifier = Modifier
                    .size(72.dp)
                    .testTag(
                        if (pomodoroState.isRunning) TestTags.Pomodoro.PAUSE_BUTTON
                        else TestTags.Pomodoro.PLAY_BUTTON
                    ),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = phaseColor,
                ),
            ) {
                Icon(
                    if (pomodoroState.isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (pomodoroState.isRunning) "Pause" else "Resume",
                    modifier = Modifier.size(36.dp),
                )
            }

            IconButton(onClick = { }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Skip")
            }
        }

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
        tasks = listOf(
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
        tasks = emptyList(),
    )
}
