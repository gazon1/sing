---
title: "NoDirectClockSystemRule exemptions are fragile string comparisons"
date: 2026-09-29
status: accepted
tags: [detekt, architecture, tech-debt]
---

## Context

`NoDirectClockSystemRule` bans `Clock.System` access in production code. Two call sites are exempted by file-path string comparison:

```kotlin
private fun isAllowedFile(element: KtElement): Boolean {
    val path = element.containingKtFile.virtualFilePath
    return path.contains("core/platform/Clock.kt") ||
        path.contains("core/di/CoreDiModule.kt")
}
```

This works, but it is fragile in two ways:

1. **Refactoring risk.** Moving `todayAt()` to a different file, or moving the DI binding, silently removes the exemption and would break the build or make the rule fire incorrectly.
2. **Platform-path assumptions.** `virtualFilePath` returns an absolute path that depends on the development machine's project root. In CI (Linux) the separator is `/`; on a Windows checkout it would be `\`. The `contains()` check happens to work on both, but a path like `C:\Users\dev\...\core\platform\Clock.kt` on Windows is still a match, so this is not currently a problem — though it would be if the path ever contained a literal string that happens to match a false positive.

## Decision

Keep the current implementation. The alternatives are worse or not yet viable:

- **Koin module metadata** (`single { Clock.System }`) — no public API to inspect Koin bindings.
- **Annotation on allowed functions** (`@ClockSystemAllowed`) — requires the annotation to be defined and placed on two internal functions, adding ceremony for a narrow exception.
- **Suppress annotations** — per-call-site suppress works but buries the exemption at each call site instead of centralizing it.

The current `contains()` approach is the pragmatic middle ground: explicit, centralized, and low Ceremony.

## Consequences

- If `todayAt` or the DI binding moves to a different file, this rule must be updated alongside it. Treat it as a linked refactoring pair.
- A comment in `Clock.kt` and `CoreDiModule.kt` should reference `NoDirectClockSystemRule` so that developers moving code are warned.
- A future improvement: define `Clock.System` usage in a single `core/platform/Clock.kt` internal object and exempt only that object's direct references, rather than exempting the entire file.

## Links

- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoDirectClockSystemRule.kt`
- Related: `2026-09-29-check-tags-legacy-raw-dead-code.md`
