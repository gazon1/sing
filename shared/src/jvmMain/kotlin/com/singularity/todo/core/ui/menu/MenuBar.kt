@file:Suppress("FunctionNaming")

package com.singularity.todo.core.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop window menu bar — replaced by [AwtMenuBarInstaller].
 *
 * @see AwtMenuBarInstaller
 */
@Deprecated(
    message = "Replaced by AwtMenuBarInstaller",
    replaceWith = ReplaceWith("AwtMenuBarInstaller(entries)"),
)
@Composable
fun MenuBarHost(entries: List<MenuNode>, modifier: Modifier = Modifier) {
    // Deprecated — body removed. AwtMenuBarInstaller is the replacement.
}
