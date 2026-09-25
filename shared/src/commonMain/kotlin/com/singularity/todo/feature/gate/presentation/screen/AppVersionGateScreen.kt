package com.singularity.todo.feature.gate.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.feature.gate.presentation.state.AppVersionGateState
import com.singularity.todo.feature.gate.presentation.viewmodel.AppVersionGateIntent
import com.singularity.todo.feature.gate.presentation.viewmodel.AppVersionGateViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Full-screen version gate.
 *
 * Checks [RemoteConfigPort] via [AppVersionGateViewModel] and renders either:
 * - A loading splash while checking.
 * - A blocked screen with "Update Required" and a store button.
 * - The [content] composable when the version is acceptable.
 *
 * The blocked screen's "Check Again" button re-checks via [AppVersionGateViewModel.onCheckAgain].
 * "Update Now" calls [onOpenStore] — a platform-specific callback provided by the caller
 * (Android: opens Play Store intent; Desktop: opens browser).
 *
 * @param playStoreUrl Platform-specific update URL passed to [AppVersionGateViewModel].
 * @param onOpenStore Called when the user clicks "Update Now". Platform-specific:
 *   Android — starts [android.content.Intent.ACTION_VIEW] with the Play Store URI.
 *   Desktop — calls [java.awt.Desktop.browse].
 * @param content The app content to render when the version gate passes.
 */
@Composable
fun AppVersionGateScreen(
    playStoreUrl: String,
    onOpenStore: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val vm: AppVersionGateViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    AppVersionGateContent(
        state = state,
        onCheckAgain = { vm.onIntent(AppVersionGateIntent.CheckAgain) },
        onOpenStore = onOpenStore,
        modifier = modifier,
        content = content,
    )
}

@Composable
private fun AppVersionGateContent(
    state: AppVersionGateState,
    onCheckAgain: () -> Unit,
    onOpenStore: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (state) {
            is AppVersionGateState.Checking -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.Update,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Checking version…",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            is AppVersionGateState.Blocked -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Update Required",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = buildString {
                            append("Your version (${state.currentVersion}) is no longer supported.\n")
                            append("Please update to ${state.minSupportedVersion} or newer to continue.")
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(40.dp))
                    Button(onClick = onOpenStore) {
                        Text("Update Now")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = onCheckAgain) {
                        Text("Check Again")
                    }
                }
            }

            is AppVersionGateState.Allowed -> {
                content()
            }
        }
    }
}
