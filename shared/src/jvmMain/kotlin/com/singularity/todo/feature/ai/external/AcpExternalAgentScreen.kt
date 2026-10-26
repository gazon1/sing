package com.singularity.todo.feature.ai.external

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.BubbleRole
import com.singularity.todo.core.ui.components.ChatInputBar
import com.singularity.todo.core.ui.components.MessageBubble
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.sheet.BottomSheetHost
import com.singularity.todo.core.ui.textfields.rememberBridgedTextFieldState
import com.singularity.todo.feature.ai.acp.AcpAgentProfile
import com.singularity.todo.feature.ai.ui.AgentPlanCard
import com.singularity.todo.feature.ai.ui.AgentToolCallCard
import org.koin.compose.viewmodel.koinViewModel

/**
 * The screen where an external agent is hosted.
 *
 * ## What the reader needs before the first message
 *
 * Which agent, and what it may do. The agent's command is shown next to its name — an
 * external agent is a third-party program the application is about to start, and a
 * person who cannot see what will be launched cannot say yes to it.
 *
 * And the state of its tool access. An agent that cannot reach the user's tasks is
 * still a working agent, and the difference has to be visible *before* the first
 * message — otherwise the reader concludes the agent is broken when it says it cannot
 * find their tasks.
 *
 * ## The permission sheet
 *
 * The agent asks, the reader answers. The sheet lists the choices the agent offered,
 * because the agent's own options are what "allow once" and "never again" mean; a
 * generic yes/no would throw that away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcpExternalAgentScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val vm: AcpExternalAgentViewModel = koinViewModel()
    AcpExternalAgentScreenContent(vm = vm, modifier = modifier, onBack = onBack)
}

/**
 * The screen, with the ViewModel handed in.
 *
 * The house pattern: the public screen is a thin Koin wrapper and the content takes the
 * ViewModel as a parameter, so a preview — or a test — can drive the screen with a
 * ViewModel it constructed itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcpExternalAgentScreenContent(
    vm: AcpExternalAgentViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val state by vm.stateFlow.collectAsStateWithLifecycle()

    val inputState = rememberBridgedTextFieldState(
        source = state.inputSource,
        onTextChanged = { vm.onIntent(AcpExternalAgentViewModel.Intent.InputChanged(it)) },
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(TestTags.ACP_SCREEN),
    ) {
        AgentHeader(
            state = state,
            onSelect = { vm.onIntent(AcpExternalAgentViewModel.Intent.SelectAgent(it)) },
            onConnect = { vm.onIntent(AcpExternalAgentViewModel.Intent.Connect) },
            onDisconnect = { vm.onIntent(AcpExternalAgentViewModel.Intent.Disconnect) },
            onBack = onBack,
        )

        when (val access = state.toolAccess) {
            is AcpToolAccess.Unavailable -> ToolAccessWarning(access.reason)
            AcpToolAccess.Granted, AcpToolAccess.Unknown -> Unit
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(TestTags.ACP_MESSAGES),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(
                    role = if (message.fromUser) BubbleRole.User else BubbleRole.Assistant,
                    content = message.text,
                )
            }
            if (state.activity.plan.isNotEmpty()) {
                item { AgentPlanCard(steps = state.activity.plan) }
            }
            items(state.activity.calls, key = { it.id }) { call ->
                AgentToolCallCard(
                    name = call.name,
                    status = call.status,
                    summary = call.summary,
                )
            }
        }

        state.turnOutcome?.let { outcome ->
            TurnOutcomeLine(outcome)
        }

        HorizontalDivider()

        AgentInputRow(
            state = state,
            inputState = inputState,
            onSend = { vm.onIntent(AcpExternalAgentViewModel.Intent.Send) },
            onCancel = { vm.onIntent(AcpExternalAgentViewModel.Intent.Cancel) },
        )
    }

    state.permission?.let { request ->
        BottomSheetHost(onDismiss = { vm.onIntent(AcpExternalAgentViewModel.Intent.DenyPermission) }) {
            PermissionSheet(
                request = request,
                onAllow = { vm.onIntent(AcpExternalAgentViewModel.Intent.AllowPermission(it)) },
                onDeny = { vm.onIntent(AcpExternalAgentViewModel.Intent.DenyPermission) },
            )
        }
    }

    state.permissionAnswer?.let { answer ->
        PermissionAnswerLine(answer)
    }

    NotificationHost(
        events = vm.events,
        mapper = { it.toNotification() },
        modifier = Modifier.testTag(TestTags.ACP_NOTIFICATION_HOST),
    )
}

private fun AcpExternalAgentViewModel.UiEvent.toNotification(): Notification = when (this) {
    is AcpExternalAgentViewModel.UiEvent.Error -> Notification.Error(message)
}

/**
 * The row the reader types into, and the button that stops a running turn.
 *
 * The stop button is here and not in the header because it belongs to the thing being
 * typed into: a reader who presses it is stopping the turn they are in the middle of.
 */
@Composable
private fun AgentInputRow(
    state: AcpExternalAgentUiState,
    inputState: TextFieldState,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChatInputBar(
            state = inputState,
            onSend = onSend,
            enabled = !state.isRunning && state.connection is AcpConnection.Connected,
            placeholder = "Ask the agent...",
            modifier = Modifier.weight(1f),
        )
        if (state.isRunning) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.testTag(TestTags.ACP_CANCEL_BUTTON),
            ) {
                Text("Stop")
            }
        }
    }
}

/**
 * The agent on offer, its command, and the button that starts the conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentHeader(
    state: AcpExternalAgentUiState,
    onSelect: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag(TestTags.ACP_BACK_BUTTON),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
            Text(
                text = "External agent",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            when (state.connection) {
                is AcpConnection.Connected -> OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.testTag(TestTags.ACP_DISCONNECT_BUTTON),
                ) {
                    Text("Disconnect")
                }

                AcpConnection.Connecting -> CircularProgressIndicator(
                    modifier = Modifier.testTag(TestTags.ACP_CONNECTING),
                )

                AcpConnection.Disconnected, is AcpConnection.Failed -> Button(
                    onClick = onConnect,
                    modifier = Modifier.testTag(TestTags.ACP_CONNECT_BUTTON),
                ) {
                    Text("Connect")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.ACP_AGENT_LIST),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.agents.forEach { agent ->
                FilterChip(
                    selected = agent.id == state.selectedAgentId,
                    onClick = { onSelect(agent.id) },
                    label = { Text(agent.displayName) },
                    modifier = Modifier.testTag(TestTags.acpAgentChip(agent.id)),
                )
            }
        }

        state.selectedAgent?.let { agent ->
            AgentCommand(agent)
        }

        when (val connection = state.connection) {
            is AcpConnection.Failed -> Text(
                text = connection.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(TestTags.ACP_CONNECTION_ERROR),
            )

            is AcpConnection.Connected -> Text(
                text = "Connected to ${connection.agentName} (protocol version ${connection.protocolVersion}); " +
                    "tool server build ${connection.toolServerBuild}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.ACP_CONNECTION_STATUS),
            )

            else -> Unit
        }
    }
}

/**
 * The command that will be launched.
 *
 * Shown before the connection, not after: an external agent is a third-party program,
 * and the person who has to decide whether to run it is the one who has to see it.
 */
@Composable
private fun AgentCommand(agent: AcpAgentProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = agent.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = agent.commandLine(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.testTag(TestTags.ACP_AGENT_COMMAND),
        )
    }
}

/**
 * The warning that the agent cannot see the user's tasks.
 *
 * It stays on screen for the whole conversation: the agent is blind the whole time,
 * and a warning that disappears after the first message would leave the reader
 * wondering why the agent cannot find anything.
 */
@Composable
private fun ToolAccessWarning(reason: String) {
    Text(
        text = "This agent cannot see your tasks: $reason",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag(TestTags.ACP_NO_TOOL_ACCESS_WARNING),
    )
}

/**
 * How the turn ended.
 */
@Composable
private fun TurnOutcomeLine(outcome: com.singularity.todo.core.llm.AgentTurnOutcome) {
    when (outcome) {
        is com.singularity.todo.core.llm.AgentTurnOutcome.Failed -> Text(
            text = outcome.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag(TestTags.ACP_TURN_FAILED),
        )

        com.singularity.todo.core.llm.AgentTurnOutcome.Cancelled -> Text(
            text = "Cancelled",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag(TestTags.ACP_TURN_CANCELLED),
        )

        is com.singularity.todo.core.llm.AgentTurnOutcome.Completed -> Unit
    }
}

/**
 * What the reader decided about the last permission request.
 *
 * The sheet closes when the answer is given, and without this the decision leaves no trace
 * at all: the reader allowed a tool once, and the screen afterwards shows nothing about
 * it. The question is named next to the answer, because "allowed" only means something
 * next to what was allowed.
 */
@Composable
private fun PermissionAnswerLine(answer: AcpPermissionAnswer) {
    val decided: String = when (answer.decision) {
        is com.singularity.todo.feature.ai.acp.PermissionDecision.Selected -> "Allowed"
        com.singularity.todo.feature.ai.acp.PermissionDecision.Declined -> "Declined"
    }
    Text(
        text = "$decided: ${answer.request.title}",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * The permission request, with the choices the agent offered.
 */
@Composable
private fun PermissionSheet(
    request: com.singularity.todo.feature.ai.acp.AcpPermissionRequest,
    onAllow: (String) -> Unit,
    onDeny: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .testTag(TestTags.ACP_PERMISSION_SHEET),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "The agent asks for permission", style = MaterialTheme.typography.titleMedium)
        Text(
            text = request.title.ifBlank { "A tool call" },
            style = MaterialTheme.typography.bodyMedium,
        )
        request.options.filterNot { it.isRejection }.forEach { option ->
            Button(
                onClick = { onAllow(option.optionId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.ACP_PERMISSION_ALLOW),
            ) {
                Text(option.label)
            }
        }
        OutlinedButton(
            onClick = onDeny,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.ACP_PERMISSION_DENY),
        ) {
            Text("Deny")
        }
    }
}
