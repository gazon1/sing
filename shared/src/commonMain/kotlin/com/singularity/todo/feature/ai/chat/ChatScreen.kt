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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.BubbleRole
import com.singularity.todo.core.ui.components.ChatInputBar
import com.singularity.todo.core.ui.components.MessageBubble
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.preview.PreviewThemed
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val vm: ChatViewModel = koinViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("AI Assistant") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            ChatMessagesList(
                messages = state.messages,
                isLoading = state.isLoading,
                modifier = Modifier.weight(1f),
            )
            HorizontalDivider()
            ChatInputBar(
                value = state.input,
                onValueChange = { vm.onIntent(ChatViewModel.Intent.InputChanged(it)) },
                onSend = { vm.onIntent(ChatViewModel.Intent.Send) },
                enabled = !state.isLoading,
            )
        }
    }

    NotificationHost(
        events = vm.events,
        mapper = { it.toNotification() },
        modifier = Modifier.testTag("chat_notification_host"),
    )
}

private fun ChatUiEvent.toNotification(): Notification = when (this) {
    is ChatUiEvent.Error -> Notification.Error(message)
}

@Composable
private fun ChatMessagesList(messages: List<ChatMessage>, isLoading: Boolean, modifier: Modifier = Modifier) {
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
            MessageBubble(
                role = if (msg.role == ChatRole.User) BubbleRole.User else BubbleRole.Assistant,
                content = msg.content,
            )
        }
        if (isLoading) item { ThinkingIndicator() }
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
                messages = listOf(
                    ChatMessage("m1", ChatRole.User, "Hello, what can you help me with?"),
                    ChatMessage("m2", ChatRole.Assistant, "Hi! I can help you manage your tasks, take notes, and organize your projects."),
                    ChatMessage("m3", ChatRole.User, "Can you show me my tasks for today?"),
                    ChatMessage("m4", ChatRole.Assistant, "You have 3 tasks due today: Buy groceries, Finish project report, and Send follow-up emails."),
                ),
                isLoading = false,
                modifier = Modifier.weight(1f),
            )
            HorizontalDivider()
            ChatInputBar(
                value = "",
                onValueChange = {},
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
                messages = listOf(
                    ChatMessage("m1", ChatRole.User, "Can you explain Kotlin coroutines?"),
                    ChatMessage("m2", ChatRole.Assistant, "Kotlin Coroutines are a way to handle asynchronous programming in a sequential manner. They allow you to write code that looks synchronous but can pause and resume without blocking."),
                ),
                isLoading = true,
                modifier = Modifier.weight(1f),
            )
            HorizontalDivider()
            ChatInputBar(
                value = "",
                onValueChange = {},
                onSend = {},
                enabled = false,
            )
        }
    }
}
