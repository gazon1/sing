package com.singularity.todo.core.di

import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.TagsRepositoryImpl
import com.singularity.todo.feature.tags.TagsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Tags feature DI: repositories, ViewModels.
 */
fun tagsModule(): org.koin.core.module.Module = module {
    // ─── Repository ─────────────────────────────────────────────────────

    single<TagsRepository> { TagsRepositoryImpl(get(), get(), get(), get()) }

    // ─── ViewModels ─────────────────────────────────────────────────────

    viewModel { TagsViewModel(tagRepo = get()) }
}
