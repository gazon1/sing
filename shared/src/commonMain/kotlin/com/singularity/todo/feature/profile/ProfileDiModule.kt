package com.singularity.todo.feature.profile

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings for the profile feature.
 */
fun profileModule(): Module = module {
    single { ProfileRepositoryImpl(get(), get(), get()) }
    single { ProfileAwareCurrentUser(get(), get()) }
}
