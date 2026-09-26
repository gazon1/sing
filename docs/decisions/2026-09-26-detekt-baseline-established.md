---
status: accepted
date: 2026-09-26
review: after PR-0.3 (KDoc batch)
---

# detekt baseline established

## Context

After activating 7 custom detekt rule sets in PR-0.0 (no-runblocking, no-viewmodel-scope, no-real-delay-in-test, mvi-viewmodel, no-state-in, no-static-profile-aware-current-user, pass-through-use-case), the codebase had ~930 violations spread across shared (441 FunctionNaming, 61 BackingPropertyNaming, 47 LongMethod, 43 PackageNaming, 41 UnusedParameter, etc.) and desktopApp (1 FunctionNaming).

All new rules start in **warn-only mode** to avoid blocking CI on day 1. A baseline captures existing violations so new violations fail the build.

## Decision

1. **Generate baseline files** for all modules that apply the `:detekt-rules` plugin:
   - `config/detekt/baseline-shared.xml` — shared module (commonMain + androidMain + jvmMain + tests)
   - `config/detekt/baseline-desktopApp.xml` — desktopApp

2. **`warningsAsErrors: true`** in `config/detekt/detekt.yml`. Baseline-suppressed issues are exempt; new violations cause build failure. Modules without a baseline (androidApp, mcp-server) use `config/detekt/detekt-minimal.yml` which keeps `warningsAsErrors: false` — they are report-only on day 1.

3. **androidApp and mcp-server** use a separate `config/detekt/detekt-minimal.yml` that excludes all custom rule-set sections. These modules do not apply `:detekt-rules` and cannot run custom rules.

4. **Baseline generated** via `just detekt-baseline` (`./gradlew :shared:detektBaseline :desktopApp:detektBaseline`).

## Rationale

- `warningsAsErrors: true` without a baseline would fail the build on all ~930 existing violations — unacceptable for a hygiene PR.
- Baseline files are checked into source control so every developer gets the same suppressed violations.
- New violations from **new code** (PRs merging after baseline) will fail CI, enforcing the rule.
- Per-module baselines allow progressive cleanup: shared/desktopApp first, androidApp/mcp-server after PR-0.3.

## Consequences

- `./check.sh` will fail if a PR introduces a **new** detekt violation (not in baseline) in shared or desktopApp.
- Developers must run `just detekt-fix` before committing new code to auto-fix style violations.
- The ~930 baseline violations are **technical debt**. A dedicated cleanup campaign (PR-0.3 or follow-up) should address the top categories: FunctionNaming, BackingPropertyNaming, LongMethod, PackageNaming.
- After PR-0.3 KDoc batch, regenerate baselines with `./gradlew :shared:detektBaseline :desktopApp:detektBaseline` to capture the cleaner state.
