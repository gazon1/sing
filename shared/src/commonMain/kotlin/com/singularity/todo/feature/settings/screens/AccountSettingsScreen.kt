package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.feature.settings.SettingsUiState

@Composable
fun AccountSettingsScreen(
    state: SettingsUiState.Content,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Account") {
            Text(
                text = "User ID",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.userId,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        SettingsSection(title = "Data") {
            DataActionRow(
                primaryLabel = "Export Data",
                onPrimary = { /* TODO: export data */ },
                dangerLabel = "Clear All",
                onDanger = { /* TODO: clear data */ },
            )
        }
    }
}

@Composable
private fun DataActionRow(
    primaryLabel: String,
    onPrimary: () -> Unit,
    dangerLabel: String,
    onDanger: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onPrimary, modifier = Modifier.weight(1f)) {
            Text(primaryLabel)
        }
        Button(onClick = onDanger, modifier = Modifier.weight(1f)) {
            Text(dangerLabel)
        }
    }
}
