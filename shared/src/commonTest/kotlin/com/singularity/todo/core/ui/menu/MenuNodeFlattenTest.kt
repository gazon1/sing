package com.singularity.todo.core.ui.menu

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests that a flatten utility correctly counts all nodes in a menu tree.
 *
 * The flatten function is a pure recursive traversal — tested here so any
 * refactor of the tree structure is caught by the test suite.
 */
class MenuNodeFlattenTest {

    private fun flatten(nodes: List<MenuNode>): List<MenuNode> = buildList {
        for (node in nodes) {
            add(node)
            if (node is MenuNode.SubMenu) {
                addAll(flatten(node.children))
            }
        }
    }

    @Test
    fun `flatten empty list`() {
        assertEquals(emptyList<MenuNode>(), flatten(emptyList()))
    }

    @Test
    fun `flatten single action`() {
        val nodes = buildMenuNodes { item("a", "Alpha") {} }
        assertEquals(nodes, flatten(nodes))
    }

    @Test
    fun `flatten divider is included`() {
        val nodes = buildMenuNodes { divider() }
        assertEquals(nodes, flatten(nodes))
    }

    @Test
    fun `flatten counts all nodes including nested children`() {
        val children = buildMenuNodes {
            item("ai_1", "Refine") {}
            item("ai_2", "Generate") {}
            item("ai_3", "Decompose") {}
        }
        val nodes = buildMenuNodes {
            item("1", "One") {}
            item("2", "Two") {}
            subMenu("ai", "AI", children = children)
            divider()
            item("3", "Three") {}
        }
        // 3 items + 1 submenu + 1 divider = 5 top-level; 3 nested children
        assertEquals(8, flatten(nodes).size)
    }

    @Test
    fun `flatten deep nesting`() {
        val nodes = buildMenuNodes {
            subMenu(
                "l1", "Level 1",
                children = buildMenuNodes {
                subMenu(
                    "l2", "Level 2",
                    children = buildMenuNodes {
                    subMenu(
                        "l3", "Level 3",
                        children = buildMenuNodes {
                        item("deep", "Deepest") {}
                    }
                    )
                }
                )
            }
            )
        }
        // 1 (l1) + 1 (l2) + 1 (l3) + 1 (deepest) = 4
        assertEquals(4, flatten(nodes).size)
    }

    @Test
    fun `flatten with multiple submenus`() {
        val nodes = buildMenuNodes {
            subMenu(
                "a", "A",
                children = buildMenuNodes {
                item("a1", "A1") {}
                item("a2", "A2") {}
            }
            )
            subMenu(
                "b", "B",
                children = buildMenuNodes {
                item("b1", "B1") {}
            }
            )
        }
        // a + a1 + a2 + b + b1 = 5
        assertEquals(5, flatten(nodes).size)
    }
}
