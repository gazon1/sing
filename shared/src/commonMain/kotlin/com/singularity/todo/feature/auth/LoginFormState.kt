package com.singularity.todo.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.singularity.todo.core.ui.components.FormState

/**
 * Login / sign-up form data.
 *
 * @param email     User's email address.
 * @param password  User's password.
 * @param isSignUp  `true` for sign-up mode, `false` for sign-in mode.
 */
data class LoginForm(val email: String = "", val password: String = "", val isSignUp: Boolean = false)

/**
 * Form state for the login / sign-up screen.
 *
 * Persists across process death via [Saver]. The [isSignUp] toggle is included
 * so screen rotation doesn't reset the user's chosen mode.
 */
@Stable
class LoginFormState(form: LoginForm = LoginForm()) : FormState<LoginForm>(form) {
    override fun initialForm() = LoginForm()

    companion object {
        val Saver = listSaver(
            save = { listOf(it.value.email, it.value.password, it.value.isSignUp) },
            restore = { LoginFormState(LoginForm(it[0] as String, it[1] as String, it[2] as Boolean)) },
        )
    }
}

@Composable
fun rememberLoginFormState() = rememberSaveable(saver = LoginFormState.Saver) { LoginFormState() }
