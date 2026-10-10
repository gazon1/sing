---
title: "Two Largest Baseline Rules Contradict Documented Conventions"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** 2026-10-04, while sizing up a campaign to shrink the detekt baseline.
The plan proposed attacking the top-3 rules mechanically. Two of them are not debt.

**Status: OPEN**

**Tracked as:** #456

**`BackingPropertyNaming` — 53 entries, every one of them correct.**
AGENTS.md's *canonical VM pattern* is:

```kotlin
private val _state = MutableStateFlow<UiState>(UiState.Loading)
val state: StateFlow<UiState> = _state.asStateFlow()
```

detekt's `BackingPropertyNaming` forbids the underscore prefix. The rule is not
configured anywhere in `config/detekt/detekt.yml` — it is running on detekt's
built-in default, and it is flagging the project's own mandated pattern 53 times.
"Fixing" these means renaming `_state` → `stateInternal` in 53 places and
rewriting the canonical example in AGENTS.md, so that a style rule wins over the
documented architecture. That is backwards.

**`PackageNaming` — 43 entries, real but not mechanical.**
Almost all are one package: `com.singularity.todo.feature.calendar_sync`. detekt
wants no underscores in package names. The rename is a mechanical edit but it
touches every import of that package, and the neighbouring question — whether
repositories live in `domain/port/` — is already an open decision
(`C2` in the restore-verifiability plan). Do them together or neither.

**`LongMethod` — 39 entries, genuine, and not a campaign.**
Decomposing 39 long methods is Epic B3-scale work with real regression risk per
method. It wants a per-method decision, not a sweep. `BackupScreen.kt` at 451
lines is the largest and belongs on its own.

**Try next, in order:**

1. **Decide `BackingPropertyNaming` explicitly** (10 minutes, removes 53 entries).
   Either add it to `detekt.yml` with `active: false` and a comment pointing at
   AGENTS.md's canonical pattern, or change the convention and the doc together.
   Option 1 is almost certainly right — the underscore is doing real work, keeping
   the mutable backing property visibly distinct from the `asStateFlow()` public
   face.
2. **Leave `PackageNaming` until C2 is decided**, then do the package rename in one
   commit with its own ADR.
3. **Leave `LongMethod`.** Work it as Epic B, biggest first.

The pattern across all three is the one worth remembering: an unconfigured
detekt built-in default is a rule nobody chose. The same thing happened with
`style:MaximumLineLength` (default 120 silently overriding `.editorconfig`'s 140)
and with the two rule sets that were registered but never configured. **Default-on
is not the same as decided-on**, and a baseline full of entries that contradict
your own architecture is a signal to look at the configuration, not the code.

---
