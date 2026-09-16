package com.singularity.todo.core.ui.menu

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A node in a desktop menu — either an action, a sub-menu, or a divider.
 * This is the generic, platform-independent model used by both context menus
 * and the window menu bar.
 */
sealed class MenuNode {
    abstract val id: String
    abstract val enabled: Boolean

    data class Action(
        override val id: String,
        val label: String,
        val icon: ImageVector? = null,
        val shortcut: String? = null,
        val danger: Boolean = false,
        val checked: Boolean = false,
        override val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : MenuNode()

    data class SubMenu(
        override val id: String,
        val label: String,
        val icon: ImageVector? = null,
        override val enabled: Boolean = true,
        val children: List<MenuNode>,
    ) : MenuNode()

    /** Menu separator. The id is fixed so callers don't need to supply one. */
    data object Divider : MenuNode() {
        override val id: String = "__divider__"
        override val enabled: Boolean = true
    }
}
