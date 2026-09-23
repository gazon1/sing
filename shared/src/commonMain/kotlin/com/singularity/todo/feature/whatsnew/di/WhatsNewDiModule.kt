package com.singularity.todo.feature.whatsnew.di

/**
 * DI module for the WhatsNew feature.
 *
 * No bindings needed — [com.singularity.todo.feature.whatsnew.presentation.screen.WhatsNewScreen]
 * is a pure Composable that injects [com.singularity.todo.core.config.RemoteConfigPort]
 * directly via `koinInject()`.
 */
fun whatsNewModule() = org.koin.dsl.module {}
