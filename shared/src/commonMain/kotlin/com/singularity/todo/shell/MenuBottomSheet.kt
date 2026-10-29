package com.singularity.todo.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.singularity.todo.core.ui.components.sheet.BottomSheetHost
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeUiState

/**
 * Bottom-sheet menu shown when the user taps the "Menu" bottom-bar item.
 *
 * It carries the destinations, the projects as a tree, and the way to the full
 * project list. The tree is drawn by [ProjectTreeSection], which the Desktop
 * drawer draws too — one tree, two drawings.
 *
 * Why a sheet and not a navigation destination (see reference plan §9):
 * - The user expects "today" to remain visible underneath the menu.
 * - Dismissing the sheet should not pop the back stack.
 * - It's presentation-only — no business state lives here.
 *
 * Stateless: receives `onDismiss` and `onSelect` callbacks from the caller.
 * The caller (`AndroidShell`) owns `menuVisible` as `rememberSaveable` state.
 *
 * Sections are declarative — adding a new section means adding one entry.
 *
 * The entries themselves come from [AppMenu], the model the Desktop drawer
 * draws too: to add a menu item, add it to `DestinationKind.menuEntries` and
 * it appears on both platforms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuBottomSheet(
    onDismiss: () -> Unit,
    currentDestination: AppDestination? = null,
    onSelect: (AppDestination) -> Unit,
    treeState: ProjectsTreeUiState,
    onToggleExpanded: (ProjectId) -> Unit,
    onOpenProject: (ProjectId) -> Unit,
) {
    // The partial state is skipped, and that is the fix for a real defect, not
    // a preference.
    //
    // The sheet opened at the half-height, and the menu is taller than that, so
    // the lower entries — Statistics, Notes, AI Chat, Search, Archive — were
    // drawn under the system navigation bar. A tap there never reached the
    // app: it hit the system HOME button and left it. Measured on the device
    // (720x1600): the sheet's content ran from y=820 down past y=2100, with
    // the navigation bar covering y=1504..1600 and the "Statistics" entry
    // sitting at y=1496..1600.
    //
    // The sheet is a menu, not a peeker: it opens to show what it holds. See
    // docs/decisions/2026-10-22-a-sheet-opens-to-what-it-holds.md.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    LaunchedEffect(Unit) { sheetState.show() }
    // The canonical sheet host, not a bare ModalBottomSheet: it applies the
    // window insets and asserts the testTag→resource-id mapping on the sheet's
    // own window.
    BottomSheetHost(
        onDismiss = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(TestTags.MENU_SHEET),
    ) {
        Column(
            // The sheet renders into its own window, so the app-root
            // testTagsAsResourceId never reaches the menu items.
            //
            // The column scrolls because the menu holds ten entries, the
            // projects tree and three headers, and a screen shorter than that
            // would clip the last ones again — the same defect the settings
            // rail had, and the reason the rail scrolls on every form factor.
            modifier = Modifier.mapTestTagsAsResourceIds()
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MenuSections.forEach { section ->
                SectionHeader(section.title)
                section.items.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.label) },
                        selected = item.destination == currentDestination,
                        onClick = { onSelect(item.destination) },
                        icon = item.iconContent(),
                        // Ensure testTagsAsResourceId reaches this item individually.
                        // mapTestTagsAsResourceIds() on the Column covers descendants, but
                        // NavigationDrawerItem may establish its own semantics scope.
                        // Applying it here too is idempotent and guarantees propagation.
                        modifier = Modifier
                            .fillMaxWidth()
                            .mapTestTagsAsResourceIds()
                            .testTag(item.id),
                    )
                }
                HorizontalDivider()
            }

            // ─── Projects ─────────────────────────────────────────────────────
            // The projects as a tree, and the way to the full list. The section
            // is drawn by the composable the Desktop drawer draws too.
            ProjectsMenuSection(
                state = treeState,
                onToggleExpanded = onToggleExpanded,
                onOpenProject = onOpenProject,
                onOpenAllProjects = { onSelect(AppDestination.ProjectsGraph()) },
                headerModifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            HorizontalDivider()
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
 * The sections are built from [AppMenu], the model both platforms draw, so the
 * sheet and the drawer offer the same entries.
 */
private data class MenuSection(val title: String, val items: List<MenuEntry>)

/**
 * The icon of the entry, or nothing.
 *
 * `null` hides the icon slot — the same convention Compose's own nullable
 * content uses.
 */
@Composable
private fun MenuEntry.iconContent(): (@Composable () -> Unit)? =
    icon?.let { icon -> { Icon(icon, contentDescription = null) } }

/** The sheet's sections, built from the shared [AppMenu]. */
private val MenuSections: List<MenuSection> = listOf(
    MenuSection(title = "Account", items = AppMenu.account),
    MenuSection(title = "Search", items = AppMenu.search),
    MenuSection(title = "Destinations", items = AppMenu.sheetDestinations),
)
