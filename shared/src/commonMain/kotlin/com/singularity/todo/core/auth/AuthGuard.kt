package com.singularity.todo.core.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.feature.auth.LoginScreen
import org.koin.compose.koinInject

/**
 * Navigation guard: routes the user to [LoginScreen] when the session is
 * [Session.SignedOut], shows a spinner while [Session.Loading], and renders
 * the protected [content] for [Session.SignedIn] / [Session.Anonymous].
 *
 * This is the single place where the auth-required decision is made — every
 * feature ViewModel receives its userId from [CurrentUser] and never has to
 * branch on auth state itself.
 *
 * [LoginScreen] uses [com.singularity.todo.feature.nav.LocalAppNavigator] to
 * navigate on success; no callbacks are needed here.
 */
@Composable
fun AuthGuard(authRepository: AuthRepository = koinInject(), content: @Composable () -> Unit) {
    val session by authRepository.session.collectAsStateWithLifecycle()
    when (session) {
        Session.Loading -> LoadingIndicator()
        Session.SignedOut -> LoginScreen()
        is Session.SignedIn, is Session.Anonymous -> content()
    }
}
