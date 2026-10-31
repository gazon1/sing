package com.singularity.todo.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.Navigator

/**
 * Global search action shared by the Android and Desktop shell app bars.
 *
 * Hidden by callers when the current top-level destination is already
 * [AppDestination.Search], so the icon does not navigate to the screen it
 * is already on.
 */
@Composable
fun ShellSearchButton(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    contentDescription: String = "Search",
) {
    IconButton(
        onClick = { navigator.open(AppDestination.Search) },
        modifier = modifier.testTag(TestTags.SHELL_SEARCH_BUTTON),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = contentDescription,
        )
    }
}
