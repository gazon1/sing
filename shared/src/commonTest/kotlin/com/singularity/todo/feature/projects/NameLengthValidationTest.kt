package com.singularity.todo.feature.projects

import com.singularity.todo.core.text.visibleLength
import com.singularity.todo.feature.projects.domain.ProjectsDomain
import com.singularity.todo.feature.projects.domain.model.CreateProjectInput
import com.singularity.todo.feature.tags.domain.TagDomain
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Name-length limits are read by people as "characters". [String.length] counts
 * UTF-16 units, so an emoji outside the BMP is charged twice and a name the user
 * reads as 25 characters fails a 50-character limit whose own message says it passes.
 *
 * These pin the exact boundary, because the defect only exists *at* the boundary.
 */
@Tag("fast")
class NameLengthValidationTest {

    /** U+1F389 — four UTF-16 units, one visible character. */
    private val partyEmoji = "🎉"

    private fun input(name: String) = CreateProjectInput(name = name, color = 0xFF2196F3.toInt())

    // ─── The count itself ──────────────────────────────────────────────────────

    @Test
    fun `visibleLength counts an astral-plane emoji once`() {
        assertEquals(1, partyEmoji.visibleLength())
    }

    @Test
    fun `visibleLength charges an emoji twice that String length does`() {
        val name = partyEmoji.repeat(25)
        assertEquals(50, name.length, "precondition: String.length sees 50 UTF-16 units")
        assertEquals(25, name.visibleLength())
    }

    // ─── Project boundary: 50 ──────────────────────────────────────────────────

    @Test
    fun `project name of 50 ascii characters is accepted`() {
        assertNull(ProjectsDomain.validateName("a".repeat(50)))
    }

    @Test
    fun `project name of 51 ascii characters is rejected`() {
        assertNotNull(ProjectsDomain.validateName("a".repeat(51)))
    }

    @Test
    fun `project name of 50 emoji is accepted`() {
        assertNull(ProjectsDomain.validateName(partyEmoji.repeat(50)))
    }

    @Test
    fun `project name of 51 emoji is rejected`() {
        val error = ProjectsDomain.validateName(partyEmoji.repeat(51))
        assertNotNull(error)
        assertEquals("project.name.too_long", error.code)
    }

    @Test
    fun `25 emoji measures 50 utf-16 units and was rejected before the fix`() {
        // The exact case the old `name.length > 50` check got wrong: 50 units,
        // 25 visible characters, accepted under a limit stated in characters.
        assertEquals(50, partyEmoji.repeat(25).length)
        assertNull(ProjectsDomain.validateName(partyEmoji.repeat(25)))
    }

    // ─── Tag boundary: 100 ─────────────────────────────────────────────────────

    @Test
    fun `tag name of 100 ascii characters is accepted`() {
        assertNull(TagDomain.validateName("a".repeat(100)))
    }

    @Test
    fun `tag name of 101 ascii characters is rejected`() {
        assertNotNull(TagDomain.validateName("a".repeat(101)))
    }

    @Test
    fun `tag name of 100 emoji is accepted`() {
        assertNull(TagDomain.validateName(partyEmoji.repeat(100)))
    }

    @Test
    fun `tag name of 101 emoji is rejected`() {
        val error = TagDomain.validateName(partyEmoji.repeat(101))
        assertNotNull(error)
        assertEquals("tag.name.too_long", error.code)
    }

    // ─── Blank still wins over length ──────────────────────────────────────────

    @Test
    fun `blank name reports blank rather than too long`() {
        assertEquals("project.name.blank", ProjectsDomain.validateName("   ")?.code)
        assertEquals("tag.name.blank", TagDomain.validateName("")?.code)
    }

    // ─── The message states the limit the rule actually applies ────────────────

    @Test
    fun `too-long message quotes the shared limit`() {
        val error = ProjectsDomain.validateName(partyEmoji.repeat(51))
        assertEquals(
            "Name too long (max ${ProjectsDomain.MAX_NAME_LENGTH} characters)",
            error?.message,
        )
    }

    // ─── W4: one rule, not two ────────────────────────────────────────────────

    @Test
    fun `create-input validation agrees with the name rule`() {
        // The write path composes the name rule rather than restating it, so the
        // editor and the domain cannot disagree about what a valid name is.
        assertFalse(ProjectsDomain.validateCreateInput(input("a".repeat(50))).isLeft)
        assertTrue(ProjectsDomain.validateCreateInput(input("a".repeat(51))).isLeft)
        assertFalse(ProjectsDomain.validateCreateInput(input(partyEmoji.repeat(50))).isLeft)
        assertTrue(ProjectsDomain.validateCreateInput(input(partyEmoji.repeat(51))).isLeft)
    }
}
