package com.singularity.todo.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.exposeTestTagsAsResourceId
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.nav.Search
import com.singularity.todo.feature.nav.icon

/**
 * Bottom-sheet menu shown when the user taps the "Menu" bottom-bar item.
 *
 * Why a sheet and not a navigation destination (see reference plan §9):
 * - The user expects "today" to remain visible underneath the menu.
 * - Dismissing the sheet should not pop the back stack.
 * - It's presentation-only — no business state lives here.
 *
 * Stateless: receives `onDismiss` and `onSelect` callbacks from the caller.
 * The caller (`AndroidShell`) owns `menuVisible` as `rememberSaveable` state.
 *
 * Sections are declarative — adding a new section means adding one entry
 *
 * To add new menu items
 * to the "Destinations" group, just append to [DestinationKind.menuEntries].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuBottomSheet(onDismiss: () -> Unit, onSelect: (AppDestination) -> Unit) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    LaunchedEffect(Unit) { sheetState.show() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(TestTags.MENU_SHEET),
    ) {
        Column(
            // The sheet renders into its own window, so the app-root
            // testTagsAsResourceId never reaches the menu items.
            modifier = Modifier.exposeTestTagsAsResourceId()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MenuSections.forEach { section ->
                SectionHeader(section.title)
                section.items.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.label) },
                        selected = false,
                        onClick = { onSelect(item.destination) },
                        icon = item.iconContent(),
                        modifier = Modifier.fillMaxWidth()
                            .testTag(TestTags.menuItem(item.label)),
                    )
                }
                HorizontalDivider()
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * Declarative section definition. Each item either has a [destination]
 * to navigate to (via `onSelect`) or [icon] to display.
 *
 * When you add a new destination to `AppDestination`, append it to
 * [DestinationKind.menuEntries] and it shows up automatically.
 */
private data class MenuSection(val title: String, val items: List<MenuItem>)

private data class MenuItem(
    val label: String,
    val destination: AppDestination,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    /**
     * Returns a non-null composable lambda for the [NavigationDrawerItem.icon]
     * slot, or `null` if the item has no icon. Returning `null` hides the
     * icon slot — same as Compose's own nullable-content convention.
     */
    fun iconContent(): (@Composable () -> Unit)? = icon?.let { icon -> { Icon(icon, contentDescription = null) } }
}

/** Static menu structure — Account/Search are placeholder sections for now. */
private val MenuSections: List<MenuSection> = listOf(
    MenuSection(
        title = "Account",
        items = listOf(
            MenuItem(label = "Profile & sync", destination = AppDestination.Settings),
            MenuItem(
                label = AppDestination.ProfileSwitcher.title,
                destination = AppDestination.ProfileSwitcher,
                icon = AppDestination.ProfileSwitcher.icon,
            ),
        ),
    ),
    MenuSection(
        title = "Search",
        items = listOf(
            MenuItem(label = "Quick search", destination = AppDestination.Search),
        ),
    ),
    MenuSection(
        title = "Destinations",
        items = DestinationKind.menuEntries.map { dest ->
            MenuItem(label = dest.title, destination = dest, icon = dest.icon)
        },
    ),
)
