package com.singularity.todo.feature.gate

import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.gate.presentation.viewmodel.AppVersionGateViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * DI module for [AppVersionGateViewModel].
 *
 * Each platform provides its own [playStoreUrl] when calling [gateModule]:
 * - Android: `market://details?id=com.singularity.todo`
 * - Desktop: `https://github.com/singularity-todo/singularity/releases`
 *
 * Screen composables use [org.koin.compose.viewmodel.koinViewModel] without parameters
 * (Koin resolves all three constructor arguments via `get()`).
 * The [scope][AppVersionGateViewModel] defaults to [AutoCloseableCoroutineScope].
 */
fun gateModule(playStoreUrl: String): Module = module {
    viewModel {
        AppVersionGateViewModel(
            remoteConfigPort = get<RemoteConfigPort>(),
            appVersion = appVersion(),
            playStoreUrl = playStoreUrl,
        )
    }
}
