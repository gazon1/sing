package com.singularity.todo.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.singularity.todo.core.ui.components.FormState

/**
 * Magic-link verification form data.
 *
 * @param email  User's email address.
 * @param token  One-time token from the magic link URL.
 */
data class MagicLinkForm(val email: String = "", val token: String = "")

/**
 * Form state for the magic-link verification screen.
 *
 * Persists across process death via [Saver]. Using [rememberSaveable] (not plain
 * [remember]) means the email and token survive configuration changes and
 * on-save-instance state restoration.
 */
@Stable
class MagicLinkFormState(form: MagicLinkForm = MagicLinkForm()) : FormState<MagicLinkForm>(form) {
    override fun initialForm() = MagicLinkForm()

    companion object {
        val Saver = listSaver(
            save = { listOf(it.value.email, it.value.token) },
            restore = { MagicLinkFormState(MagicLinkForm(it[0] as String, it[1] as String)) },
        )
    }
}

@Composable
fun rememberMagicLinkFormState() = rememberSaveable(saver = MagicLinkFormState.Saver) { MagicLinkFormState() }
