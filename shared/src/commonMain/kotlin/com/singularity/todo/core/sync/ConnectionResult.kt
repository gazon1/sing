package com.singularity.todo.core.sync

/**
 * Result of a "test connection" probe (e.g. Supabase health check or empty push).
 * Rendered inline in [SyncConfigScreen].
 */
sealed interface ConnectionResult {
    data object Idle : ConnectionResult
    data object InProgress : ConnectionResult
    data class Success(val version: String? = null) : ConnectionResult
    data class Error(val message: String) : ConnectionResult
}
