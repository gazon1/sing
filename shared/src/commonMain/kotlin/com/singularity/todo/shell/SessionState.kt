package com.singularity.todo.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.auth.Session
import com.singularity.todo.feature.auth.AuthViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Whether the current session is anonymous (local). Centralises the lookup so
 * both shells observe the session the same way.
 */
@Composable
fun rememberIsAnonymousSession(): Boolean {
    val authViewModel: AuthViewModel = koinViewModel()
    val session by authViewModel.session.collectAsStateWithLifecycle(initialValue = Session.Loading)
    return session is Session.Anonymous
}
