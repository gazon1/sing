package com.singularity.todo.core.ui.menu

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.Test

/**
 * Desktop JVM smoke tests for [MenuBarHost].
 *
 * [MenuBarHost] is currently a stub because Material 2 `MenuBar` is not available
 * in the current Compose Multiplatform version. These tests verify the stub
 * renders without crashing.
 *
 * When a proper MenuBar implementation is added, expand these tests to verify
 * File / Edit / View / Help submenus and the quit shortcut.
 */
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
    fun menu_bar_host_renders_without_crash() = runDesktopComposeUiTest {
        setContent {
            MenuBarHost(entries = buildSampleMenu())
        }
        // Stub renders nothing — no assertion needed beyond no crash.
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun menu_bar_host_with_empty_entries_renders() = runDesktopComposeUiTest {
        setContent {
            MenuBarHost(entries = emptyList())
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun menu_bar_host_with_deeply_nested_submenus_renders() = runDesktopComposeUiTest {
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
            MenuBarHost(entries = deep)
        }
    }
}
