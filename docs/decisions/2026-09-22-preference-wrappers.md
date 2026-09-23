---
title: "DataStore preference wrappers: inline class + BaseSettingsRepository"
date: 2026-09-22
status: accepted
tags: [settings, architecture, datastore, kotlin]
---

## Context

Each per-section `*SettingsRepository` had ~15 lines of boilerplate per field:
```kotlin
// Before: repetitive per-field boilerplate
private val enabledKey = booleanPreferencesKey("namespace.enabled")
val enabled: Flow<Boolean> = dataStore.data.map { it[enabledKey] ?: false }
suspend fun setEnabled(value: Boolean) { dataStore.edit { it[enabledKey] = value } }
```

With 6 fields across 5 repositories, this was ~90 lines of mechanical repetition.

Additionally, the inline class wrappers (`BooleanPref`, `IntPref`, etc.) had a Kotlin limitation: a `value class` can't directly hold a `DataStore<Preferences>` reference without boxing. The workaround is an internal `PrefSpec<T>` data class as the single stored property.

## Decision

### 1. Inline class preference wrappers (`PreferenceWrappers.kt`)

```kotlin
@JvmInline
value class BooleanPref internal constructor(private val spec: PrefSpec<Boolean>) {
    val flow: Flow<Boolean> = spec.dataStore.data.map { it[spec.key] ?: spec.default }
    suspend fun set(value: Boolean) { spec.dataStore.edit { it[spec.key] = value } }
}

class EnumPref<T : Enum<T>> internal constructor(
    private val dataStore: DataStore<Preferences>,
    val key: Preferences.Key<String>,
    private val default: T,
    private val klass: KClass<T>,
) {
    val flow: Flow<T> get() = dataStore.data.map { p ->
        p[key]?.let { n -> klass.java.enumConstants.find { it.name == n } } ?: default
    }
    suspend fun set(value: T) = dataStore.edit { it[key] = value.name }
}

// IntPref supports optional range coercion
@JvmInline
value class IntPref internal constructor(private val spec: PrefSpec<Int>) {
    val flow: Flow<Int> get() = spec.dataStore.data.map {
        val raw = it[spec.key] ?: spec.default
        spec.range?.let { raw.coerceIn(it) } ?: raw
    }
    suspend fun set(value: Int) {
        val coerced = spec.range?.let { value.coerceIn(it) } ?: value
        spec.dataStore.edit { it[spec.key] = coerced }
    }
}
```

`PrefSpec<T>` is an internal data class holding the 4 pieces of data:
```kotlin
internal data class PrefSpec<T>(
    val dataStore: DataStore<Preferences>,
    val key: Preferences.Key<T>,
    val default: T,
    val range: IntRange? = null,
)
```

### 2. `BaseSettingsRepository` abstract class

```kotlin
abstract class BaseSettingsRepository(internal val dataStore: DataStore<Preferences>) {
    protected fun boolPref(name: String, default: Boolean) =
        BooleanPref(PrefSpec(dataStore, booleanPreferencesKey(name), default))
    protected fun <T : Enum<T>> enumPref(name: String, default: T, klass: KClass<T>) =
        EnumPref(dataStore, stringPreferencesKey(name), default, klass)
    protected fun nsKey(ns: String, name: String) = "$ns.$name"
    // ... intPref, stringPref, floatPref, nullableStringPref
}
```

Each concrete repository inherits `BaseSettingsRepository` and calls factory methods:
```kotlin
class DataStoreNotificationsSettingsRepository(dataStore: DataStore<Preferences>)
    : BaseSettingsRepository(dataStore), NotificationsSettingsRepository {
    private val enabledPref = boolPref(nsKey(NOTIFICATIONS, "enabled"), true)
    override val enabled: Flow<Boolean> = enabledPref.flow
    override suspend fun setEnabled(value: Boolean) = enabledPref.set(value)
}
```

## Consequences

- 5 repositories (`Notifications`, `WorkSchedule`, `Greeting`, `DefaultAgendaView`, appearance) shrank by ~35% each (~90 → ~55 lines).
- `PrefSpec` as internal holder avoids Kotlin inline class boxing — the inline class wrapper is zero-cost at call sites.
- `IntPref` range support (e.g., `intPref(..., range = 0..23)`) enforces min/max at write time, consistent with `coerceIn` in `Flow.map`.
- No changes to the public repository interface — `Flow<T>` and `suspend fun set` signatures are identical.
