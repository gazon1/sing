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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSwitchRow
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.feature.settings.SettingsIntent
import com.singularity.todo.feature.settings.SettingsUiState

@Composable
fun InterfaceSettingsScreen(
    state: SettingsUiState.Content,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Theme") {
            SettingsSwitchRow(
                title = "Dark Theme",
                subtitle = "Use dark color scheme",
                checked = state.darkTheme,
                onCheckedChange = { onIntent(SettingsIntent.UpdateDarkTheme(it)) },
            )
        }

        AccentColorPicker(
            selected = state.accentColor,
            onSelect = { onIntent(SettingsIntent.UpdateAccentColor(it)) },
        )

        FontSizeSlider(
            value = state.fontSizeScale,
            onValueChange = { onIntent(SettingsIntent.UpdateFontSizeScale(it)) },
        )
    }
}

@Composable
private fun AccentColorPicker(
    selected: String,
    onSelect: (String) -> Unit,
) {
    SettingsSection(title = "Accent Color") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SingularityAccents.entries) { accent ->
                AccentChip(
                    accent = accent,
                    selected = accent.name.equals(selected, ignoreCase = true),
                    onClick = { onSelect(accent.name.lowercase()) },
                )
            }
        }
    }
}

@Composable
private fun AccentChip(
    accent: SingularityAccents,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else accent.color,
        ),
    ) {
        Text(
            accent.displayName,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color.White,
        )
    }
}

@Composable
private fun FontSizeSlider(value: Float, onValueChange: (Float) -> Unit) {
    SettingsSection(title = "Font Size") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Scale", style = MaterialTheme.typography.bodyMedium)
            Text("%.0f%%".format(value * 100), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.75f..1.5f,
            steps = 5,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
