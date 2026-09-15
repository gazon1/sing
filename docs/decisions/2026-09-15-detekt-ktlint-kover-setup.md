---
title: "Integrate detekt, ktlint, and kotlinx-kover for code quality and coverage"
date: 2026-09-15
tags: [detekt, ktlint, kover, lint, coverage, quality]
---

## Context

The Singularity Todo KMP project (560+ Kotlin files across `shared`, `androidApp`, `desktopApp`, `mcp-server`) had no static analysis, formatting enforcement, or code coverage tooling. Conventions were documented only in `AGENTS.md` but not enforced mechanically. Developers could introduce logic bugs, style violations, or untested code without any automated guard.

## Idea

Adopt three complementary Kotlin quality tools:
- **detekt 1.23.8** (stable 1.x line) for static analysis: logic, complexity, naming, performance, exceptions, style.
- **ktlint 12.x** (bundled via `detekt-formatting` plugin) for formatting and imports. One Gradle task (`detektFormat`) fixes both.
- **kotlinx-kover 0.9.9** (latest stable) for code coverage measurement on `shared` and `desktopApp`.

Roll out in **report-only mode first** (`ignoreFailures = true`), generate baselines from existing code, then tighten gates once violations are addressed.

## Decision

### Tool scope split

| Tool | Responsibility |
|---|---|
| ktlint (via `detekt-formatting`) | Formatting, imports, whitespace, line length (`max_line_length = 140` in `.editorconfig`) |
| detekt | Everything else: logic, complexity, naming, performance, exceptions, style rules not covered by ktlint |

Rationale: running ktlint separately from detekt would double-report formatting issues. The `detekt-formatting` plugin merges them so `detektFormat` auto-fixes both in one shot.

### Versions

- **detekt = 2.0.0-alpha.3** — pinned to a version with explicit Kotlin 2.3.21 support. The stable 1.23.x line was compiled against Kotlin 2.0.21 and refuses to run under Kotlin 2.3.21. Note: detekt 2.x removed `detektFormat` — auto-fix is merged into the `detekt` task itself when `auto_correct=true` in the `ktlint` config section. Upgrade to stable 2.x once released.
- **kover = 0.9.9** — latest stable. Supports AGP 9.x, Gradle 9.x, Android KMP libraries, and configuration cache.

### Configuration

Single shared `config/detekt/detekt.yml` used by both `shared` and `desktopApp`. Key rule adjustments for day-1 noise reduction:
- `MagicNumber`, `MatchingDeclarationName`, `MaxLineLength`, `UndocumentedPublicClass`, `UndocumentedPublicFunction` — disabled
- `LongMethod`, `LongParameterList`, `ComplexMethod`, `TooManyFunctions` — thresholds bumped
- `WildcardImport` — enabled (ktlint will clean up)

`.editorconfig` at root sets `max_line_length = 140` (JetBrains official), `ktlint_code_style = ktlint_official`.

### Baseline strategy

Run `detektBaseline` once during setup to capture all existing violations in:
- `config/detekt/baseline-shared.xml`
- `config/detekt/baseline-desktopApp.xml`

Subsequent `detekt` runs ignore baseline entries. Remove entries to enforce rules on those files. Promote to `ignoreFailures = false` once the codebase is clean.

### Kover scope

Covers `shared` (all KMP source sets: commonMain + jvmMain + androidMain + tests) and `desktopApp`. `androidApp` is a thin Android shell and excluded; `mcp-server` is a thin JVM wrapper.

Reports: XML (for CI / tools) + HTML (for human review). Both generated on every `koverXmlReport` / `koverHtmlReport` invocation.

### Automation

- **just recipes** (`.just/tests/mod.just`):
  - `just detekt` — run detekt analysis (report-only)
  - `just detekt-fix` — auto-fix detekt style + ktlint formatting
  - `just detekt-baseline` — regenerate baselines
  - `just lint` — alias for `detekt`
  - `just coverage` — generate kover XML reports
  - `just coverage-html` — generate kover HTML reports
  - `just tcheck` — runs `./check.sh` then `just lint`
- **`./check.sh`** — appends `detekt` (report-only) as step [5/5] after `androidApp:assembleDebug`

## Rationale

- Single `detekt.yml` for all modules avoids rule drift and is easier to evolve.
- `detekt-formatting` plugin eliminates double-reporting between detekt and ktlint on formatting rules.
- Baseline-first approach prevents a 560-file fix-up PR from blocking the initial setup.
- Kover scoped to `shared + desktopApp` where the business logic lives.
- Report-only initially reduces friction on first PR; gates can be tightened in a follow-up.

## Consequences

- **`.editorconfig` may rewrap existing code** on first `detektFormat` run. Expect a large diff; consider a separate "format" commit before merging.
- **detekt 2.0.0-alpha.3 vs Kotlin 2.3.21**: this version was chosen because stable 1.23.8 was compiled against Kotlin 2.0.21 and throws "detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.21". Upgrade to stable 2.x once released.
- **`ignoreFailures = true`** means violations are reported but never block builds. To enforce violations: set `ignoreFailures = false` in both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts` once baselines are settled.
- **New Gradle tasks added**:
  - `:shared:detekt` / `:shared:detektFormat` / `:shared:detektBaseline`
  - `:desktopApp:detekt` / `:desktopApp:detektFormat` / `:desktopApp:detektBaseline`
  - `:shared:koverXmlReport` / `:shared:koverHtmlReport`
  - `:desktopApp:koverXmlReport` / `:desktopApp:koverHtmlReport`
- **Configuration cache**: detekt 1.23.x and kover 0.9.9 are both CC-compatible. Verified by running `./gradlew --configuration-cache :shared:detekt`.

## Links

- `gradle/libs.versions.toml` — detekt and kover version pins
- `build.gradle.kts` (root) — `alias(libs.plugins.detekt)` and `alias(libs.plugins.kover)`
- `shared/build.gradle.kts` — detekt + kover configuration, `detektPlugins(libs.detekt.formatting)`
- `desktopApp/build.gradle.kts` — same
- `config/detekt/detekt.yml` — shared detekt ruleset
- `config/detekt/baseline-shared.xml` — baseline for `:shared`
- `config/detekt/baseline-desktopApp.xml` — baseline for `:desktopApp`
- `.editorconfig` — ktlint configuration
- `.just/tests/mod.just` — `detekt`, `detekt-fix`, `detekt-baseline`, `lint`, `coverage`, `coverage-html` recipes
- `./check.sh` — step [5/5] detekt invocation
- `.zcode/plans/plan-sess_9f3caf0f-26c0-432f-a74a-4d529ae939aa.md` — prior session plan that first proposed this setup
