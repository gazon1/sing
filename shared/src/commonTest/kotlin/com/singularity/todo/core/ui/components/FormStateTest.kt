package com.singularity.todo.core.ui.components

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [FormState] — form data holder with immutable-update pattern.
 */
@Tag("fast")
class FormStateTest {

    private data class SimpleForm(val email: String = "", val password: String = "", val rememberMe: Boolean = false)

    private class SimpleFormState(form: SimpleForm = SimpleForm()) : FormState<SimpleForm>(form) {
        // Capture a fresh instance for reset() — not the same reference as 'form'
        private val fresh = SimpleForm()
        override fun initialForm() = fresh
    }

    @Test
    fun `initial value comes from initialForm()`() {
        val s = SimpleFormState()
        assertEquals(SimpleForm(), s.value)
    }

    @Test
    fun `update applies transform to value`() {
        val s = SimpleFormState()

        s.update { copy(email = "test@example.com") }

        assertEquals(SimpleForm(email = "test@example.com"), s.value)
    }

    @Test
    fun `update is additive`() {
        val s = SimpleFormState()

        s.update { copy(email = "test@example.com") }
        s.update { copy(password = "secret") }

        assertEquals(SimpleForm(email = "test@example.com", password = "secret"), s.value)
    }

    @Test
    fun `reset returns to initial value`() {
        val s = SimpleFormState()

        s.update { copy(email = "test@example.com", password = "secret", rememberMe = true) }
        s.reset()

        assertEquals(SimpleForm(), s.value)
    }

    @Test
    fun `update preserves untouched fields`() {
        val s = SimpleFormState(SimpleForm(email = "old@example.com"))

        s.update { copy(password = "newpassword") }

        assertEquals("old@example.com", s.value.email)
        assertEquals("newpassword", s.value.password)
        assertEquals(false, s.value.rememberMe)
    }

    @Test
    fun `value is accessible from outside`() {
        val s = SimpleFormState()

        s.update { copy(email = "a@b.com") }
        assertEquals("a@b.com", s.value.email)
    }

    @Test
    fun `multiple sequential updates are additive`() {
        val s = SimpleFormState()

        s.update { copy(email = "1@x.com") }
        s.update { copy(email = "2@x.com") }
        s.update { copy(email = "3@x.com") }

        assertEquals("3@x.com", s.value.email)
    }
}
