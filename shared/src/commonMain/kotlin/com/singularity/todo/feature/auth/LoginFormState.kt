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
 * @param disposition what to do with data created while offline. `null` until the user
 * picks, which is the point: the attach screen cannot submit without one, because REQ-UA-005
 * requires the choice to be made rather than defaulted. `null` on the login screen, where
 * there is nothing to attach.
 */
data class LoginForm(
    val email: String = "",
    val password: String = "",
    val isSignUp: Boolean = false,
    val disposition: LocalDataDisposition? = null,
)

/**
 * Form state for the login / sign-up screen.
 *
 * Persists across process death via [Saver]. The [isSignUp] toggle and the attach
 * disposition are both included so screen rotation does not reset the user's chosen mode
 * or silently un-answer the question about their data.
 */
@Stable
class LoginFormState(form: LoginForm = LoginForm()) : FormState<LoginForm>(form) {
    override fun initialForm() = LoginForm()

    companion object {
        val Saver = listSaver(
            save = {
                listOf(
                    it.value.email,
                    it.value.password,
                    it.value.isSignUp,
                    // The simple class name, or null. An unrecognised name reads back as null
                    // rather than crashing the restore — the screen then shows both options
                    // again, which is the safe reading of a choice that was lost.
                    // Actual counts are reloaded from summarise() when the screen opens.
                    it.value.disposition?.let { d ->
                        when (d) {
                            is LocalDataDisposition.Keep -> "Keep"
                            is LocalDataDisposition.Discard -> "Discard"
                        }
                    },
                )
            },
            restore = {
                LoginFormState(
                    LoginForm(
                        email = it[0] as String,
                        password = it[1] as String,
                        isSignUp = it[2] as Boolean,
                        disposition = (it[3] as String?)?.let { kind ->
                            when (kind) {
                                "Keep" -> LocalDataDisposition.Keep(ReassignCounts(), LocalDataSummary(0, 0))
                                "Discard" -> LocalDataDisposition.Discard(EraseCounts())
                                else -> null
                            }
                        },
                    ),
                )
            },
        )
    }
}

@Composable
fun rememberLoginFormState() = rememberSaveable(saver = LoginFormState.Saver) { LoginFormState() }
