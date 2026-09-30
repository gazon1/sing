package com.singularity.todo.core.di

import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.TagsViewModel
import com.singularity.todo.feature.tags.data.TagGroupRepositoryImpl
import com.singularity.todo.feature.tags.data.TagsRepositoryImpl
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import com.singularity.todo.feature.tags.domain.usecase.CreateTagGroupUseCase
import com.singularity.todo.feature.tags.domain.usecase.CreateTagUseCase
import com.singularity.todo.feature.tags.domain.usecase.DeleteTagGroupUseCase
import com.singularity.todo.feature.tags.domain.usecase.EffectiveTagsResolver
import com.singularity.todo.feature.tags.domain.usecase.UpdateTagUseCase
import com.singularity.todo.feature.tags.presentation.viewmodel.TagGroupsViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Tags feature DI: repositories, use cases, ViewModels.
 */
fun tagsModule(): org.koin.core.module.Module = module {
    // ─── Repository ─────────────────────────────────────────────────────

    single<TagsRepository> {
        TagsRepositoryImpl(
            get(),
            get(),
            get(),
            get(),
        )
    }

    // Named resolution: the repository takes three distinct DAOs, and the group
    // delete writes through TagDao to release member tags. Positional get() would
    // silently misbind if the constructor order ever changes.
    single<TagGroupRepository> {
        TagGroupRepositoryImpl(
            tagGroupDao = get(),
            inheritedTagGroupDao = get(),
            tagDao = get(),
            clock = get(),
            currentUser = get(),
            syncRepository = get(),
        )
    }

    // ─── Use Cases ───────────────────────────────────── // ────────────────

    factoryOf(
        ::CreateTagUseCase,
    )
    factoryOf(::UpdateTagUseCase)
    factoryOf(::CreateTagGroupUseCase)
    factoryOf(::DeleteTagGroupUseCase)
    factoryOf(::EffectiveTagsResolver)

    // ─── ViewModels ─────────────────────────────────────────────────────

    viewModel { TagsViewModel(tagRepo = get(), createTag = get(), updateTag = get(), currentUser = get()) }
    viewModelOf(::TagGroupsViewModel)
}
