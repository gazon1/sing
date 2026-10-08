# ADR: `NoUnusedImports` false positive on launch through implicit receiver

**Date:** 2026-10-08
**Status:** accepted
**Deciders:** agent (investigation), user (review)

---

## Context

During the sync-attach rebase, detekt's `NoUnusedImports` rule (the standard `NoUnusedImports`
rule from the detekt library) reported `import kotlinx.coroutines.launch` as unused in a file
where it was very much used. Removing the import produced an `Unresolved reference` — confirming
the finding was a false positive.

The root cause is the gap between detekt's static analysis and Kotlin's implicit receiver
dispatch: when `launch` is called through an implicit `CoroutineScope` receiver (i.e., inside a
class or lambda where `this` is a `CoroutineScope` and `launch` is resolved without an explicit
receiver), the compiler's symbol resolution knows it is a use of the imported `launch`. Detekt's
rule, running in its own analysis pass, can miss this and report the import as unused.

This is a **known limitation** of static analysis tools that rely on semantic information from
compilation but do not re-run full type-resolution. It is not a bug in detekt per se — it
is a class of false positive that affects any rule relying on import-use tracking across
implicit receiver boundaries.

---

## Decision

**1. Document the pattern and the resolution.**

When `NoUnusedImports` fires on `kotlinx.coroutines.launch` (or any other coroutine
builder) and the build fails on deletion:

```kotlin
// The `launch` import is used — detekt's NoUnusedImports has a known false-positive
// gap when `launch` is called through an implicit CoroutineScope receiver.
// Suppressing here is intentional and correct: the alternative (an explicit
// `this.launch { }` or `scope.launch { }`) requires a named receiver that
// may not be present at the call site.
@Suppress("USELESS_IMPORT")
import kotlinx.coroutines.launch
```

**2. Style guide: prefer explicit receiver for `launch` in production code.**

The project convention (visible in all production usages) is explicit receiver:

```kotlin
scope.launch { ... }       // ✓ preferred
this.launch { ... }         // ✓ acceptable if receiver is named
launch { ... }              // ✗ avoid — triggers NoUnusedImports false positive
```

This convention eliminates the false positive class entirely.

**3. No custom detekt rule for this.**

Writing a custom rule to suppress `NoUnusedImports` findings on coroutine-builder imports
would require deep coupling to detekt's internal `BindingContext` API and would replicate a
gap-filler that the comment-based suppression handles cleanly. The cost of the custom rule
(outweighs the benefit.

---

## What to do when this fires

1. Try deleting the flagged import. If the build fails with `Unresolved reference`, it is
   this false positive.
2. Add the `//Suppress("USELESS_IMPORT")` comment above the import.
3. Optionally, convert the call to use an explicit receiver (`scope.launch { }`) — this
   eliminates the false positive without any suppression.
4. If many files are affected, run `just detekt-fix` and review the auto-format results.

---

## Links

- `detekt.yml:430` — `NoUnusedImports` configuration and existing comment about findings
- `kotlinx.coroutines.CoroutineScope.launch` — the function in question
