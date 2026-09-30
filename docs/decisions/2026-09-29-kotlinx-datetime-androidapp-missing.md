---
title: Context
date: 2026-09-29
status: closed
description: androidApp lacked kotlinx.datetime on classpath — added, enabling LocalDate use
owner: singularity-dev
last_updated: 2026-09-29
labels: android, build-configuration, datetime
---

# Context

`DebugSeedActivity` (PR2) uses `kotlinx.datetime.LocalDate`, `TimeZone`, and
`Clock.System` — types that live in `shared/` but must be referenced from
`androidApp/src/debug/`. The `kotlinx.datetime` artifact was present in `shared`
but was NOT on `androidApp`'s own classpath, causing unresolved reference errors
at compile time.

# Idea

Add `implementation(libs.kotlinx.datetime)` to `androidApp/build.gradle.kts` so
the android target can reference datetime types used in shared models.

# Decision

Added `kotlinx.datetime` to `androidApp`'s dependencies in
`androidApp/build.gradle.kts`. Also moved `import kotlin.time.Clock` to use the
fully-qualified `kotlin.time.Clock.System` inside the datetime zone calculation
to avoid any ambiguous import risk.

Workaround for `plusDays` not being resolved as a member extension: use
`LocalDate.fromEpochDays(today.toEpochDays() + days)` instead, which is
guaranteed to work on any platform.

# Rationale

- `shared` transitively brings `kotlinx.datetime` to `androidApp` through the
  `implementation(project(":shared"))` dependency, BUT Gradle does not re-export
  transitive implementation dependencies to consumers of an `implementation` project.
  Only `api` (formerly `compile`) dependencies are visible downstream.
- Adding it directly to `androidApp` makes the classpath explicit and avoids
  relying on the unpredictable presence of transitive implementation deps.

# Consequences

- None — compile error resolved, no runtime behavior change.
- Detekt CI on androidApp source set now has access to datetime types if needed.

# Links

- PR2: DebugSeedActivity implementation
- kotlinx.datetime 0.8.0 — `plusDays`/`minusDays` are member functions, not extensions
