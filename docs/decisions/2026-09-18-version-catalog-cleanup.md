---
title: "Version catalog cleanup — kebab-case, bundles, single resolutionStrategy"
date: 2026-09-18
tags: [gradle, version-catalog, build-config]
---

## Context

During a Gradle health audit we found 42 issues in `libs.versions.toml` and related build scripts:
- Inconsistent naming: `kotlinx-coroutinesSwing` (camelCase), `compose-uiTooling` (mixed), `kotlin-testJunit` (mixed)
- Dead entries: `supabaseVersion` (orphan duplicate), `koin-ksp-compiler` (Beta, INCOMPATIBLE with koin 4.x), `roborazzi` (never used), `compose-runtime-liveedit` (never used), and 6 more
- Hardcoded version literals bypass the catalog: `androidx.navigation3:navigation3-runtime-android:1.1.1` in `shared/build.gradle.kts`, `io.modelcontextprotocol:kotlin-sdk:0.15.0` in `mcp-server/build.gradle.kts`
- Duplicate `resolutionStrategy` in both root `build.gradle.kts` and `shared/build.gradle.kts`
- Broken accessor: `libs.versions.android.compileSdk` referenced a non-existent nested path; TOML had flat keys (`android-compileSdk`)
- `org.gradle.warning.mode=none` silently hid Gradle 9.x deprecation warnings

## Decision

1. **Kebab-case for all TOML keys** — all library and version keys use `kebab-case` (e.g., `kotlin-test-junit`, `compose-ui-tooling`, `kotlinx-coroutines-swing`). Accessors generated from kebab keys are predictable: `libs.compose.ui.tooling`, `libs.kotlin.test.junit`.
2. **Dead code removal policy** — unused version keys, library entries, and plugin aliases are deleted in the same commit that identifies them. Commented-out dependencies that reference deleted catalog entries are also removed.
3. **Single `resolutionStrategy` in root `build.gradle.kts`** — all version alignment rules live in root `allprojects {}` block using `libs.versions.*.get()`. Module-level `resolutionStrategy` blocks are removed.
4. **Bundles for always-together groups** — bundles are added only when all members are consistently used together across every source set that declares the bundle. Four bundles added: `compose`, `koin`, `ktor`, `datastore`.

## Rationale

- **Kebab-case**: Gradle's version catalog generates nested accessors from kebab keys (`a-b-c` → `libs.a.b.c`), which is the most predictable mapping. CamelCase keys like `kotlinx-coroutinesSwing` would generate `libs.kotlinx.coroutinesSwing` with no dots — inconsistent and harder to navigate.
- **Dead code removal**: Commented-out dependencies referencing missing catalog entries create a false sense of completeness. `koin-ksp-compiler` was explicitly documented as INCOMPATIBLE with the current koin version — keeping it invited confusion.
- **Single resolutionStrategy**: Two blocks meant two places to maintain, and the local block duplicated serialization rules already in root. Centring alignment in one place reduces drift.
- **Bundles**: The four added bundles (`compose`, `koin`, `ktor`, `datastore`) each contain libraries that are declared together in every source set that uses any one of them. This is a pure convenience — bundles do not change resolved graph.

## Consequences

- All TOML keys follow `kebab-case` naming convention. New entries must use kebab-case.
- Before adding a new dependency, check if the library entry already exists in `libs.versions.toml`. Hardcoded `group:artifact:version` strings in `build.gradle.kts` are a code smell.
- `resolutionStrategy` additions go in `build.gradle.kts` (root) only. Never add a second `configurations.all { resolutionStrategy }` in a module.
- When adding a bundle, confirm all members are used together in every relevant source set. A bundle that partially applies is worse than no bundle.
- Gradle deprecation warnings are now visible (`warning.mode=summary`). Warnings from AGP 9.x, Kotlin 2.3.x, and KMP 1.12.x should be reviewed periodically.
- `android.useAndroidX=true` removed from `gradle.properties` — it has been the default since AGP 4.x.
- Android SDK versions use `sdk-compile` / `sdk-min` / `sdk-target` keys (accessor: `libs.versions.sdk.compile` etc.). Keys starting with `android` are avoided because library aliases like `androidx-android-*` shadow the version accessor.
- Library keys starting with `kotlin-` are avoided if possible, because they shadow `libs.versions.kotlin` accessors. If unavoidable, use direct string constants in `resolutionStrategy` rules.

## Links

- Commit: `905cf1f` (DSL selector composer + Map-based SelectorSerializer + shared UI adoption)
- Files changed: `gradle/libs.versions.toml`, `build.gradle.kts`, `shared/build.gradle.kts`, `androidApp/build.gradle.kts`, `desktopApp/build.gradle.kts`, `mcp-server/build.gradle.kts`, `gradle.properties`
- Related: `docs/decisions/DIGEST.md`
