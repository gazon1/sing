package com.singularity.todo.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.enums.EnumEntries

/**
 * Base class for typed DataStore-backed settings repositories.
 * Provides factory helpers for all supported preference types.
 *
 * Usage:
 * ```kotlin
 * class DataStoreNotificationsSettingsRepository(dataStore: DataStore<Preferences>)
 *     : BaseSettingsRepository(dataStore), NotificationsSettingsRepository {
 *     private val enabledPref = boolPref("notifications.enabled", true)
 *     override val enabled: Flow<Boolean> get() = enabledPref.flow
 * }
 * ```
 */
abstract class BaseSettingsRepository(internal val dataStore: DataStore<Preferences>) {

    protected fun boolPref(name: String, default: Boolean): BooleanPref =
        BooleanPref(PrefSpec(dataStore, booleanPreferencesKey(name), default))

    protected fun intPref(name: String, default: Int, range: IntRange? = null): IntPref =
        IntPref(PrefSpec(dataStore, intPreferencesKey(name), default, range))

    protected fun stringPref(name: String, default: String): StringPref =
        StringPref(PrefSpec(dataStore, stringPreferencesKey(name), default))

    protected fun floatPref(name: String, default: Float): FloatPref =
        FloatPref(PrefSpec(dataStore, floatPreferencesKey(name), default))

    protected fun nullableStringPref(name: String): NullableStringPref =
        NullableStringPref(dataStore, stringPreferencesKey(name))

    protected fun <T : Enum<T>> enumPref(
        name: String,
        default: T,
        entries: EnumEntries<T>,
    ): EnumPref<T> = EnumPref(dataStore, stringPreferencesKey(name), default, entries)

    /** Convenience: form a namespaced preference key string. */
    protected fun nsKey(namespace: String, name: String): String = "$namespace.$name"
}
