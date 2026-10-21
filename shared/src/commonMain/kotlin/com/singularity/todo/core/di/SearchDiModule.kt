package com.singularity.todo.core.di

import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.feature.search.data.SearchTextIndexImpl
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The full-text index.
 *
 * The index is a read model over four tables, and it is bound once here rather
 * than four times in the modules of the four kinds: the repositories read their
 * own rows, and everything that asks "which records carry this word" — the
 * search, the task filter, the note list, the link autocompletion — asks this
 * one port.
 *
 * The binding is at the root scope on purpose. A child scope would hide it
 * from the sibling modules that need it (ADR `2026-09-27-di-module-aggregator-narrative`).
 */
fun searchModule(): Module = module {
    single<SearchTextIndex> {
        SearchTextIndexImpl(
            ftsDao = get(),
            taskDao = get(),
            currentUser = get(),
        )
    }
}
