package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSwitchRow
import com.singularity.todo.core.ui.preview.PreviewThemed
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
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
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
        // Live preview — mini task card showing the selected accent
        val currentAccent = SingularityAccents.fromString(selected)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(currentAccent.color),
                )
                Text(
                    "Sample task",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Swatches row
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SingularityAccents.entries.forEach { accent ->
                AccentSwatch(
                    accent = accent,
                    selected = accent.name.equals(selected, ignoreCase = true),
                    onClick = { onSelect(accent.name.lowercase()) },
                )
            }
        }
    }
}

@Composable
private fun AccentSwatch(
    accent: SingularityAccents,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(accent.color)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
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
        // Live preview sample
        Text(
            "Sample task text",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = (14.sp * value),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun InterfaceSettingsScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    InterfaceSettingsScreen(
        state = SettingsUiState.Content(
            darkTheme = false,
            accentColor = "blue",
            fontSizeScale = 1.0f,
        ),
        onIntent = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun InterfaceSettingsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    InterfaceSettingsScreen(
        state = SettingsUiState.Content(
            darkTheme = true,
            accentColor = "purple",
            fontSizeScale = 1.25f,
        ),
        onIntent = {},
    )
}
