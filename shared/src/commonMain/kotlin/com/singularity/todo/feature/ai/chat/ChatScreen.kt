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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.llm.AgentPlanStep
import com.singularity.todo.core.llm.AgentToolCall
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.core.llm.ToolCallStatus
import com.singularity.todo.core.refresh.RefreshableContent
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.BubbleRole
import com.singularity.todo.core.ui.components.ChatInputBar
import com.singularity.todo.core.ui.components.MessageBubble
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.TopAppBar
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.textfields.rememberBridgedTextFieldState
import com.singularity.todo.feature.ai.ui.AgentPlanCard
import com.singularity.todo.feature.ai.ui.humanToolName
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.GenuiSurface
import com.singularity.todo.feature.genui.render.LocalGenuiOnAction
import com.singularity.todo.feature.genui.render.rememberDataContext
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonElement
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

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
    val inputState =
        rememberBridgedTextFieldState(
            source = state.inputSource,
            onTextChanged = { vm.onIntent(ChatViewModel.Intent.InputChanged(it)) },
        )

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("AI Assistant") }) },
    ) { padding ->
        RefreshableContent {
            Column(
                modifier =
                    Modifier
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
                state.surfaceError?.let { err ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.onIntent(ChatViewModel.Intent.DismissSurfaceError) }) {
                            Text("Dismiss")
                        }
                    }
                }
                HorizontalDivider()
                ChatInputBar(
                    state = inputState,
                    onSend = { vm.onIntent(ChatViewModel.Intent.Send) },
                    enabled = !state.isLoading,
                    // While a turn runs there is nothing to send, and the reader may want it to stop.
                    onStop =
                        if (state.isLoading) {
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

private fun ChatUiEvent.toNotification(): Notification =
    when (this) {
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
    val ctx =
        rememberDataContext(
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
    FollowConversationEnd(listState = listState, messages = messages, isLoading = isLoading)
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
 * Keeps the end of the conversation in view as a reply streams in.
 *
 * The reply arrives one token at a time, so the end of the conversation moves on every token.
 * Three things keep that from being a stream of jumps:
 *
 * - **Only when the end is out of view.** The list is asked to move only when its own
 *   `canScrollForward` says something is below the fold. Content that has grown *inside* the
 *   viewport is already where the reader is looking, and the item the reader follows is the
 *   last message rather than whatever trails it — the thinking indicator is a one-line
 *   decoration, and asking whether *it* is visible says nothing about whether the newest token
 *   is.
 * - **One slide at a time.** The move is an animation, so the effect that runs it must outlive
 *   the token that asked for it. A `LaunchedEffect` keyed on the tail's length is disposed by
 *   the next token, which cancels the animation in flight and starts another from a standstill:
 *   a chase that never lands, and one whose `movedOnce` bookkeeping is only ever reached by
 *   luck, because a cancelled run never gets past the `finally`. The requests go through a
 *   conflated channel into one long-lived mover instead, which serves them one after another
 *   and measures the end of the conversation when it starts a move rather than when the request
 *   was queued. A token that arrives mid-slide therefore retargets the next move rather than
 *   killing this one.
 * - **Only while the reader is at the end.** Somebody who scrolled up to re-read an earlier
 *   answer is left where they are — a new token is not an argument for moving them — and the
 *   end is picked up again when they scroll back down, or when they send a message of their
 *   own, which is a request to see it.
 *
 * The first move is a jump rather than an animation: a conversation restored from the store can
 * be twenty messages long, and animating through all of them is a fairground ride.
 */
@Composable
private fun FollowConversationEnd(
    listState: LazyListState,
    messages: ImmutableList<ChatMessage>,
    isLoading: Boolean,
) {
    // Whether the reader is with the end of the conversation.
    var following by remember(listState) { mutableStateOf(true) }

    // Whether the motion in progress is this follower's rather than the reader's. Every move
    // sets it before the list can report a scroll of its own and clears it only after the move
    // has landed: the list sets and clears `isScrollInProgress` strictly between those two
    // writes, and the two converge — one scroll at a time — so "a scroll is in progress and it
    // is not ours" can only mean the reader, whatever order the two coroutines resume in. It is
    // not the reader's turn to be moved; it is the *list's* answer that decides, which is why
    // this is a registration around a move rather than a second guess at `canScrollForward`.
    // A reader who takes the wheel cancels the move (a drag outranks it) and clears the flag
    // while their own scroll runs, so the observation reads their position — the one that
    // matters.
    var moving by remember(listState) { mutableStateOf(false) }

    // "The end of the conversation moved." Conflated on purpose: two tokens in one frame are one
    // move, and the move that runs reads the newest end for itself.
    val moves = remember(listState) { Channel<Unit>(Channel.CONFLATED) }

    // How many questions the reader has typed, so that a new one can be told from every other
    // change to the conversation.
    var questions by remember(listState) { mutableIntStateOf(0) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (scrolling && !moving) following = !canScrollForward
            }
    }

    // A token, a new message, or the end of a turn: the end of the conversation may have moved.
    val tailLength = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(messages.size, tailLength, isLoading) {
        // A question of the reader's own is a request to see the answer to it — they are not
        // re-reading an older answer, they are waiting for a new one. It cannot be recognised by
        // the tail's role: `ChatViewModel.turn` appends the question *and* an empty message for
        // the answer in one state change, so the tail is already the assistant's placeholder by
        // the time anything here can look at it. The questions are counted instead.
        val asked = messages.count { it.role == ChatRole.User }
        if (asked > questions) following = true
        questions = asked
        if (messages.isNotEmpty()) moves.trySend(Unit)
    }

    // One mover, for the life of the list. Nothing here is keyed on a token, so a slide that is
    // running is never disposed by the next one: the token's request waits in the channel and is
    // served when the slide lands.
    LaunchedEffect(listState) {
        var slid = false
        moves.receiveAsFlow().collect {
            if (!following || !listState.canScrollForward) return@collect
            moving = true
            try {
                listState.slideToContentEnd(animate = slid)
            } finally {
                moving = false
            }
            slid = true
        }
    }
}

/**
 * Puts the end of the content at the bottom of the viewport, sliding or jumping.
 *
 * Not `animateScrollToItem(lastIndex)`, which aligns the last item's *start* with the top of the
 * viewport: for the one item this screen follows — the answer being written, once it is taller
 * than the viewport — that puts the first line on screen and every line after it, including the
 * token that has just arrived, below the fold, with nowhere left to scroll. The end of the
 * content is the position with nothing below the viewport.
 */
@Suppress("NoSwallowedCancellation", "SwallowedException") // the only cancellation swallowed is the reader's
private suspend fun LazyListState.slideToContentEnd(animate: Boolean) {
    try {
        val lastIndex = layoutInfo.totalItemsCount - 1
        if (lastIndex < 0) return
        // The distance to the end is only knowable once the last item has been laid out: an item
        // below the fold has no height to add up. Bring it in by index first — a target the list
        // clamps to the end whenever the item fits — and measure again.
        if (layoutInfo.visibleItemsInfo.lastOrNull()?.index != lastIndex) {
            if (animate) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
        }
        // `viewportEndOffset` is the last pixel the reader can see, so the remainder is how far
        // the end of the content sits below the fold. Erring high is safe — a scroll stops at the
        // end of the content — so the trailing content padding is left in.
        val tail = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
        val remaining = (tail.offset + tail.size - layoutInfo.viewportEndOffset).toFloat()
        if (remaining <= 0f) return
        if (animate) animateScrollBy(remaining) else scrollBy(remaining)
    } catch (preempted: CancellationException) {
        // A reader's drag outranks this move and cancels it. That ends the move, not the
        // follower: the mover is still collecting, and the next token is served as usual. A
        // cancellation of the follower itself — the screen leaving, the list going away — is
        // rethrown by `ensureActive`, so nothing is swallowed but the preemption.
        currentCoroutineContext().ensureActive()
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
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (plan.isNotEmpty()) {
            AgentPlanCard(steps = plan)
        }
        if (calls.isNotEmpty()) {
            val failed = calls.any { it.status == ToolCallStatus.FAILED }
            val last = calls.last()
            val statusText =
                when {
                    failed -> "failed"
                    calls.all { it.status == ToolCallStatus.COMPLETED } -> "done"
                    else -> "running"
                }
            Text(
                text = "${calls.size} call(s) · ${humanToolName(last.name)} · $statusText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (outcome) {
            is AgentTurnOutcome.Failed -> {
                Text(
                    text = outcome.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is AgentTurnOutcome.Cancelled -> {
                Text(
                    text = "Cancelled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Unit
            }
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
private fun ChatScreenLightPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("AI Assistant") }) },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                ChatMessagesList(
                    messages =
                        persistentListOf(
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
private fun ChatScreenLoadingPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("AI Assistant") }) },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                ChatMessagesList(
                    messages =
                        persistentListOf(
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
private fun ChatMessagesListDarkPreview() =
    PreviewThemed(darkTheme = true, useSurface = false) {
        Column(modifier = Modifier.fillMaxSize()) {
            ChatMessagesList(
                messages =
                    persistentListOf(
                        ChatMessage("m1", ChatRole.User, "Draft the release notes"),
                        ChatMessage("m2", ChatRole.Assistant, "Here is a first pass at the release notes."),
                    ),
                isLoading = false,
                onSurfaceAction = { _, _ -> },
                modifier = Modifier.weight(1f),
            )
        }
    }
