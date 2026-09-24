package com.singularity.todo.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import kotlin.reflect.KClass

/**
 * Emits [emptyPreferences] when [IOException] is thrown (e.g. corrupted DataStore file),
 * re-throwing all other exceptions.
 */
private fun Flow<Preferences>.catchIOExceptionEmitEmpty(): Flow<Preferences> =
    catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

/**
 * Internal holder for preference metadata. Used by [BooleanPref], [IntPref], [StringPref],
 * [FloatPref] to avoid boxing of non-primitive types in the inline class constructor.
 */
internal data class PrefSpec<T>(
    val dataStore: DataStore<Preferences>,
    val key: Preferences.Key<T>,
    val default: T,
    val range: IntRange? = null,
)

/** Inline class wrapper for a boolean DataStore preference. */
@JvmInline
value class BooleanPref internal constructor(private val spec: PrefSpec<Boolean>) {
    val flow: Flow<Boolean>
        get() = spec.dataStore.data
            .catchIOExceptionEmitEmpty()
            .map { it[spec.key] ?: spec.default }

    suspend fun set(value: Boolean) {
        spec.dataStore.edit { it[spec.key] = value }
    }
}

/** Inline class wrapper for an int DataStore preference with optional range validation. */
@JvmInline
value class IntPref internal constructor(private val spec: PrefSpec<Int>) {
    val flow: Flow<Int>
        get() = spec.dataStore.data
            .catchIOExceptionEmitEmpty()
            .map {
                val raw = it[spec.key] ?: spec.default
                spec.range?.let { raw.coerceIn(it) } ?: raw
            }

    suspend fun set(value: Int) {
        val coerced = spec.range?.let { value.coerceIn(it) } ?: value
        spec.dataStore.edit { it[spec.key] = coerced }
    }
}

/** Inline class wrapper for a string DataStore preference. */
@JvmInline
value class StringPref internal constructor(private val spec: PrefSpec<String>) {
    val flow: Flow<String>
        get() = spec.dataStore.data
            .catchIOExceptionEmitEmpty()
            .map { it[spec.key] ?: spec.default }

    suspend fun set(value: String) {
        spec.dataStore.edit { it[spec.key] = value }
    }
}

/** Inline class wrapper for a float DataStore preference. */
@JvmInline
value class FloatPref internal constructor(private val spec: PrefSpec<Float>) {
    val flow: Flow<Float>
        get() = spec.dataStore.data
            .catchIOExceptionEmitEmpty()
            .map { it[spec.key] ?: spec.default }

    suspend fun set(value: Float) {
        spec.dataStore.edit { it[spec.key] = value }
    }
}

/**
 * Nullable string preference. Null value removes the key from DataStore (consistent with
 * [DefaultAgendaViewSettingsRepository] behavior).
 */
class NullableStringPref internal constructor(
    private val dataStore: DataStore<Preferences>,
    val key: Preferences.Key<String>,
) {
    val flow: Flow<String?>
        get() = dataStore.data.map { it[key] }

    suspend fun set(value: String?) {
        dataStore.edit {
            if (value == null) it.remove(key) else it[key] = value
        }
    }
}

/**
 * Enum preference stored as string (`.name`). Reading uses [KClass.java.enumConstants]
 * to recover the enum value; invalid stored strings fall back to [default].
 */
class EnumPref<T : Enum<T>> internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val key: Preferences.Key<String>,
    private val default: T,
    private val klass: KClass<T>,
) {
    val flow: Flow<T>
        get() = dataStore.data.map { prefs ->
            prefs[key]?.let { name ->
                klass.java.enumConstants.find { it.name == name }
            } ?: default
        }

    suspend fun set(value: T) {
        dataStore.edit { it[key] = value.name }
    }
}
