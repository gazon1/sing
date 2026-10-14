package com.singularity.todo.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.singularity.todo.core.ui.components.FormState

/**
 * Server configuration form data.
 *
 * @param url     Supabase project URL (e.g. https://xxx.supabase.co).
 * @param anonKey Anon key from the Supabase project settings.
 */
data class ServerConfigForm(val url: String = "", val anonKey: String = "")

/**
 * Form state for the server configuration screen.
 *
 * Persists across process death via [Saver]. The user should not need to re-enter
 * these values after the app is killed — they are meaningful once and permanent.
 */
@Stable
class ServerConfigFormState(form: ServerConfigForm = ServerConfigForm()) : FormState<ServerConfigForm>(form) {
    override fun initialForm() = ServerConfigForm()

    companion object {
        val Saver = listSaver(
            save = { listOf(it.value.url, it.value.anonKey) },
            restore = { ServerConfigFormState(ServerConfigForm(it[0] as String, it[1] as String)) },
        )
    }
}

@Composable
fun rememberServerConfigFormState() = rememberSaveable(saver = ServerConfigFormState.Saver) { ServerConfigFormState() }
