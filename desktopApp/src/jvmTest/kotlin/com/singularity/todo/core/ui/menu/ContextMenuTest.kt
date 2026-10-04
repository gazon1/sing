@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.core.ui.menu

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop JVM smoke tests for [ContextMenuHost].
 *
 * Popup-based menus open in an overlay window on Desktop which can be tricky to
 * test in a headless environment. These tests verify the composable renders
 * without crashing for both hidden (null) and shown (non-null) states.
 */
@Tag("fast")
class ContextMenuTest {

    private fun buildSampleEntries() = buildMenuNodes {
        item("pin", "Pin", checked = false) {}
        item("complete", "Mark as completed", checked = false) {}
        divider()
        subMenu("ai", "AI Actions", children = buildMenuNodes {
            item("ai_refine", "Improve with SMART") {}
            item("ai_gen", "Generate description") {}
        })
        divider()
        item("delete", "Delete", danger = true) {}
    }

    @Test
    fun context_menu_host_with_null_state_renders_nothing() = runDesktopComposeUiTest {
        setContent {
            ContextMenuHost(
                openState = null,
                onDismiss = {},
                entries = buildSampleEntries(),
            )
        }
        // null state = hidden; no UI rendered.
    }

    @Test
    fun context_menu_host_with_open_state_does_not_crash() = runDesktopComposeUiTest {
        setContent {
            ContextMenuHost(
                openState = ContextMenuOpenState(DpOffset(100.dp, 200.dp)),
                onDismiss = {},
                entries = buildSampleEntries(),
            )
        }
        // Smoke test: verifies no crash when menu is open.
    }

    @Test
    fun context_menu_host_with_empty_entries_does_not_crash() = runDesktopComposeUiTest {
        setContent {
            ContextMenuHost(
                openState = ContextMenuOpenState(DpOffset(0.dp, 0.dp)),
                onDismiss = {},
                entries = emptyList(),
            )
        }
    }

    @Test
    fun context_menu_host_with_nested_submenus_does_not_crash() = runDesktopComposeUiTest {
        val nested = buildMenuNodes {
            subMenu("move", "Move to", children = buildMenuNodes {
                subMenu("project", "Project", children = buildMenuNodes {
                    item("proj_a", "Project A") {}
                    item("proj_b", "Project B") {}
                })
                item("inbox", "Inbox") {}
            })
        }
        setContent {
            ContextMenuHost(
                openState = ContextMenuOpenState(DpOffset(50.dp, 50.dp)),
                onDismiss = {},
                entries = nested,
            )
        }
    }
}
