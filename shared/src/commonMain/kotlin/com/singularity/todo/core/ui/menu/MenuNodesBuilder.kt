package com.singularity.todo.core.ui.menu

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * DSL builder for a flat list of [MenuNode]s.
 *
 * Usage:
 * ```
 * buildMenuNodes {
 *     item("id", "Label", onClick = { ... })
 *     divider()
 *     subMenu("sub", "Sub menu", children = buildMenuNodes { ... })
 * }
 * ```
 *
 * The `@DslMarker` ensures that only the builder's methods are in scope inside
 * the lambda — outer scope receivers (e.g. Composables) cannot accidentally shadow
 * `item`/`subMenu`/`divider`.
 *
 *  @see buildMenuNodes
 */
@DslMarker
annotation class MenuDsl

@MenuDsl
class MenuNodesBuilder {
    private val items = mutableListOf<MenuNode>()

    fun item(
        id: String,
        label: String,
        icon: ImageVector? = null,
        shortcut: String? = null,
        danger: Boolean = false,
        checked: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        items += MenuNode.Action(
            id = id,
            label = label,
            icon = icon,
            shortcut = shortcut,
            danger = danger,
            checked = checked,
            enabled = enabled,
            onClick = onClick,
        )
    }

    fun divider() {
        items += MenuNode.Divider
    }

    fun subMenu(
        id: String,
        label: String,
        icon: ImageVector? = null,
        enabled: Boolean = true,
        children: List<MenuNode>,
    ) {
        items += MenuNode.SubMenu(
            id = id,
            label = label,
            icon = icon,
            enabled = enabled,
            children = children,
        )
    }

    /** Trailing lambda overload — convenient DSL form for nested submenus. */
    fun subMenu(
        id: String,
        label: String,
        icon: ImageVector? = null,
        enabled: Boolean = true,
        block: MenuNodesBuilder.() -> Unit,
    ) {
        items += MenuNode.SubMenu(
            id = id,
            label = label,
            icon = icon,
            enabled = enabled,
            children = buildMenuNodes(block),
        )
    }

    /** Returns an immutable snapshot of the menu built so far. */
    internal fun build(): List<MenuNode> = items.toList()
}

/**
 * Entry point for the menu DSL. Call this from feature code to build a
 * `List<MenuNode>`.
 *
 * @param block the DSL lambda — calls [MenuNodesBuilder.item],
 *              [MenuNodesBuilder.divider], and [MenuNodesBuilder.subMenu]
 */
fun buildMenuNodes(block: MenuNodesBuilder.() -> Unit): List<MenuNode> = MenuNodesBuilder().apply(block).build()
