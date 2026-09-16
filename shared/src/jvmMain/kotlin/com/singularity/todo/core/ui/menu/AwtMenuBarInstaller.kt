package com.singularity.todo.core.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.LocalAwtWindow
import java.awt.CheckboxMenuItem
import java.awt.Frame
import java.awt.Menu
import java.awt.MenuBar
import java.awt.MenuItem
import java.awt.MenuShortcut

/**
 * Installs a native AWT menu bar on the current [java.awt.Frame] window.
 *
 * Called from [DesktopShellNav3Root] on the JVM. The menu bar appears in the
 * OS-native window chrome (GTK on Linux, Win32 on Windows, macOS Aqua everywhere).
 *
 * Uses [LocalAwtWindow] which is set by [androidx.compose.ui.awt.ComposeWindow]
 * during composition — available inside any Composable called within
 * [androidx.compose.ui.window.singleWindowApplication].
 *
 * ## AWT constraint
 *
 * `java.awt.MenuBar.add` accepts only [java.awt.Menu] (not [java.awt.MenuItem]).
 * Top-level [MenuNode.Action] nodes are therefore wrapped in a hidden parent
 * [java.awt.Menu] — but our DSL has none of those at the top level. So in practice
 * top-level entries must be [MenuNode.SubMenu]; if any bare Action sneaks in we
 * wrap it in a single-item Menu with the action's label as the Menu title.
 *
 * @param entries the menu tree built with [buildMenuNodes]
 * @see buildMenuNodes
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AwtMenuBarInstaller(entries: List<MenuNode>) {
    val window = LocalAwtWindow.current as? Frame ?: return
    val menuBar = remember(entries) { buildAwtMenuBar(entries) }
    LaunchedEffect(menuBar) {
        window.menuBar = menuBar
    }
}

/** Builds a native AWT [MenuBar] from a [List<MenuNode>] tree. */
private fun buildAwtMenuBar(nodes: List<MenuNode>): MenuBar {
    val menuBar = MenuBar()
    for (node in nodes) {
        // AWT MenuBar.add only accepts java.awt.Menu — wrap bare top-level
        // Actions into a single-item Menu so they can still appear in the bar.
        val menu: Menu = when (node) {
            is MenuNode.Action -> Menu(node.label).apply {
                add(node.toAwtMenuItem())
            }
            is MenuNode.SubMenu -> node.toAwtMenu()
            MenuNode.Divider -> continue
        }
        menuBar.add(menu)
    }
    return menuBar
}

private fun MenuNode.SubMenu.toAwtMenu(): Menu {
    val menu = Menu(label)
    menu.isEnabled = enabled
    for (child in children) {
        when (child) {
            is MenuNode.Action -> menu.add(child.toAwtMenuItem())
            is MenuNode.SubMenu -> menu.add(child.toAwtMenu())
            MenuNode.Divider -> menu.addSeparator()
        }
    }
    return menu
}

private fun MenuNode.Action.toAwtMenuItem(): MenuItem {
    val item = if (checked) {
        CheckboxMenuItem(label, checked)
    } else {
        MenuItem(label)
    }
    item.isEnabled = enabled
    shortcut?.toMenuShortcut()?.let { item.shortcut = it }
    item.addActionListener { onClick() }
    return item
}

private fun String.toMenuShortcut(): MenuShortcut? {
    // Format: "Ctrl+N", "Ctrl+Shift+N", "Meta+S", "F5", etc.
    val parts = this.split("+")
    if (parts.isEmpty()) return null
    val keyCodeStr = parts.last()
    var shiftNeeded = false
    for (modifier in parts.dropLast(1)) {
        when (modifier.trim()) {
            "Ctrl", "Control" -> { /* MenuShortcut handles Ctrl automatically */ }
            "Shift" -> shiftNeeded = true
            "Alt", "Meta" -> { /* AWT MenuShortcut doesn't support Alt/Meta */ }
        }
    }
    val keyCode = keyCodeStr.uppercase().codePoints().findFirst().orElse(0)
    if (keyCode == 0) return null
    return MenuShortcut(keyCode, shiftNeeded)
}
