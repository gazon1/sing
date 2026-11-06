package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Abstract state holder for a form with typed data [T] that survives process death
 * via a concrete [Saver] provided by each subclass.
 *
 * Each concrete subclass:
 * 1. Provides [initialForm] (the starting data)
 * 2. Exposes a `Saver` in its companion object for `rememberSaveable`
 * 3. Provides its own `rememberXxxFormState()` factory function
 *
 * ```
 * data class LoginForm(val email: String = "", val password: String = "", val isSignUp: Boolean = false)
 *
 * class LoginFormState(form: LoginForm = LoginForm()) : FormState<LoginForm>(form) {
 *     companion object {
 *         val Saver: Saver<LoginFormState, Any> = listSaver(
 *             save = { listOf(it.value.email, it.value.password, it.value.isSignUp) },
 *             restore = { LoginFormState(LoginForm(...)) }
 *         )
 *     }
 * }
 *
 * @Composable fun rememberLoginFormState() = rememberSaveable(saver = LoginFormState.Saver) { LoginFormState() }
 * ```
 *
 * @param T The form data type, typically a data class.
 */
@Stable
abstract class FormState<T : Any>(initialData: T) {
    private var _value by mutableStateOf(initialData)

    /** The current form data. */
    val value: T get() = _value

    /** Override to provide the initial form data — used by [reset]. */
    protected abstract fun initialForm(): T

    /** Resets the form to its initial state by re-initializing the backing state. */
    fun reset() {
        _value = initialForm()
    }

    /**
     * Updates the form by applying [transform] to the current [value].
     * Creates a new data class instance via `copy()` — no mutation of existing fields.
     */
    fun update(transform: T.() -> T) {
        _value = _value.transform()
    }
}
