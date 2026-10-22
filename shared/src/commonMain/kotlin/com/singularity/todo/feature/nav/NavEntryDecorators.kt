package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey

/**
 * The entry decorators every nested graph applies.
 *
 * The one that matters is the ViewModel-store decorator: it gives every entry of
 * the graph its own ViewModel store. Without it an entry resolves the store of
 * whatever is above it, so two entries of the same graph share one store — and
 * Koin keys a ViewModel by its class alone, so the second entry silently reuses
 * the first one's ViewModel, with the first one's parameters.
 *
 * It used to be applied on Android and not on Desktop, on the belief that
 * "each NavDisplay entry already has proper per-entry ViewModel scoping" on
 * Desktop. Nothing in nav3 provides that scoping. `NestedEntryViewModelScopeTest`
 * measured it: on Desktop the entry inherited the outer store.
 *
 * The decorator comes first in the list and the saveable-state holder comes last,
 * for the same reason the outer graph orders them: a decorator that writes state
 * must see the entry's own store.
 *
 * @return the decorators to hand to `NavDisplay`.
 */
@Composable
fun <T : NavKey> rememberAppEntryDecorators(): List<NavEntryDecorator<T>> =
    listOf(rememberViewModelStoreNavEntryDecorator())
