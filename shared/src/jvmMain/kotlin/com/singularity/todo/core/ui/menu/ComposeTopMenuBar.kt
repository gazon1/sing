package com.singularity.todo.core.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Compose-rendered top menu bar.
 *
 * Replaces AWT MenuBar for consistent visual styling with the rest of the app
 * (Material3 theme + dark mode). Uses the same [MenuNode] DSL as the context menu.
 *
 * IMPORTANT: each interactive element uses its OWN [MutableInteractionSource].
 * Sharing one between hoverable and clickable on the same modifier chain can
 * suppress click events on Compose 1.12.
 */
@Composable
fun ComposeTopMenuBar(entries: List<MenuNode>, modifier: Modifier = Modifier) {
    var openMenuId by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEach { node ->
                when (node) {
                    is MenuNode.SubMenu -> TopMenuBarItem(
                        node = node,
                        isOpen = openMenuId == node.id,
                        onOpenChange = { open -> openMenuId = if (open) node.id else null },
                        onDismiss = { openMenuId = null },
                    )

                    is MenuNode.Action -> TopMenuBarAction(node)

                    MenuNode.Divider -> { /* skip dividers at top level */ }
                }
            }
        }
    }
}

@Composable
private fun TopMenuBarItem(
    node: MenuNode.SubMenu,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val hover = rememberHoverOpenState(
        delayMs = 200L,
        isOpen = isOpen,
        onOpen = { onOpenChange(true) },
    )

    Box(
        modifier = Modifier
            .hoverable(interactionSource = hover.interactionSource, enabled = node.enabled)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (hover.isHovered || isOpen) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = node.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (node.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            },
            maxLines = 1,
        )
    }

    if (isOpen && node.children.isNotEmpty()) {
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(0, 36),
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            DropdownPanel {
                node.children.forEach { child ->
                    TopMenuDropdownItem(node = child, onAction = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun TopMenuBarAction(node: MenuNode.Action) {
    val hoverSource = remember { MutableInteractionSource() }
    val clickSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .clickable(
                interactionSource = clickSource,
                indication = null,
                enabled = node.enabled,
                onClick = node.onClick,
            )
            .hoverable(interactionSource = hoverSource, enabled = node.enabled)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = node.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (node.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun DropdownPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.width(220.dp).padding(vertical = 6.dp), content = content)
    }
}

@Composable
private fun TopMenuDropdownItem(node: MenuNode, onAction: () -> Unit) {
    when (node) {
        is MenuNode.Action -> DropdownActionRow(node, onAction)

        is MenuNode.SubMenu -> DropdownSubMenuRow(node, onAction)

        MenuNode.Divider -> HorizontalDivider(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun DropdownActionRow(node: MenuNode.Action, onAction: () -> Unit) {
    val hoverSource = remember { MutableInteractionSource() }
    val clickSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = clickSource,
                enabled = node.enabled,
                onClick = {
                    node.onClick()
                    onAction()
                },
            )
            .hoverable(interactionSource = hoverSource)
            .background(if (isHovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node.checked) {
                Text(
                    text = "✓",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = node.label,
                color = if (node.danger) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (node.checked) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (node.shortcut != null) {
                Text(
                    text = node.shortcut,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun DropdownSubMenuRow(node: MenuNode.SubMenu, onDismiss: () -> Unit) {
    var openSub by remember { mutableStateOf(false) }
    val hover = rememberHoverOpenState(
        delayMs = 300L,
        isOpen = openSub,
        onOpen = { openSub = true },
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource = hover.interactionSource, enabled = node.enabled)
            .background(if (hover.isHovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = node.label,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "▶",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }

    if (openSub && node.children.isNotEmpty()) {
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(220, 0),
            onDismissRequest = { openSub = false },
            properties = PopupProperties(focusable = true),
        ) {
            DropdownPanel {
                node.children.forEach { child ->
                    TopMenuDropdownItem(node = child, onAction = {
                        openSub = false
                        onDismiss()
                    })
                }
            }
        }
    }
}
