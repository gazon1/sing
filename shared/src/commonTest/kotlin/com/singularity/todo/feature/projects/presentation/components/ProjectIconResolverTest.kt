package com.singularity.todo.feature.projects.presentation.components

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("fast")
class ProjectIconResolverTest {

    @Test
    fun `null or blank resolves to None`() {
        assertEquals(ResolvedProjectIcon.None, resolveProjectIcon(null))
        assertEquals(ResolvedProjectIcon.None, resolveProjectIcon(""))
        assertEquals(ResolvedProjectIcon.None, resolveProjectIcon("   "))
    }

    @Test
    fun `registered key resolves to Material`() {
        val resolved = resolveProjectIcon("Work")
        assertTrue(resolved is ResolvedProjectIcon.Material)
        assertEquals("Work", (resolved as ResolvedProjectIcon.Material).key)
    }

    @Test
    fun `unknown string resolves to Emoji`() {
        val resolved = resolveProjectIcon("🚀")
        assertTrue(resolved is ResolvedProjectIcon.Emoji)
        assertEquals("🚀", (resolved as ResolvedProjectIcon.Emoji).value)
    }
}
