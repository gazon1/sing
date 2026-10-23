package com.singularity.todo.feature.ai.acp.di

import com.singularity.todo.core.mcp.McpServerCommandProvider
import com.singularity.todo.feature.ai.acp.AcpAgentLauncher
import com.singularity.todo.feature.ai.acp.SubprocessAcpAgentLauncher
import com.singularity.todo.feature.ai.external.AcpExternalAgentViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The bindings of the external-agent feature.
 *
 * ## JVM only
 *
 * The agent is a process, and processes are a desktop thing. This module therefore
 * lives in `jvmMain` and is composed into the desktop application's module list — the
 * mobile build never sees it, and never acquires a dependency it cannot use.
 *
 * ## An explicit lambda, not `viewModelOf`
 *
 * The ViewModel's constructor has defaults, and Koin's reflective constructor
 * resolution does not fill them in — it fails on the first parameter it cannot
 * resolve, defaults included. An explicit lambda says what is injected and what is
 * left alone.
 */
fun acpModule(): Module = module {
    // The command that starts the tool server. One instance: it reads the
    // environment once, and the environment does not change while the application runs.
    single { McpServerCommandProvider() }

    // How an agent is started. Bound rather than defaulted, so that the graph says
    // what it does and a test can put its own launcher in its place.
    single<AcpAgentLauncher> { SubprocessAcpAgentLauncher }

    viewModel {
        AcpExternalAgentViewModel(
            idGen = get(),
            profileRepository = get(),
            commandProvider = get(),
            launcher = get(),
            crashReporter = get(),
        )
    }
}
