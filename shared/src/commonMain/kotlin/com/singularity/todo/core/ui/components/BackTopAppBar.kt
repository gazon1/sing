package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.todo.core.ui.preview.PreviewThemed

/**
 * Scaffold + TopAppBar with a back-arrow navigation icon.
 *
 * Replaces the `Scaffold(topBar = { TopAppBar(navigationIcon = { IconButton(ArrowBack) }) })`
 * pattern that was copy-pasted across 6 screens.
 *
 * @param title    Screen title displayed in the center.
 * @param onBack   Called when the back arrow is tapped.
 * @param modifier Standard Compose modifier.
 * @param actions  Optional trailing actions (e.g. save icon). Empty by default.
 * @param content  The screen body. Receives the scaffold padding; most screens pass it through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopAppBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit = { },
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                modifier = modifier,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = { actions() },
            )
        },
    ) { paddingValues ->
        content(paddingValues)
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun BackTopAppBarLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    BackTopAppBar(
        title = "Task Detail",
        onBack = {},
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {}
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun BackTopAppBarDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    BackTopAppBar(
        title = "Project",
        onBack = {},
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {}
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun BackTopAppBarPurpleDarkPreview() = PreviewThemed(
    darkTheme = true,
    accent = com.singularity.todo.core.ui.theme.SingularityAccents.Purple,
    useSurface = false,
) {
    BackTopAppBar(
        title = "Settings",
        onBack = {},
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {}
    }
}
