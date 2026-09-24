package com.singularity.todo.core.di

import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.TagsViewModel
import com.singularity.todo.feature.tags.data.TagGroupRepositoryImpl
import com.singularity.todo.feature.tags.data.TagsRepositoryImpl
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import com.singularity.todo.feature.tags.domain.usecase.CreateTagUseCase
import com.singularity.todo.feature.tags.domain.usecase.UpdateTagUseCase
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Tags feature DI: repositories, use cases, ViewModels.
 */
fun tagsModule(): org.koin.core.module.Module = module {
    // ─── Repository ─────────────────────────────────────────────────────

    single<TagsRepository> { TagsRepositoryImpl(get(), get(), get(), get()) }

    single<TagGroupRepository> { TagGroupRepositoryImpl(get(), get()) }

    // ─── Use Cases ─────────────────────────────────────────────────────

    factoryOf(::CreateTagUseCase)
    factoryOf(::UpdateTagUseCase)

    // ─── ViewModels ─────────────────────────────────────────────────────

    viewModel { TagsViewModel(tagRepo = get()) }
}
