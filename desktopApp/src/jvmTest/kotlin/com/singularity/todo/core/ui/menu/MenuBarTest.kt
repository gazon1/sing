package com.singularity.todo.core.ui.menu

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop JVM smoke tests for [AwtMenuBarInstaller].
 *
 * [AwtMenuBarInstaller] installs a native AWT menu bar on the current [java.awt.Frame].
 * In tests (no real AWT window), [LocalAwtWindow] returns null and the function
 * early-returns — verifying no crash in this path.
 *
 * Integration tests with a real window would verify File / Edit / View / Help
 * submenus and the Ctrl+Q quit shortcut.
 */
@Tag("fast")
class MenuBarTest {

    private fun buildSampleMenu() = buildMenuNodes {
        subMenu("file", "File", children = buildMenuNodes {
            item("new_task", "New Task", shortcut = "Ctrl+N") {}
            item("settings", "Settings…", shortcut = "Ctrl+,") {}
            divider()
            item("quit", "Quit", shortcut = "Ctrl+Q") {}
        })
        subMenu("help", "Help", children = buildMenuNodes {
            item("about", "About") {}
        })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun menu_bar_installer_renders_without_crash() = runDesktopComposeUiTest {
        setContent {
            AwtMenuBarInstaller(entries = buildSampleMenu())
        }
        // No real AWT Frame in test — verifies early-return path, no crash.
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun menu_bar_installer_with_empty_entries_renders() = runDesktopComposeUiTest {
        setContent {
            AwtMenuBarInstaller(entries = emptyList())
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun menu_bar_installer_with_deeply_nested_submenus_renders() = runDesktopComposeUiTest {
        val deep = buildMenuNodes {
            subMenu("l1", "Level 1", children = buildMenuNodes {
                subMenu("l2", "Level 2", children = buildMenuNodes {
                    subMenu("l3", "Level 3", children = buildMenuNodes {
                        item("action", "Deep Action") {}
                    })
                })
            })
        }
        setContent {
            AwtMenuBarInstaller(entries = deep)
        }
    }
}
