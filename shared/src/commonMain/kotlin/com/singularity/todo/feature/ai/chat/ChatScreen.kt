package com.singularity.todo.feature.ai.chat

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
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
    // Counted from the list itself rather than remembered against its length: a list replaced by
    // one of the same length — a turn that swaps a placeholder, a profile switch — would otherwise
    // keep a count that no longer describes it, and the policy keys the reader's own question on
    // this number. The scan is over a conversation, not over a corpus.
    val questionCount = messages.count { it.role == ChatRole.User }
    FollowConversationEnd(
        listState = listState,
        tail =
            ConversationTail(
                messageCount = messages.size,
                lastContentLength = messages.lastOrNull()?.content?.length ?: 0,
                questionCount = questionCount,
                isLoading = isLoading,
            ),
    )
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag(TestTags.CHAT_MESSAGES),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        // Bottom-anchored, so that a conversation shorter than the window keeps its end at the
        // bottom instead of hanging from the top with the newest message in the middle of the
        // screen. The scroll policy cannot do this and does not try: there is no distance to
        // scroll, so `remaining` in `slideToContentEnd` is not positive and the move does nothing,
        // which is also why "a short conversation is not scrolled" and "its end sits at the
        // bottom" are one property of the arrangement rather than two of the policy. Content that
        // overflows is unaffected: an arrangement's alignment only positions what is left over.
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
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
 * The shape of the end of the conversation, as the follow-the-tail policy needs it.
 *
 * A value of its own so the policy can be mounted and driven without a whole `ChatMessage` list,
 * and so the two things the policy reacts to — a new message and a token inside the last one —
 * are named rather than dug out of the conversation on every recomposition.
 */
data class ConversationTail(
    val messageCount: Int,
    val lastContentLength: Int,
    val questionCount: Int,
    val isLoading: Boolean,
) {
    /** The index of the last item the list draws. The thinking indicator, when shown, is one. */
    val lastItemIndex: Int
        get() = messageCount + (if (isLoading) 1 else 0) - 1
}

/**
 * Keeps the end of the conversation in view as a reply streams in.
 *
 * The reply arrives one token at a time, so the end of the conversation moves on every token.
 * Three things keep that from being a stream of jumps:
 *
 * - **Only when the end is out of view.** The move measures the distance between the last laid-out
 *   item and the bottom of the viewport, and scrolls only that distance: content that has grown
 *   *inside* the viewport needs no move at all. What is measured is that distance, never the
 *   visibility of an index — a tall reply's index is on screen from its first line while the token
 *   being written sits at its last, so "the last message is visible" says nothing about whether the
 *   newest token is. The thinking indicator is part of this end while a turn runs: it is drawn
 *   after the answer, so it is what "below the reply" means for as long as the turn lasts, and
 *   [ConversationTail.lastItemIndex] therefore counts it.
 * - **One slide at a time.** The move may be an animation, so the effect that runs it must outlive
 *   the token that asked for it. A `LaunchedEffect` keyed on the tail's length is disposed by the
 *   next token, which cancels the animation in flight and starts another from a standstill: a
 *   chase that never lands. The requests go through a conflated channel into one long-lived mover
 *   instead, which serves them one after another. A token that arrives mid-slide therefore
 *   retargets the next move rather than killing this one.
 * - **Only while the reader is at the end.** Somebody who scrolled up to re-read an earlier answer
 *   is left where they are — a new token is not an argument for moving them — and the end is
 *   picked up again when they scroll back down, or when they send a message of their own, which is
 *   a request to see it.
 *
 * Who moved the list is read from its position, not from its scroll events. A move that lands
 * records the position it left the list at, and a later request compares the list against that
 * record: a reply that grew at its end leaves the position untouched, a scroll does not. The
 * mover is the only other author of a scroll and it is idle while a request is being read, so a
 * position that moved is the reader's. `LazyListState.isScrollInProgress` was the earlier
 * discriminator, and it cannot answer this question at all: a slide of the policy's own that is
 * still running when the next token lengthens the reply stops with the end once more below the
 * fold, which is indistinguishable from a reader who scrolled away — so every such slide was read
 * as the reader taking the wheel, and the rest of the reply was dropped on the floor.
 *
 * The first move is a jump rather than an animation: a conversation restored from the store can be
 * twenty messages long, and animating through all of them is a fairground ride.
 *
 * This is public, rather than private like the rest of the screen's parts, for the same reason
 * [ChatScreenContent] is: the desktop Compose harness mounts it on its own to prove the two
 * behaviours above, and a policy that can only be tested through a whole chat screen is a policy
 * nothing tests.
 */
@Composable
fun FollowConversationEnd(
    listState: LazyListState,
    tail: ConversationTail,
) {
    // Whether the reader is with the end of the conversation.
    var following by remember(listState) { mutableStateOf(true) }

    // Where this policy last left the list, so a later request can tell a reply that grew — which
    // leaves the position alone — from a scroll, which does not. Recorded only by a move that
    // landed: a move the reader cancelled landed nothing, and pretending otherwise would make the
    // next request read their position as ours and drag them back to the end.
    var left by remember(listState) { mutableStateOf(listState.anchor) }

    // How many questions the reader has asked, as of the last request.
    //
    // Saved, not merely remembered: a screen remounted from saved state — a tab switch, a process
    // restored after the system killed it — restores a conversation whose first tail already has
    // questions in it. Counted from zero again, that first tail looks like a question the reader
    // just asked, and the reader-asked rule would override where they were parked. The count is
    // part of the conversation's history, so it is saved alongside it.
    var questions by rememberSaveable(listState) { mutableStateOf(0) }

    // "The end of the conversation moved, and here is how." Conflated on purpose: two tokens in
    // one frame are one move, and a request that arrives mid-slide retargets the next move rather
    // than killing this one.
    val moves = remember(listState) { Channel<MoveRequest>(Channel.CONFLATED) }

    // A token, a new message, the loading indicator appearing or going away: the end of the
    // conversation may have moved. Keyed on the tail itself, because every field of it can move
    // the end — and because `questionCount` is then read once per tail and compared, rather than
    // being written down again for each token that cannot have changed it.
    LaunchedEffect(tail) {
        // A question of the reader's own is a request to see the answer to it — they are not
        // re-reading an older answer, they are waiting for a new one. It cannot be recognised by
        // the tail's role: `ChatViewModel.turn` appends the question *and* an empty message for
        // the answer in one state change, so the tail is already the assistant's placeholder by
        // the time anything here can look at it. The questions are counted instead, and only a
        // count that grew is a question: a token leaves the count alone.
        val asked = tail.questionCount > questions
        if (asked) questions = tail.questionCount
        if (tail.messageCount > 0) {
            moves.trySend(MoveRequest(index = tail.lastItemIndex, readerAsked = asked))
        }
    }

    // One mover, for the life of the list. Nothing here is keyed on a token, so a slide that is
    // running is never disposed by the next one: the token's request waits in the channel and is
    // served when the slide lands.
    LaunchedEffect(listState) {
        var landed = false
        moves.receiveAsFlow().collect { request ->
            // A reader's gesture outranks the move, but it does not discard the request: the
            // channel has already handed this one over, and no later token re-sends it, so
            // dropping it here would lose the last token of a turn that happened to arrive under
            // their thumb. Wait for the wheel to stop and then ask again where they left the list.
            if (listState.isScrollInProgress) {
                snapshotFlow { listState.isScrollInProgress }.first { !it }
            }
            if (request.readerAsked) {
                // They asked. That is a request to see the answer, not a re-read of an older one,
                // and it outranks where they happened to be parked when they typed.
                following = true
            } else if (listState.anchor != left) {
                // The list is not where this policy left it, and the reply growing at its end
                // cannot have moved it: the reader scrolled. Where they left it is their answer,
                // not ours — the end of the content means they are following, anything else means
                // they are reading something earlier.
                following = !listState.canScrollForward
            }
            if (!following) return@collect
            when (listState.slideToContentEnd(lastIndex = request.index, animate = landed)) {
                MoveOutcome.Landed -> {
                    left = listState.anchor
                    landed = true
                }

                // Nothing to record: there was nothing to show yet, or the reader took the list.
                MoveOutcome.NothingToDo, MoveOutcome.TakenOver -> {
                    Unit
                }
            }
        }
    }
}

/**
 * Where a lazy list sits: the anchor a scroll leaves behind, and content that grows at the end
 * does not.
 *
 * An item index alone cannot say this — a tall reply is "the last item" from its first line to its
 * last — which is also why the policy measures pixels rather than asking whether an index is
 * visible.
 */
private data class ScrollAnchor(val index: Int, val offset: Int)

private val LazyListState.anchor: ScrollAnchor
    get() = ScrollAnchor(firstVisibleItemIndex, firstVisibleItemScrollOffset)

/**
 * "The end of the conversation moved." Carries the item to align and how the request came about,
 * because a question of the reader's own outranks the position rule: they asked to see the answer.
 */
private data class MoveRequest(val index: Int, val readerAsked: Boolean)

/**
 * What became of one move.
 *
 * These used to be one `Boolean`, and the two facts it carried are not the same one: a move that
 * lands leaves the list parked at the end and is the only kind that may be recorded as such, while
 * a move the reader cancels lands nothing at all. A reader who cancels a slide has taken the list,
 * so the next request finds their position and stops following them; they are not owed a second
 * attempt at the same move.
 */
private enum class MoveOutcome {
    /** The list shows the end of the content, and that position is the one to remember. */
    Landed,

    /** There is nothing to show yet: no messages, or no layout to move. */
    NothingToDo,

    /** A reader's gesture cancelled the move before it landed. */
    TakenOver,
}

/**
 * Puts the end of the content at the bottom of the viewport, sliding or jumping.
 *
 * Not `animateScrollToItem(lastIndex)`, which aligns the last item's *start* with the top of the
 * viewport: for the one item this screen follows — the answer being written, once it is taller
 * than the viewport — that puts the first line on screen and every line after it, including the
 * token that has just arrived, below the fold, with nowhere left to scroll. The end of the
 * content is the position with nothing below the viewport.
 *
 * A reader's gesture is what cancels this: Compose cancels a scroll of ours when their input takes
 * the scroll mutex, so the cancellation caught below *is* the signal, and no flag has to be polled
 * to learn that they took the wheel. A cancellation that is not theirs — the screen going away,
 * the list being disposed — is rethrown by `ensureActive`.
 */
@Suppress("NoSwallowedCancellation") // swallowed: the scroll mutex's preemption, never a coroutine's
private suspend fun LazyListState.slideToContentEnd(
    lastIndex: Int,
    animate: Boolean,
): MoveOutcome {
    if (lastIndex < 0) return MoveOutcome.NothingToDo
    try {
        // The list may not have been laid out at all when the first request arrives — a
        // conversation restored from the store, or the first frame a message arrives on. A jump
        // by index suspends inside the scroll until a layout exists, which is the guarantee this
        // rewrite must not trade away: the layout of the frame before this content grew is empty,
        // and measuring it would leave a restored chat at the top.
        if (layoutInfo.visibleItemsInfo.lastOrNull()?.index != lastIndex) {
            if (animate) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
        }
        // The frame this move was woken on has composed the new content but not yet measured it:
        // the effect resumes during composition, before layout. Waiting for the next frame makes
        // the measurement below the one the newest token produced rather than the previous one.
        // Without this, a token that pushed the reply past the fold was measured as still fitting,
        // dropped, and left below the viewport until the token after it.
        withFrameNanos { }
        // `viewportEndOffset` is the last pixel the reader can see, so the remainder is how far
        // the end of the content sits below the fold. Erring high is safe — a scroll stops at the
        // end of the content — so the trailing content padding is left in. Content may have grown
        // while the move above was running; the remainder measured here is the one that counts,
        // and the token that grew it is still in the conflated channel as the next request.
        val tail = layoutInfo.visibleItemsInfo.lastOrNull() ?: return MoveOutcome.NothingToDo
        val remaining = (tail.offset + tail.size - layoutInfo.viewportEndOffset).toFloat()
        if (remaining > 0f) {
            if (animate) animateScrollBy(remaining) else scrollBy(remaining)
        }
        return MoveOutcome.Landed
    } catch (_: CancellationException) {
        // A reader's drag outranks this move and cancels it. That ends the move, not the follower:
        // the mover is still collecting, and the next token is served as usual. Nothing is
        // recorded about where the list now sits — the reader put it there, and the next request
        // reads that from the list itself. A cancellation of the follower itself is rethrown by
        // `ensureActive`, so nothing is swallowed but the preemption.
        currentCoroutineContext().ensureActive()
        return MoveOutcome.TakenOver
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
