package com.singularity.todo.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.singularity.todo.core.datastore.catchDataStoreIoError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.enums.EnumEntries

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
            .catchDataStoreIoError()
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
            .catchDataStoreIoError()
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
            .catchDataStoreIoError()
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
            .catchDataStoreIoError()
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
    /**
     * Emits `null` on I/O error, matching the behaviour of the four typed wrappers
     * ([BooleanPref], [IntPref], [StringPref], [FloatPref]) — all of which emit their
     * default on DataStore corruption rather than propagating the exception.
     *
     * A settings screen should survive a corrupt store: `null` here means "not set",
     * which is indistinguishable from a user who has not set a value, and which
     * the settings UI already handles as an empty field.
     */
    val flow: Flow<String?>
        get() = dataStore.data
            .catchDataStoreIoError()
            .map { it[key] }

    suspend fun set(value: String?) {
        dataStore.edit {
            if (value == null) it.remove(key) else it[key] = value
        }
    }
}

/**
 * Enum preference stored as string (`.name`). Reading recovers the value by name out of
 * [entries]; a stored string that matches no constant falls back to [default].
 *
 * ## Why the caller passes [EnumEntries] instead of a [KClass]
 *
 * Recovering the constants from a `KClass` means `KClass.java.enumConstants`, and
 * `java.lang.Class` does not exist on Kotlin/Native — a preference read that compiles
 * on the only two targets this project builds. [EnumEntries] is the multiplatform type
 * the compiler hands you for `SomeEnum.entries`, so the caller already holds it and no
 * reflection is involved at any point.
 */
class EnumPref<T : Enum<T>> internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val key: Preferences.Key<String>,
    private val default: T,
    private val entries: EnumEntries<T>,
) {
    /**
     * Emits [default] on I/O error, matching the behaviour of the four typed wrappers
     * ([BooleanPref], [IntPref], [StringPref], [FloatPref]) — all of which emit their
     * default on DataStore corruption rather than propagating the exception.
     *
     * A settings screen should survive a corrupt store: the enum default is the same
     * value the UI initialises to, and the user can still interact with the settings.
     */
    val flow: Flow<T>
        get() = dataStore.data
            .catchDataStoreIoError()
            .map { prefs ->
            prefs[key]?.let { name ->
                entries.firstOrNull { it.name == name }
            } ?: default
        }

    suspend fun set(value: T) {
        dataStore.edit { it[key] = value.name }
    }
}
