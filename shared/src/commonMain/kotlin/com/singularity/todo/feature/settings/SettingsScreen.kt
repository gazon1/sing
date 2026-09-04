package com.singularity.todo.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.settings.screens.AccountSettingsScreen
import com.singularity.todo.feature.settings.screens.AiProviderSettingsScreen
import com.singularity.todo.feature.settings.screens.AppearanceSettingsScreen
import com.singularity.todo.feature.settings.screens.NotificationSettingsScreen
import com.singularity.todo.feature.settings.screens.WorkScheduleSettingsScreen
import org.koin.compose.koinInject

private enum class SettingsTab(val label: String) {
    Appearance("Appearance"),
    Notifications("Notifications"),
    AIProvider("AI Provider"),
    WorkSchedule("Work Schedule"),
    Account("Account"),
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = koinInject()
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(SettingsTab.Appearance) }

    when (val state = uiState) {
        is SettingsUiState.Loading -> {
            Box(modifier = modifier.fillMaxSize()) {
                Text("Loading...", modifier = Modifier.padding(16.dp))
            }
        }
        is SettingsUiState.Error -> {
            Box(modifier = modifier.fillMaxSize()) {
                Text("Error: ${state.cause}", modifier = Modifier.padding(16.dp))
            }
        }
        is SettingsUiState.Content -> {
            Row(modifier = modifier.fillMaxSize()) {
                // Navigation rail on wide screens
                if (false) { // TODO: use WindowSizeClass
                    SettingsNavRail(selectedTab, Modifier.fillMaxHeight()) { tab ->
                        selectedTab = tab
                    }
                    VerticalDivider()
                }

                // Sub-screen content
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        SettingsTab.Appearance -> AppearanceSettingsScreen(
                            state = state,
                            onIntent = viewModel::processIntent,
                            modifier = Modifier.fillMaxSize()
                        )
                        SettingsTab.Notifications -> NotificationSettingsScreen(
                            state = state,
                            onIntent = viewModel::processIntent,
                            modifier = Modifier.fillMaxSize()
                        )
                        SettingsTab.AIProvider -> AiProviderSettingsScreen(
                            state = state,
                            onIntent = viewModel::processIntent,
                            modifier = Modifier.fillMaxSize()
                        )
                        SettingsTab.WorkSchedule -> WorkScheduleSettingsScreen(
                            state = state,
                            onIntent = viewModel::processIntent,
                            modifier = Modifier.fillMaxSize()
                        )
                        SettingsTab.Account -> AccountSettingsScreen(
                            state = state,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavRail(
    selectedTab: SettingsTab,
    modifier: Modifier = Modifier,
    onSelect: (SettingsTab) -> Unit,
) {
    Column(modifier = modifier.width(80.dp).padding(vertical = 8.dp)) {
        SettingsTab.entries.forEach { tab ->
            val icon = when (tab) {
                SettingsTab.Appearance -> Icons.Filled.Palette
                SettingsTab.Notifications -> Icons.Filled.Notifications
                SettingsTab.AIProvider -> Icons.Filled.SmartToy
                SettingsTab.WorkSchedule -> Icons.Filled.Schedule
                SettingsTab.Account -> Icons.Filled.AccountCircle
            }
            Column(
                modifier = Modifier
                    .clickable { onSelect(tab) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = tab.label)
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
