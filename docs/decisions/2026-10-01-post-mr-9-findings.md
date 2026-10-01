---
title: "Post-MR-9 findings — Convention plugins"
date: 2026-10-01
tags: [build, convention-plugins, mr-9]
status: accepted
---

# Post-MR-9 audit findings

## MR-9: Convention plugins — partial

**Decision**: Document reference implementations; defer wiring to future Gradle version.

### What was done

- 4 convention plugin source files written with full migration documentation:
  - `CommonDepsConventionPlugin` — centralises version-pinning
  - `KotlinMultiplatformLibraryConventionPlugin` — `shared` module
  - `AndroidApplicationConventionPlugin` — `androidApp`
  - `JvmApplicationConventionPlugin` — `desktopApp`, `mcp-server`
- `build-logic/README.md` with step-by-step migration instructions
- CI still uses plugin alias approach (no breaking change)

### Why not wired

Gradle 9's included-build classpath isolation creates a chicken-and-egg:
1. Convention build needs plugin repos from root's `pluginManagement`
2. Convention plugins published via `gradlePlugin { }` can't be resolved from root until the convention build is compiled
3. Compiling the convention build requires Gradle to already be resolving the root build's plugins
4. Removing `includeBuild` from settings breaks the main build; keeping it prevents the convention build from compiling

This is a known Gradle limitation. Workarounds exist but add complexity outweighing the benefit for a 6-module project.

### Action items

- **Wire convention plugins** when project reaches 10+ modules, or when Gradle adds native support for convention plugin discovery
- **Prefer `build-logic/` over `buildSrc/`** when wiring — the documented structure is correct, just blocked by Gradle 9 classpath

### Related

- `build-logic/README.md` — full migration path
- `docs/decisions/2026-10-01-tech-debt-reconciled.md` — Cluster 9 (package convention) already handled
