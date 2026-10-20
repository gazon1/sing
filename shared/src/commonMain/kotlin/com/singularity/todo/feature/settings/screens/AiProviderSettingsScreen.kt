package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.llm.LlmProvider
import com.singularity.todo.core.llm.OpenAiConfig
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.textfields.rememberAnchoredTextFieldState
import com.singularity.todo.core.ui.textfields.rememberBridgedTextFieldState
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.settings.SettingsUiState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

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
    val apiKeyState = rememberTextFieldState()
    var passwordVisible by remember { mutableStateOf(false) }

    // The key is sent to the ViewModel as the user types. The first emission is the
    // field's initial empty value, which is not an edit — pushing it would clear the
    // key already in secure storage.
    LaunchedEffect(apiKeyState) {
        snapshotFlow { apiKeyState.text.toString() }
            .distinctUntilChanged()
            .drop(1)
            .collect { onIntent(SettingsIntent.Ai.UpdateApiKey(it)) }
    }

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
            val providerState = rememberAnchoredTextFieldState(currentProvider)

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    state = providerState,
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
            OutlinedSecureTextField(
                state = apiKeyState,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("OpenAI API Key") },
                textObfuscationMode = if (passwordVisible) {
                    TextObfuscationMode.Visible
                } else {
                    TextObfuscationMode.Hidden
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
            val baseUrlState = rememberBridgedTextFieldState(
                source = state.baseUrlSource,
                onTextChanged = { onIntent(SettingsIntent.Ai.UpdateBaseUrl(it)) },
            )
            OutlinedTextField(
                state = baseUrlState,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("https://...") },
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }

        // ─── Model ──────────────────────────────────────────────────────────────
        SettingsSection(title = "Model") {
            // Read-only anchor: the dropdown sets the model, the field only shows it.
            val modelState = rememberAnchoredTextFieldState(state.ai.model)
            val modelNameState = rememberBridgedTextFieldState(
                source = state.modelSource,
                onTextChanged = { onIntent(SettingsIntent.Ai.UpdateModel(it)) },
            )
            if (state.aiEphemeral.models.isNotEmpty()) {
                var modelExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    OutlinedTextField(
                        state = modelState,
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
                    state = modelNameState,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Model name") },
                    lineLimits = TextFieldLineLimits.SingleLine,
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
            val systemPromptState = rememberBridgedTextFieldState(
                source = state.systemPromptSource,
                onTextChanged = { onIntent(SettingsIntent.Ai.UpdateSystemPrompt(it)) },
            )
            Text(
                "Sent to the model on every request. Edit to specialise the assistant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                state = systemPromptState,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("System prompt") },
                lineLimits = TextFieldLineLimits.MultiLine(
                    minHeightInLines = 3,
                    maxHeightInLines = 6,
                ),
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
