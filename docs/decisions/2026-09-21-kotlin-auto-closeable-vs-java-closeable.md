---
title: "`kotlin.AutoCloseable` vs `java.io.Closeable` in KMP commonMain"
status: accepted
date: 2026-09-21
---

# `kotlin.AutoCloseable` vs `java.io.Closeable` in KMP commonMain

## Context

Kotlin 2.0 introduced `kotlin.AutoCloseable` as a **commonMain expect interface** (`@SinceKotlin("2.0")`).
It coexists with `java.io.Closeable` (Java) and `kotlin.io.Closeable` (deprecated alias in Kotlin stdlib).

In KMP commonMain, these three types are **not interchangeable**:

| Type | Where | `close()` signature |
|---|---|---|
| `kotlin.AutoCloseable` | Kotlin 2.0 commonMain expect | `fun close(): Unit` |
| `kotlin.io.Closeable` | Kotlin stdlib alias | `fun close(): Unit` (same signature, but deprecated) |
| `java.io.Closeable` | Java/JVM | `void close()` |

`androidx.lifecycle.ViewModel.addCloseable()` (lifecycle 2.8+) accepts:

```kotlin
public open fun addCloseable(closeable: AutoCloseable)
```

This is **`kotlin.AutoCloseable`** — the Kotlin commonMain interface. On JVM, the JVM
implementation bridges to the same concept.

## Problem

If a class implements `java.io.Closeable` (or `kotlin.io.Closeable`) and is passed to
`ViewModel.addCloseable()`, compilation fails on iOS/Native targets, and on JVM it
causes a `ClassCastException` at runtime.

Symptoms:
- `ViewModel.addCloseable()` line says "type mismatch: X is not a subtype of AutoCloseable"
- Or: runtime ClassCastException when the ViewModel is cleared
- Or: compilation failure in commonMain on non-JVM targets ("Unresolved reference Closeable")

## Root Cause

The Kotlin stdlib historically aliased `kotlin.io.Closeable` to `java.io.Closeable`.
When `kotlin.AutoCloseable` was introduced in Kotlin 2.0 as a separate interface,
`kotlin.io.Closeable` **was not** retroactively changed to point to it.

```
// Kotlin 2.0 stdlib
expect interface AutoCloseable { fun close(): Unit }
typealias Closeable = java.io.Closeable  // NOT kotlin.AutoCloseable!
```

So you can have:
- A class `implements java.io.Closeable` → **wrong** for `ViewModel.addCloseable()`
- A class `implements kotlin.AutoCloseable` → **correct**

## Solution

**Always implement `kotlin.AutoCloseable`** (the expect interface). In practice:
`AutoCloseableCoroutineScope` already does this correctly.

If you need to close a resource, implement `kotlin.AutoCloseable.close()`:

```kotlin
class MyResource : kotlin.AutoCloseable {
    override fun close() {
        // cleanup
    }
}
```

## Rule

> In KMP commonMain, **never** use `java.io.Closeable` or `kotlin.io.Closeable`
> where `kotlin.AutoCloseable` is expected. Use the **unqualified** `AutoCloseable`
> name in type positions — the compiler resolves it to `kotlin.AutoCloseable`
> in commonMain context.

```kotlin
// WRONG in commonMain
class X : java.io.Closeable { ... }
class X : Closeable { ... }  // kotlin.io.Closeable alias — still wrong

// CORRECT in commonMain
class X : AutoCloseable { ... }  // kotlin.AutoCloseable expect
```

## Related

- [2026-09-21-auto-closeable-coroutine-scope](./2026-09-21-auto-closeable-coroutine-scope.md) — where this pitfall was discovered
- `AutoCloseableCoroutineScope.kt` — correct implementation using kotlin.AutoCloseable
- [Kotlin 2.0 AutoCloseable RFC](https://youtrack.jetbrains.com/issue/KT-9972)
