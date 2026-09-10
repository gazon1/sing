package com.singularity.todo.core.settings

import kotlinx.coroutines.flow.Flow

/**
 * A feature contributes its settings block through this interface.
 *
 * Each contributor:
 * - [section] — marker value for identification
 * - [observe] — reactive stream of the typed settings section
 * - [apply] — persists a settings intent from the UI
 *
 * Registration: `single<SettingsContributor> { MyContributor(get()) }` in the
 * feature's DI module. [SettingsViewModel] collects all contributors via
 * `getAll<SettingsContributor>()`.
 *
 * @param S  the sealed-subtype of [SettingsSection] this contributor owns
 * @param I  the sealed-subtype of [SettingsIntent] this contributor handles
 */
interface SettingsContributor<S : SettingsSection, I : SettingsIntent> {
    val section: S
    fun observe(): Flow<S>
    suspend fun apply(intent: I)
}
