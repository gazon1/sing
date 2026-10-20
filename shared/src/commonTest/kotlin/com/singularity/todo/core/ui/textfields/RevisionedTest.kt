package com.singularity.todo.core.ui.textfields

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The count of a value's replacements, and the only two ways a value changes.
 *
 * The type exists so that an owner cannot replace a value and leave the count where it
 * was: the count travels inside the value, and it moves only when the owner says the
 * value was replaced.
 */
@Tag("fast")
class RevisionedTest {

    @Test
    fun `a value that nothing replaced carries no count`() {
        val value = Revisioned.initial("first")

        assertEquals("first", value.value)
        assertEquals(0L, value.revision)
    }

    @Test
    fun `a keystroke changes the value and leaves the count where it was`() {
        val value = Revisioned.initial("first").typed("firsts")

        assertEquals("firsts", value.value)
        assertEquals(0L, value.revision)
    }

    @Test
    fun `a replacement changes the value and moves the count`() {
        val value = Revisioned.initial("first").replacedBy("second")

        assertEquals("second", value.value)
        assertEquals(1L, value.revision)
    }

    @Test
    fun `a count moves once per replacement`() {
        val value = Revisioned.initial("first")
            .replacedBy("second")
            .replacedBy("third")

        assertEquals("third", value.value)
        assertEquals(2L, value.revision)
    }

    @Test
    fun `a keystroke between two replacements does not move the count`() {
        val value = Revisioned.initial("first")
            .typed("firsts")
            .replacedBy("second")
            .typed("seconds")
            .replacedBy("third")

        assertEquals("third", value.value)
        assertEquals(2L, value.revision)
    }

    @Test
    fun `two values with the same content and the same count are equal`() {
        val one = Revisioned.initial("first").replacedBy("second")
        val other = Revisioned.initial("first").replacedBy("second")

        assertEquals(one, other)
        assertEquals(one.hashCode(), other.hashCode())
    }

    @Test
    fun `two values with the same content and different counts are not equal`() {
        val replaced = Revisioned.initial("first").replacedBy("second")
        val typed = Revisioned.initial("first").typed("second")

        assertNotEquals(replaced, typed)
    }
}
