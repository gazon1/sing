package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.settings.SettingsUiState

/**
 * Agenda settings sub-screen.
 *
 * Allows the user to pick which saved agenda view should be used as the default
 * when opening the Agenda tab, and to manage saved views (navigate to SavedAgendaList).
 */
@Composable
fun AgendaSettingsScreen(
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
        SettingsSection(title = "Default View") {
            val views = state.savedAgendaViews
            val selectedId = state.defaultSavedAgendaViewId

            // "None" option — clears the default
            RadioRow(
                title = "None",
                subtitle = "Use the tab preset (Inbox, Today, etc.)",
                selected = selectedId == null,
                onClick = { onIntent(SettingsIntent.DefaultAgendaView.Update(null)) },
            )

            // Each saved view as a radio option
            views.forEach { view ->
                RadioRow(
                    title = view.name,
                    subtitle = null,
                    selected = selectedId == view.id,
                    onClick = { onIntent(SettingsIntent.DefaultAgendaView.Update(view.id)) },
                )
            }

            if (views.isEmpty()) {
                Text(
                    text = "No saved views yet. Create one from the Agenda tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RadioRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            RadioButton(selected = false, onClick = null)
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AgendaSettingsScreenLightPreview() = PreviewThemed(darkTheme = false) {
    AgendaSettingsScreen(
        state = SettingsUiState.Content(),
        onIntent = {},
    )
}
