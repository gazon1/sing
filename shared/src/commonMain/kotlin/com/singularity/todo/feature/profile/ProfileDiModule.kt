package com.singularity.todo.feature.profile

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings for the profile feature.
 *
 * IMPORTANT: Each binding is in its own `module {}` returned as a list.
 * Using a single `module {}` block for all bindings creates a child scope
 * in Koin 4, making those bindings invisible to sibling modules at the root scope.
 * Returning `List<Module>` ensures each binding is registered at root scope.
 */
fun profileModule(): List<Module> = listOf(
    module { single { ProfileRepositoryImpl(get(), get(), get()) } },
    module { single { ProfileAwareCurrentUser(get(), get()) } },
    module { factory { ProfileBootstrapper(get()) } },
)
