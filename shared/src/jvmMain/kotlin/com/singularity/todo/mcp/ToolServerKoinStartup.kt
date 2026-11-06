package com.singularity.todo.mcp

import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.feature.profile.ProfileId
import org.koin.core.module.Module

/**
 * Starts the container the tool server runs on.
 *
 * The composition root belongs to the process entry point, not to this library: the
 * server runs in two processes — the standalone launcher and the desktop application's
 * `--mcp-server` mode — and each one starts its own container. Keeping the entry point
 * in this module also made it the Koin compiler plugin's "aggregator", which forced the
 * module's compile task to re-run on every build.
 */
fun interface ToolServerKoinStartup {
    /**
     * Starts the container for [fixedProfileId], or throws when it cannot be built.
     * The start-up step turns the throw into a refusal.
     */
    fun start(fixedProfileId: String?)
}

/**
 * The bindings the tool server runs on.
 *
 * Deliberately the same set the desktop application's entry point starts: a hand-rolled
 * subset drifted once and left `SyncWorkScheduler`/proposal DAOs unbound, which killed
 * [ToolRegistrar] at startup.
 *
 * A process told which profile to serve never opens the store that holds the active
 * profile — the store takes a file lock, and the application is holding it. That is
 * decided by this module list, not by anything the server does later: the profile
 * repository is built without a store, and the tools read their one setting through
 * the narrow port, which answers with the default rather than pulling the
 * application's settings store in with it.
 */
fun toolServerModules(fixedProfileId: String?): List<Module> =
    listOf(platformModule(), coreLoggingModule()) +
        domainModule(fixedProfileId?.let(ProfileId::fromString))
