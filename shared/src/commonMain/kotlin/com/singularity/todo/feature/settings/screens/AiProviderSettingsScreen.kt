package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.llm.LlmProvider
import com.singularity.todo.core.llm.OpenAiConfig
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.preview.PreviewThemed
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
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
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
            val currentProvider = state.ai.provider.id

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = currentProvider,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(
                            type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                            enabled = true,
                        )
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    LlmProvider.entries.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.id) },
                            onClick = {
                                onIntent(SettingsIntent.Ai.UpdateProvider(provider))
                                // Auto-fill default base URL when switching to a provider
                                // whose default the user hasn't customised. Uses the same
                                // rule as [OpenAiConfig.resolveBaseUrl] so that
                                // `Test connection` and the displayed URL agree.
                                val resolved = OpenAiConfig.resolveBaseUrl(state.ai.baseUrl, provider)
                                if (resolved != state.ai.baseUrl) {
                                    onIntent(SettingsIntent.Ai.UpdateBaseUrl(resolved))
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
                    onIntent(SettingsIntent.Ai.UpdateApiKey(it))
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("OpenAI API Key") },
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide API key" else "Show API key",
                        )
                    }
                },
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
                value = state.ai.baseUrl,
                onValueChange = { onIntent(SettingsIntent.Ai.UpdateBaseUrl(it)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("https://...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }

        // ─── Model ──────────────────────────────────────────────────────────────
        SettingsSection(title = "Model") {
            if (state.aiEphemeral.models.isNotEmpty()) {
                var modelExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    OutlinedTextField(
                        value = state.ai.model,
                        onValueChange = { onIntent(SettingsIntent.Ai.UpdateModel(it)) },
                        readOnly = true,
                        label = { Text("Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier
                            .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false },
                    ) {
                        state.aiEphemeral.models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    onIntent(SettingsIntent.Ai.UpdateModel(model))
                                    modelExpanded = false
                                },
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = state.ai.model,
                    onValueChange = { onIntent(SettingsIntent.Ai.UpdateModel(it)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Model name") },
                    singleLine = true,
                )
            }
            if (state.aiEphemeral.fetchModelsError != null) {
                Text(
                    text = "Fetch error: ${state.aiEphemeral.fetchModelsError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Button(
                onClick = { onIntent(SettingsIntent.Ai.FetchModels) },
                enabled = !state.aiEphemeral.isFetchingModels,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (state.aiEphemeral.isFetchingModels) "Fetching…" else "Fetch models")
            }
        }

        // ─── System Prompt ─────────────────────────────────────────────────────
        SettingsSection(title = "System Prompt") {
            Text(
                "Sent to the model on every request. Edit to specialise the assistant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.ai.systemPrompt,
                onValueChange = { onIntent(SettingsIntent.Ai.UpdateSystemPrompt(it)) },
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
                onClick = { onIntent(SettingsIntent.Ai.TestConnection) },
                enabled = state.aiEphemeral.testResult !is AiTestResult.Testing,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (state.aiEphemeral.testResult is AiTestResult.Testing) "Testing…" else "Send ping")
            }
            AiTestResultBanner(state.aiEphemeral.testResult, Modifier.padding(top = 8.dp))
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

// ===== Preview =====

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AiProviderSettingsScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    AiProviderSettingsScreen(
        state = SettingsUiState.Content(
            ai = com.singularity.todo.core.settings.SettingsSection.Ai(
                provider = LlmProvider.OPENAI,
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4o-mini",
                systemPrompt = "You are a helpful assistant.",
            ),
        ),
        onIntent = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AiProviderSettingsScreenConnectedPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    AiProviderSettingsScreen(
        state = SettingsUiState.Content(
            ai = com.singularity.todo.core.settings.SettingsSection.Ai(
                provider = LlmProvider.OPENAI,
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4o-mini",
                systemPrompt = "You are a helpful assistant.",
            ),
            aiEphemeral = com.singularity.todo.core.settings.EphemeralState.Ai(
                testResult = AiTestResult.Ok(latencyMs = 234),
            ),
        ),
        onIntent = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AiProviderSettingsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    AiProviderSettingsScreen(
        state = SettingsUiState.Content(
            ai = com.singularity.todo.core.settings.SettingsSection.Ai(
                provider = LlmProvider.ANTHROPIC_COMPATIBLE,
                baseUrl = "https://api.anthropic.com/v1",
                model = "claude-sonnet-4-20250514",
                systemPrompt = "You are Claude.",
            ),
            aiEphemeral = com.singularity.todo.core.settings.EphemeralState.Ai(
                testResult = AiTestResult.Error(message = "Connection timeout"),
            ),
        ),
        onIntent = {},
    )
}
