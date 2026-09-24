package com.singularity.todo.core.di

import kotlinx.coroutines.runBlocking

/**
 * Synchronous bridge from a `suspend` block into a non-suspend Koin factory.
 *
 * Koin's factory DSL is synchronous; some of our bindings need to call
 * suspend code (e.g. [createKoogPromptExecutor] reads Flow-backed
 * settings). [koinBridge] wraps that call in a one-shot `runBlocking`,
 * scoped to the binding's first construction.
 *
 * Prefer this helper over a raw [runBlocking] so the bridge is greppable
 * and can later be replaced wholesale with Koin coroutine-aware factories
 * — one site change, one behavioural shift.
 */
internal inline fun <T> koinBridge(crossinline block: suspend () -> T): T =
    runBlocking { block() }
