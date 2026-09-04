package com.singularity.todo.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.ResultDialog
import com.singularity.todo.core.ui.components.UiEvent
import org.koin.compose.koinInject

/**
 * Create-task screen. The screen used to own `var title by remember`, the
 * coroutine scope, the saving flag, and the error message — every one of
 * those now lives in [TaskEditorViewModel], which is also unit-tested.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(onBack: () -> Unit) {
    val vm: TaskEditorViewModel = koinInject()
    val state by vm.uiState.collectAsStateWithLifecycle()

    var dialogText by remember { mutableStateOf<String?>(null) }
    CollectEvents(vm.events) { event ->
        when (event) {
            is UiEvent.ShowDialog -> dialogText = event.text
            is UiEvent.ShowError -> dialogText = event.message
            UiEvent.NavigateBack -> onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Task") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.onIntent(TaskEditorIntent.Save) },
                        enabled = state.title.isNotBlank() && !state.saving,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        TaskEditorBody(
            state = state,
            onTitleChange = { vm.onIntent(TaskEditorIntent.TitleChanged(it)) },
            onDescriptionChange = { vm.onIntent(TaskEditorIntent.DescriptionChanged(it)) },
            onSave = { vm.onIntent(TaskEditorIntent.Save) },
            modifier = Modifier.padding(padding),
        )
    }

    ResultDialog(title = "Error", text = dialogText, onDismiss = { dialogText = null })
}

@Composable
private fun TaskEditorBody(
    state: TaskEditorUiState,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = { Text("Title") },
            isError = state.errorMessage != null,
            supportingText = state.errorMessage?.let { msg ->
                { Text(msg, color = MaterialTheme.colorScheme.error) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChange,
            label = { Text("Description (optional)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onSave,
            enabled = state.title.isNotBlank() && !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.saving) "Saving…" else "Create task")
        }
    }
}
