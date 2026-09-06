---
title: "Bump Compose Multiplatform plugin and libs to 1.12.0"
date: 2026-09-06
tags: [compose, gradle, build]
---

## Context

The `checkJvmMainComposeLibrariesCompatibility` task warned at `./gradlew :desktopApp:run`:
```
expected: 'org.jetbrains.compose.foundation:foundation:1.11.1'
actual:   'org.jetbrains.compose.foundation:foundation:1.12.0'
```
Plugin and all explicit libs were pinned to `composeMultiplatform = "1.11.1"` in `gradle/libs.versions.toml`. Two third-party libraries pull the 1.12.0 JetBrains compose modules transitively:

- `com.mohamedrejeb.richeditor:richeditor-compose:1.2.0` — declares `requires: 1.12.0` for `foundation`, `runtime`, `material`.
- `io.coil-kt.coil3:coil-compose-core:3.6.1` — declares `requires: 1.12.0` for `foundation`.

Gradle's default conflict resolution picks the highest matching version, so 1.12.0 jars were resolved even though the plugin was 1.11.1.

## Idea

Three options were considered:

1. **Bump plugin + explicit libs to 1.12.0** — aligns everything with what the resolver already chose. One-line version bump.
2. **Add `strictly("1.11.1")` constraints** via `configurations.all { dependencies { constraints { … } } }` — forces Gradle to downgrade the already-resolved 1.12.0 jars. A forever-fight against the resolver on every build.
3. **Downgrade richeditor-compose and coil3** to versions compatible with 1.11.1 — most invasive; coil3 3.6.1 would need to drop to an older major version with fewer features.

## Decision

Bump `composeMultiplatform` to `1.12.0` and `material3` to `1.12.0-alpha03` in `gradle/libs.versions.toml`. All JetBrains compose library entries already use `version.ref = "composeMultiplatform"`, so they follow automatically. `material-icons-extended` keeps its separate `1.7.3` pin (AndroidX artifact, unrelated group).

## Consequences

- All JetBrains compose library versions MUST track `version.ref = "composeMultiplatform"`. Split-version declarations are forbidden unless the artifact is an AndroidX (not JetBrains) group.
- When adding a new third-party Compose dependency, verify its JetBrains compose `requires:` constraint in the Gradle module metadata (`.module` file in cache) before adding — if it demands a version newer than the current pin, either bump or find an alternative.
- The `checkJvmMainComposeLibrariesCompatibility` task must pass silently on every PR.

## Links

- `gradle/libs.versions.toml` — composeMultiplatform version
- Gradle module metadata for `richeditor-compose:1.2.0` and `coil-compose-core:3.6.1` — transitive `requires: 1.12.0` source
