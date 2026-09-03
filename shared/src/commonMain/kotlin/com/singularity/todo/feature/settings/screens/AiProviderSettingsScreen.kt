package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.settings.SettingsIntent
import com.singularity.todo.feature.settings.SettingsUiState

@Composable
fun AiProviderSettingsScreen(
    state: SettingsUiState.Content,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // API Key — stored in SecureStorage, displayed as masked
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("API Key", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Stored securely in device keychain",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = state.aiApiKey,
                    onValueChange = { onIntent(SettingsIntent.UpdateAiApiKey(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OpenAI API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        }

        // Base URL
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Base URL", style = MaterialTheme.typography.titleSmall)
                Text(
                    "OpenAI-compatible endpoint",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = state.aiBaseUrl,
                    onValueChange = { onIntent(SettingsIntent.UpdateAiBaseUrl(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("https://...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            }
        }

        // Model
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Model", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = state.aiModel,
                    onValueChange = { onIntent(SettingsIntent.UpdateAiModel(it)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Model name") },
                    singleLine = true
                )
            }
        }
    }
}
