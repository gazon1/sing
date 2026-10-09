package com.singularity.todo.core.sync

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Registry of pull-event handlers, one per [DocType].
 *
 * Each handler is registered by a feature module at startup (`TasksDiModule`,
 * `NotesDiModule`, etc.) and is called by [PullPhase] when a pulled event matches
 * its document type.
 *
 * Built as a dedicated collaborator rather than a field on [SyncEngine] so that:
 * - The registration map is testable in isolation
 * - Handlers can be added after engine construction (via `init` blocks in DI modules)
 * - The map is never mutated except by [registerHandler]
 *
 * [registerHandler] is non-suspend. It assigns directly to [MutableStateFlow.value],
 * which is atomic — the [MutableStateFlow] guarantees sequential consistency for all
 * writes. No lock is needed because the flow is never read by more than one coroutine
 * at a time — [PullPhase] reads it inside a suspend function.
 *
 * @see EntityApply
 * @see PullPhase
 */
internal class HandlerRegistry {
    private val _handlers = MutableStateFlow<Map<DocType, EntityApply>>(emptyMap())
    val handlers: Map<DocType, EntityApply> get() = _handlers.value

    /**
     * Registers [apply] as the handler for [docType].
     *
     * Subsequent calls for the same [DocType] replace the previous handler.
     * Registration order is not significant.
     */
    fun registerHandler(docType: DocType, apply: EntityApply) {
        _handlers.value = _handlers.value + (docType to apply)
    }
}

