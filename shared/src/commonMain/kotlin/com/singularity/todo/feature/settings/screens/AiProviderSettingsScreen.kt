package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.feature.ai.LlmProvider
import com.singularity.todo.feature.ai.OpenAiConfig
import com.singularity.todo.feature.settings.AiTestResult
import com.singularity.todo.feature.settings.SettingsIntent
import com.singularity.todo.feature.settings.SettingsUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderSettingsScreen(
    state: SettingsUiState.Content,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local-only field for the API key — never enters UI state. The value is sent to
    // the ViewModel via SettingsIntent.UpdateAiApiKey as the user types, and lives in
    // SecureStorage on the VM side.
    var apiKeyField by remember { mutableStateOf("") }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ─── Provider ───────────────────────────────────────────────────────────
        SettingsSection(title = "Provider") {
            Text(
                "Choose your LLM backend",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var expanded by remember { mutableStateOf(false) }
            val currentProvider = LlmProvider.fromId(state.aiProvider)

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = currentProvider.id,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    LlmProvider.entries.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.id) },
                            onClick = {
                                onIntent(SettingsIntent.UpdateAiProvider(provider.id))
                                // Auto-fill default base URL when switching to a provider
                                // whose default the user hasn't customised. Uses the same
                                // rule as [OpenAiConfig.resolveBaseUrl] so that
                                // `Test connection` and the displayed URL agree.
                                val resolved = OpenAiConfig.resolveBaseUrl(state.aiBaseUrl, provider)
                                if (resolved != state.aiBaseUrl) {
                                    onIntent(SettingsIntent.UpdateAiBaseUrl(resolved))
                                }
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        // ─── API Key ────────────────────────────────────────────────────────────
        SettingsSection(title = "API Key") {
            Text(
                "Stored securely in device keychain. Never leaves this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = apiKeyField,
                onValueChange = {
                    apiKeyField = it
                    onIntent(SettingsIntent.UpdateAiApiKey(it))
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("OpenAI API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }

        // ─── Base URL ───────────────────────────────────────────────────────────
        SettingsSection(title = "Base URL") {
            Text(
                "OpenAI-compatible endpoint. Auto-filled from provider defaults.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.aiBaseUrl,
                onValueChange = { onIntent(SettingsIntent.UpdateAiBaseUrl(it)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("https://...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }

        // ─── Model ──────────────────────────────────────────────────────────────
        SettingsSection(title = "Model") {
            OutlinedTextField(
                value = state.aiModel,
                onValueChange = { onIntent(SettingsIntent.UpdateAiModel(it)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Model name") },
                singleLine = true,
            )
        }

        // ─── System Prompt ─────────────────────────────────────────────────────
        SettingsSection(title = "System Prompt") {
            Text(
                "Sent to the model on every request. Edit to specialise the assistant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.aiSystemPrompt,
                onValueChange = { onIntent(SettingsIntent.UpdateAiSystemPrompt(it)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("System prompt") },
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        }

        // ─── Test Connection ────────────────────────────────────────────────────
        SettingsSection(title = "Test Connection") {
            Button(
                onClick = { onIntent(SettingsIntent.TestAiConnection) },
                enabled = state.aiTestResult !is AiTestResult.Testing,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (state.aiTestResult is AiTestResult.Testing) "Testing…" else "Send ping")
            }
            AiTestResultBanner(state.aiTestResult, Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun AiTestResultBanner(result: AiTestResult, modifier: Modifier = Modifier) {
    when (result) {
        AiTestResult.Idle, AiTestResult.Testing -> {
            // No banner — the button itself shows progress.
            Unit
        }
        is AiTestResult.Ok -> {
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Text(
                    "Connected. Latency: ${result.latencyMs} ms",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        is AiTestResult.Error -> {
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    "Failed: ${result.message}",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}