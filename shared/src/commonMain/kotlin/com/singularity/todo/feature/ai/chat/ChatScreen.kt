package com.singularity.todo.feature.ai.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.llm.AgentPlanStep
import com.singularity.todo.core.llm.AgentToolCall
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.core.refresh.RefreshableContent
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.BubbleRole
import com.singularity.todo.core.ui.components.ChatInputBar
import com.singularity.todo.core.ui.components.MessageBubble
import com.singularity.todo.core.ui.textfields.rememberBridgedTextFieldState
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.ai.ui.AgentPlanCard
import com.singularity.todo.feature.ai.ui.AgentToolCallCard
import kotlinx.serialization.json.JsonElement
import com.singularity.todo.feature.genui.render.GenuiSurface
import com.singularity.todo.feature.genui.render.LocalGenuiOnAction
import com.singularity.todo.feature.genui.render.rememberDataContext
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The chat screen.
 *
 * The Koin entry point: it resolves the ViewModel and hands it to [ChatScreenContent].
 */
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val vm: ChatViewModel = koinViewModel()
    ChatScreenContent(vm = vm, modifier = modifier)
}

/**
 * The chat screen, with the ViewModel handed in.
 *
 * The house pattern: the public screen is a thin Koin wrapper and the content takes the
 * ViewModel as a parameter, so a preview — or a test — can drive the screen with a
 * ViewModel it constructed itself, without a Koin context and without a model provider.
 */
@Composable
fun ChatScreenContent(
    vm: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.stateFlow.collectAsStateWithLifecycle()

    // The field owns its text; the ViewModel keeps the last value so the chat can still be
    // restored and the send action can read it. Sending clears the ViewModel's copy, and the
    // bridge pulls that empty value back into the field.
    val inputState = rememberBridgedTextFieldState(
        source = state.inputSource,
        onTextChanged = { vm.onIntent(ChatViewModel.Intent.InputChanged(it)) },
    )

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("AI Assistant") }) },
    ) { padding ->
        RefreshableContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                ChatMessagesList(
                    messages = state.messages,
                    isLoading = state.isLoading,
                    onSurfaceAction = { name: String, context: JsonElement? ->
                        vm.onIntent(ChatViewModel.Intent.SurfaceAction(name, context))
                    },
                    modifier = Modifier.weight(1f),
                )
                AgentActivitySection(
                    plan = state.activity.plan,
                    calls = state.activity.calls,
                    outcome = state.turnOutcome,
                )
                HorizontalDivider()
                ChatInputBar(
                    state = inputState,
                    onSend = { vm.onIntent(ChatViewModel.Intent.Send) },
                    enabled = !state.isLoading,
                    // While a turn runs there is nothing to send, and the reader may want it to stop.
                    onStop = if (state.isLoading) {
                        { vm.onIntent(ChatViewModel.Intent.Stop) }
                    } else {
                        null
                    },
                )
            }
        }
    }

    NotificationHost(
        events = vm.events,
        mapper = { it.toNotification() },
        modifier = Modifier.testTag(TestTags.CHAT_NOTIFICATION_HOST),
    )
}

private fun ChatUiEvent.toNotification(): Notification = when (this) {
    is ChatUiEvent.Error -> Notification.Error(message)
}

/**
 * Draws the surface attached to a message, if one is still there.
 *
 * Resolved through the layer's dependency graph rather than passed down: a screen that received a
 * surface model would be coupled to how the model happens to be served, and a message that
 * outlived its surface — after a profile switch, say — renders nothing rather than failing.
 */
@Composable
private fun GenuiMessageSurface(
    surfaceId: SurfaceId,
    onSurfaceAction: (String, JsonElement?) -> Unit,
) {
    val controller: SurfaceController = koinInject()
    val registry: ComponentRegistry = koinInject()
    val ctx = rememberDataContext(
        surfaceId = surfaceId,
        controller = controller,
        registry = registry,
        // A generated screen's own data model is the source of truth here, so there is
        // nothing to notify. Stated as null rather than `{ _, _, _ -> }`: the parameter
        // means "tell someone", and there is no someone.
        onDataChange = null,
        clock = koinInject(),
    )
    CompositionLocalProvider(
        LocalGenuiOnAction provides { _, name, data -> onSurfaceAction(name, data) },
    ) {
        GenuiSurface(ctx, Modifier.fillMaxWidth().padding(vertical = 4.dp))
    }
}

@Composable
private fun ChatMessagesList(
    messages: ImmutableList<ChatMessage>,
    isLoading: Boolean,
    onSurfaceAction: (String, JsonElement?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { msg ->
            // An answer that is only a screen has no words, and an empty bubble next to a
            // screen reads as a broken answer. The screen is the answer, so it stands alone.
            if (msg.content.isNotBlank()) {
                MessageBubble(
                    role = if (msg.role == ChatRole.User) BubbleRole.User else BubbleRole.Assistant,
                    content = msg.content,
                )
            }
            // A reply may be a sentence and a screen at once. The surface is drawn inside the
            // bubble rather than below it so that the two read as one answer.
            msg.surfaceId?.let { surfaceId: SurfaceId -> GenuiMessageSurface(surfaceId, onSurfaceAction) }
        }
        if (isLoading) item { ThinkingIndicator() }
    }
}

/**
 * What the agent is doing, and how the turn ended.
 *
 * Sits between the conversation and the input bar: it is about *now*, and the reader is
 * looking at the bottom of the screen while the agent works.
 *
 * The cards are the shared ones — the same cards the external-agent screen draws — so that
 * a reader who has learned to read one screen can read the other.
 */
@Composable
private fun AgentActivitySection(
    plan: ImmutableList<AgentPlanStep>,
    calls: ImmutableList<AgentToolCall>,
    outcome: AgentTurnOutcome?,
) {
    if (plan.isEmpty() && calls.isEmpty() && outcome == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (plan.isNotEmpty()) {
            AgentPlanCard(steps = plan)
        }
        calls.forEach { call ->
            AgentToolCallCard(
                name = call.name,
                status = call.status,
                summary = call.summary,
            )
        }
        when (outcome) {
            is AgentTurnOutcome.Failed -> Text(
                text = outcome.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            is AgentTurnOutcome.Cancelled -> Text(
                text = "Cancelled",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Unit
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp))
        Text("Thinking...", style = MaterialTheme.typography.bodySmall)
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChatScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("AI Assistant") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ChatMessagesList(
                messages = persistentListOf(
                    ChatMessage("m1", ChatRole.User, "Hello, what can you help me with?"),
                    ChatMessage(
                        "m2",
                        ChatRole.Assistant,
                        "Hi! I can help you manage your tasks, take notes, and organize your projects.",
                    ),
                    ChatMessage("m3", ChatRole.User, "Can you show me my tasks for today?"),
                    ChatMessage(
                        "m4",
                        ChatRole.Assistant,
                        "You have 3 tasks due today: Buy groceries, Finish project report, and Send follow-up emails.",
                    ),
                ),
                isLoading = false,
                onSurfaceAction = { _, _ -> },
                modifier = Modifier.weight(1f),
            )
            HorizontalDivider()
            ChatInputBar(
                state = rememberTextFieldState(),
                onSend = {},
                enabled = true,
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChatScreenLoadingPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("AI Assistant") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ChatMessagesList(
                messages = persistentListOf(
                    ChatMessage("m1", ChatRole.User, "Can you explain Kotlin coroutines?"),
                    ChatMessage(
                        "m2",
                        ChatRole.Assistant,
                        "Kotlin Coroutines are a way to handle asynchronous programming in a sequential manner. They allow you to write code that looks synchronous but can pause and resume without blocking.",
                    ),
                ),
                isLoading = true,
                onSurfaceAction = { _, _ -> },
                modifier = Modifier.weight(1f),
            )
            HorizontalDivider()
            ChatInputBar(
                state = rememberTextFieldState(),
                onSend = {},
                enabled = false,
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChatMessagesListDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    Column(modifier = Modifier.fillMaxSize()) {
        ChatMessagesList(
            messages = persistentListOf(
                ChatMessage("m1", ChatRole.User, "Draft the release notes"),
                ChatMessage("m2", ChatRole.Assistant, "Here is a first pass at the release notes."),
            ),
            isLoading = false,
            onSurfaceAction = { _, _ -> },
            modifier = Modifier.weight(1f),
        )
    }
}
