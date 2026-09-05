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
import com.singularity.todo.core.ui.components.ChatInputBar
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.MessageBubble
import com.singularity.todo.core.ui.components.BubbleRole
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import org.koin.compose.koinInject

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val vm: ChatViewModel = koinInject()
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
