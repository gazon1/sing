package com.singularity.todo.core.network

import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.core.qualifier.named

/**
 * Network configuration for Supabase.
 */
data class SupabaseConfig(
    val url: String,
    val anonKey: String
)

/**
 * Creates HttpClient for the current platform.
 */
expect fun createHttpClient(): HttpClient

/**
 * Creates SupabaseClient with the given config.
 * TODO: Replace stub with actual Supabase SDK once dependencies are resolved.
 */
fun createSupabaseClient(config: SupabaseConfig, httpClient: HttpClient): Any {
    return Unit
}

/**
 * Network module factory — call from platform entry point.
 */
fun createNetworkModule(config: SupabaseConfig, httpClient: HttpClient): Module {
    return org.koin.dsl.module {
        single { httpClient }
        single(named("supabaseUrl")) { config.url }
        single(named("supabaseAnonKey")) { config.anonKey }
    }
}
