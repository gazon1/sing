package com.singularity.todo.core.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import kotlinx.coroutines.delay

/** State that opens a context menu at a given screen offset. */
data class ContextMenuOpenState(
    val offset: DpOffset,
)

/**
 * Generic context menu renderer.
 *
 * @param openState null = menu is hidden; non-null = menu is shown at [openState.offset]
 * @param onDismiss called when the user clicks outside the menu or presses Escape
 * @param entries the menu tree to display
 */
@Suppress("DEPRECATION")
@Composable
fun ContextMenuHost(
    openState: ContextMenuOpenState?,
    onDismiss: () -> Unit,
    entries: List<MenuNode>,
) {
    if (openState == null) return

    val density = LocalDensity.current
    val offsetPx = IntOffset(
        x = (openState.offset.x.value * density.density).toInt(),
        y = (openState.offset.y.value * density.density).toInt(),
    )

    Popup(
        alignment = Alignment.TopStart,
        offset = offsetPx,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        MenuPanel(
            entries = entries,
            onDismissAll = onDismiss,
        )
    }
}

@Composable
private fun MenuPanel(
    entries: List<MenuNode>,
    onDismissAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .padding(vertical = 4.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        entries.forEach { node ->
            MenuPanelItem(
                node = node,
                onDismissAll = onDismissAll,
            )
        }
    }
}

@Composable
private fun MenuPanelItem(
    node: MenuNode,
    onDismissAll: () -> Unit,
) {
    var isHovered by remember { mutableStateOf(false) }
    var openSubMenu by remember { mutableStateOf(false) }

    when (node) {
        is MenuNode.Action -> {
            ActionMenuRow(
                node = node,
                onClick = {
                    node.onClick()
                    onDismissAll()
                },
            )
        }

        MenuNode.Divider -> {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = TaskListColors.Divider,
            )
        }

        is MenuNode.SubMenu -> {
            SubMenuRow(
                node = node,
                isHovered = isHovered,
                onHoverChange = { isHovered = it },
                openSubMenu = openSubMenu,
                onOpenSubMenu = { openSubMenu = true },
                onDismissAll = onDismissAll,
            )
        }
    }
}

@Composable
private fun ActionMenuRow(
    node: MenuNode.Action,
    onClick: () -> Unit,
) {
    val textColor = if (node.enabled) {
        if (node.danger) TaskListColors.Danger else TaskListColors.TextPrimary
    } else {
        TaskListColors.TextSecondary.copy(alpha = 0.5f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(enabled = node.enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node.checked) {
                Text(
                    text = "✓",
                    color = TaskListColors.Accent,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = node.label,
                color = textColor,
                fontWeight = if (node.checked) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (node.shortcut != null) {
                Text(
                    text = node.shortcut,
                    color = TaskListColors.TextSecondary,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SubMenuRow(
    node: MenuNode.SubMenu,
    isHovered: Boolean,
    onHoverChange: (Boolean) -> Unit,
    openSubMenu: Boolean,
    onOpenSubMenu: () -> Unit,
    onDismissAll: () -> Unit,
) {
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(300L)
            onOpenSubMenu()
        }
    }

    // Track this row's bounds in window coordinates for submenu positioning.
    var rowBounds by remember { mutableStateOf(Rect.Zero) }

    val textColor = if (node.enabled) TaskListColors.TextPrimary else TaskListColors.TextSecondary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 8.dp)
            .onGloballyPositioned { rowBounds = it.boundsInWindow() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = node.label,
                color = textColor,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "▶",
                color = TaskListColors.TextSecondary,
            )
        }
    }

    if (openSubMenu && node.children.isNotEmpty()) {
        val density = LocalDensity.current
        // Position submenu immediately to the right of this row, aligned to its top edge.
        // rowBounds.right is in pixels; subtract 1 to avoid 1px overlap with the row.
        val subOffsetX = (rowBounds.right - 1).toInt()
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(subOffsetX, rowBounds.top.toInt()),
            onDismissRequest = onDismissAll,
            properties = PopupProperties(focusable = false),
        ) {
            MenuPanel(
                entries = node.children,
                onDismissAll = onDismissAll,
            )
        }
    }
}
