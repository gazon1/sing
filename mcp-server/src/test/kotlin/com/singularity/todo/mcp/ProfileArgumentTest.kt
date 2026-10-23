package com.singularity.todo.mcp

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Which profile the tool server was told to work as.
 *
 * Two arguments, two meanings, and one rule that matters more than either: the legacy
 * label keeps its meaning, because client manifests in the wild pass it, and an id
 * nobody knows is an error rather than a quiet fall back to somebody else's data.
 */
@Tag("fast")
class ProfileArgumentTest {

    @Test
    fun `no argument means the stored profile`() {
        assertIs<ProfileSelection.Stored>(parseProfileSelection(emptyArray()))
    }

    @Test
    fun `a profile id is a fixed id`() {
        val selection = parseProfileSelection(arrayOf("--profile-id=abc-123"))

        assertEquals(ProfileSelection.FixedId("abc-123"), selection)
    }

    @Test
    fun `the legacy label keeps its meaning`() {
        val selection = parseProfileSelection(arrayOf("--profile=ai-agent"))

        assertEquals(ProfileSelection.LegacyLabel("ai-agent"), selection)
    }

    @Test
    fun `both arguments are ambiguous`() {
        val selection = parseProfileSelection(arrayOf("--profile=ai-agent", "--profile-id=abc-123"))

        assertEquals(ProfileSelection.Ambiguous("ai-agent", "abc-123"), selection)
    }

    @Test
    fun `an unrelated argument is not a profile`() {
        assertIs<ProfileSelection.Stored>(parseProfileSelection(arrayOf("--other=1")))
    }

    @Test
    fun `a label that only looks like a label is not one`() {
        // "--profile-id" must not be read as the label "--id".
        val selection = parseProfileSelection(arrayOf("--profile-id=abc-123"))

        assertIs<ProfileSelection.FixedId>(selection)
    }
}
