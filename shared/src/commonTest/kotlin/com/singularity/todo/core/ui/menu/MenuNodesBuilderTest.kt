package com.singularity.todo.core.ui.menu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MenuNodesBuilderTest {

    @Test
    fun `item adds Action node`() {
        val list = buildMenuNodes {
            item(id = "a", label = "Alpha", onClick = {})
        }
        assertEquals(1, list.size)
        val node = list[0] as MenuNode.Action
        assertEquals("a", node.id)
        assertEquals("Alpha", node.label)
        assertTrue(node.enabled)
        assertFalse(node.danger)
        assertFalse(node.checked)
    }

    @Test
    fun `item with all parameters`() {
        val list = buildMenuNodes {
            item(
                id = "b",
                label = "Bravo",
                shortcut = "Ctrl+B",
                danger = true,
                checked = true,
                enabled = false,
                onClick = {},
            )
        }
        val node = list[0] as MenuNode.Action
        assertEquals("b", node.id)
        assertEquals("Ctrl+B", node.shortcut)
        assertTrue(node.danger)
        assertTrue(node.checked)
        assertFalse(node.enabled)
    }

    @Test
    fun `divider adds Divider node`() {
        val list = buildMenuNodes { divider() }
        assertEquals(1, list.size)
        assertSame(MenuNode.Divider, list[0])
    }

    @Test
    fun `subMenu adds SubMenu node with children`() {
        val child = buildMenuNodes { item("c1", "Child") {} }
        val list = buildMenuNodes {
            subMenu(id = "s", label = "Sub", children = child)
        }
        assertEquals(1, list.size)
        val sub = list[0] as MenuNode.SubMenu
        assertEquals("s", sub.id)
        assertEquals("Sub", sub.label)
        assertEquals(1, sub.children.size)
    }

    @Test
    fun `subMenu can nest buildMenuNodes as children`() {
        val list = buildMenuNodes {
            subMenu("s", "Sub", children = buildMenuNodes {
                item("nested", "Nested") {}
            })
        }
        val sub = list[0] as MenuNode.SubMenu
        assertEquals(1, sub.children.size)
    }

    @Test
    fun `subMenu trailing lambda overload`() {
        val list = buildMenuNodes {
            subMenu("s", "Sub") {
                item("child", "Child") {}
                divider()
                item("another", "Another") {}
            }
        }
        assertEquals(1, list.size)
        val sub = list[0] as MenuNode.SubMenu
        assertEquals(3, sub.children.size) // item + divider + item
    }

    @Test
    fun `mixed items and dividers`() {
        val list = buildMenuNodes {
            item("a", "A") {}
            divider()
            item("b", "B") {}
            divider()
            item("c", "C") {}
        }
        assertEquals(5, list.size)
        assertEquals("a", (list[0] as MenuNode.Action).id)
        assertSame(MenuNode.Divider, list[1])
        assertEquals("b", (list[2] as MenuNode.Action).id)
        assertSame(MenuNode.Divider, list[3])
        assertEquals("c", (list[4] as MenuNode.Action).id)
    }

    @Test
    fun `build returns immutable List`() {
        val list1 = buildMenuNodes { item("x", "X") {} }
        val list2 = buildMenuNodes { item("y", "Y") {} }
        assertEquals(1, list1.size)
        assertEquals(1, list2.size)
        assertEquals("x", (list1[0] as MenuNode.Action).id)
        assertEquals("y", (list2[0] as MenuNode.Action).id)
    }
}
