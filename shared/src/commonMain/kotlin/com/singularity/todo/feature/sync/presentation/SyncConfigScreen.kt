package com.singularity.todo.feature.sync.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.sync.ConnectionTestResult
import com.singularity.todo.core.sync.SyncEngineStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SNAP_VALUES = listOf(15, 30, 60, 120)

/**
 * Sync configuration screen.
 *
 * Displays:
 * - Current sync status (Idle / Syncing / NoConnection / Error)
 * - Auto-sync toggle
 * - Sync interval slider (15 / 30 / 60 / 120 minutes)
 * - Last successful sync timestamp
 * - Manual "Sync now" button + "Test connection" button
 *
 * Error messages from [SyncState.errorMessage] are surfaced via snackbar.
 * Callers host the [SnackbarHost] — this composable does not manage its own scaffold.
 */
@Composable
fun SyncConfigScreen(
    viewModel: SyncViewModel,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val state by viewModel.state.collectAsState()
    val isIdle = state.status is SyncEngineStatus.Idle || state.status is SyncEngineStatus.NoConnection

    // Collect errorMessage as a one-shot snackbar.
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.process(SyncIntent.AcknowledgeError)
        }
    }

    // Collect connection test result as a one-shot snackbar.
    LaunchedEffect(state.connectionTestResult) {
        val result = state.connectionTestResult ?: return@LaunchedEffect
        val message = when (result) {
            is ConnectionTestResult.Success -> "Connection OK"
            is ConnectionTestResult.Failure -> "Connection failed: ${result.error.message ?: "Unknown error"}"
        }
        snackbarHostState.showSnackbar(message)
        viewModel.process(SyncIntent.AcknowledgeError)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ─── Status ────────────────────────────────────────────────────────

        Text(
            text = when (val s = state.status) {
                is SyncEngineStatus.Idle -> "Synced"
                is SyncEngineStatus.Pushing -> "Syncing…"
                is SyncEngineStatus.Pulling -> "Syncing…"
                is SyncEngineStatus.NoConnection -> "Offline"
                is SyncEngineStatus.Failure -> "Error: ${s.error.message ?: "Unknown error"}"
            },
            style = MaterialTheme.typography.titleMedium,
            color = when (state.status) {
                is SyncEngineStatus.Failure -> MaterialTheme.colorScheme.error
                is SyncEngineStatus.NoConnection -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.onSurface
            },
        )

        if (state.lastSyncedAt != null) {
            Text(
                text = "Last synced: ${formatTimestamp(state.lastSyncedAt!!)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        HorizontalDivider()

        // ─── Auto-sync toggle ───────────────────────────────────────────────

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-sync", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Automatically sync changes in the background",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Switch(
                checked = state.autoSyncEnabled,
                onCheckedChange = { viewModel.process(SyncIntent.SetAutoSync(it)) },
            )
        }

        // ─── Interval slider ───────────────────────────────────────────────

        if (state.autoSyncEnabled) {
            var sliderValue by remember(state.intervalMinutes) {
                mutableStateOf(state.intervalMinutes.toFloat())
            }

            Column {
                Text(
                    text = "Sync interval: ${sliderValue.toInt()} minutes",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        // Explicit snap to nearest discrete value.
                        val snapped = SNAP_VALUES.minByOrNull { kotlin.math.abs(it - sliderValue.toInt()) } ?: 30
                        viewModel.process(SyncIntent.SetInterval(snapped))
                    },
                    valueRange = 15f..120f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SNAP_VALUES.forEach { label ->
                        Text("$label min", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ─── Manual sync + Test connection buttons ─────────────────────────

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { viewModel.process(SyncIntent.SyncNow) },
                enabled = isIdle && !state.isLoading,
                modifier = Modifier.weight(1f),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Sync now")
            }

            OutlinedButton(
                onClick = { viewModel.process(SyncIntent.TestConnection) },
                enabled = !state.isTestingConnection,
                modifier = Modifier.weight(1f),
            ) {
                if (state.isTestingConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Test connection")
            }
        }

        // ─── Error banner (backup for callers not using snackbar) ──────────

        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = { viewModel.process(SyncIntent.AcknowledgeError) }) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun formatTimestamp(ts: Long): String {
    val sdf = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    return sdf.format(Date(ts))
}
