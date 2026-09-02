package com.singularity.todo.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.ui.theme.SingularityAccents
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val settings: SettingsRepository = koinInject()
    val darkTheme by settings.darkTheme.collectAsState(initial = false)
    val accentColor by settings.accentColor.collectAsState(initial = "blue")
    val fontSize by settings.fontSizeScale.collectAsState(initial = 1f)
    val aiApiKey by settings.aiApiKey.collectAsState(initial = null)
    val aiModel by settings.aiModel.collectAsState(initial = "gpt-4o-mini")

    var apiKeyInput by remember(aiApiKey) { mutableStateOf(aiApiKey ?: "") }
    var modelInput by remember(aiModel) { mutableStateOf(aiModel) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Appearance", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dark Theme")
                        Switch(
                            checked = darkTheme,
                            onCheckedChange = { /* TODO */ }
                        )
                    }
                }
            }
            item {
                Text("Accent Color", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SingularityAccents.entries) { accent ->
                        Card(
                            modifier = Modifier.clickable { /* TODO */ },
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = accent.color
                            )
                        ) {
                            Text(
                                accent.displayName,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = androidx.compose.ui.graphics.Color.White
                            )
                        }
                    }
                }
            }

            item {
                Text("AI Settings", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OpenAI API Key") },
                    singleLine = true
                )
            }
            item {
                OutlinedTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model") },
                    singleLine = true
                )
            }
        }
    }
}
