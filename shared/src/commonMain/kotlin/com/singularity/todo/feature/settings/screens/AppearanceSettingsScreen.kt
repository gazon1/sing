package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.feature.settings.SettingsIntent
import com.singularity.todo.feature.settings.SettingsUiState

@Composable
fun AppearanceSettingsScreen(
    state: SettingsUiState.Content,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Dark theme toggle
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Dark Theme", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Use dark color scheme",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.darkTheme,
                    onCheckedChange = { onIntent(SettingsIntent.UpdateDarkTheme(it)) }
                )
            }
        }

        // Accent color picker
        Column {
            Text("Accent Color", style = MaterialTheme.typography.titleMedium)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                items(SingularityAccents.entries) { accent ->
                    Card(
                        modifier = Modifier.clickable {
                            onIntent(SettingsIntent.UpdateAccentColor(accent.name.lowercase()))
                        },
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

        // Font size scale slider
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Font Size", style = MaterialTheme.typography.titleMedium)
                Text(
                    "%.0f%%".format(state.fontSizeScale * 100),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Slider(
                value = state.fontSizeScale,
                onValueChange = { onIntent(SettingsIntent.UpdateFontSizeScale(it)) },
                valueRange = 0.75f..1.5f,
                steps = 5,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
