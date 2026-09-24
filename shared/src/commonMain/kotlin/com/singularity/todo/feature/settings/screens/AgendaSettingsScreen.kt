package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.core.ui.components.SettingsRadioRow
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
            val views = state.agendaEphemeral.savedViews
            val selectedId = state.defaultAgendaView.viewId

            // "None" option — clears the default
            SettingsRadioRow(
                title = "None",
                subtitle = "Use the tab preset (Inbox, Today, etc.)",
                selected = selectedId == null,
                onClick = { onIntent(SettingsIntent.DefaultAgendaView.Update(null)) },
            )

            // Each saved view as a radio option
            views.forEach { view ->
                SettingsRadioRow(
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

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AgendaSettingsScreenLightPreview() = PreviewThemed(darkTheme = false) {
    AgendaSettingsScreen(
        state = SettingsUiState.Content(),
        onIntent = {},
    )
}
