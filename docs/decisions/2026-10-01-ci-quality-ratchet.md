---
title: "CI quality ratchet: FailureBundle upload + PR-only test retry"
date: 2026-10-01
status: accepted
tags: [ci, testing, desktop-compose, diagnostics, retry]
---

# CI quality ratchet: FailureBundle upload + PR-only test retry

## Context

Two gaps from the MR-1 plan and the diagnostic infrastructure built in
`2026-09-30-desktop-test-diagnostics.md`:

1. **FailureBundle** writes screenshots, DB dumps, and Kermit logs to
   `desktopApp/build/diagnostics/<TestClass>/attempt-N/`, but the CI workflow
   uploaded only the kover XML report. When a runner disappears after a build,
   the diagnostics are lost — precisely the data needed to diagnose the flake.

2. **Retry policy** was never wired. All three CI jobs run identically on
   `pull_request` and on `push` to `main`. A flaky test blocks a PR merge
   even if it passes on the second attempt, while a genuine regression on `main`
   should fail loudly rather than silently retry and eventually pass.

## Decision

### 1. Upload FailureBundle on every build

```yaml
# .github/workflows/ci.yml — test-and-check job
- name: Upload desktop failure bundle
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: desktop-failure-bundle-${{ github.run_id }}
    path: desktopApp/build/diagnostics/**
    if-no-files-found: ignore
    retention-days: 14
```

- `if: always()` — uploads on green and red builds alike; artifacts survive the
  runner being killed.
- `if-no-files-found: ignore` — the directory only exists when a test has
  failed and the harness caught it; a green build produces no directory and this
  step silently skips.
- `retention-days: 14` — flakes are actionable within two weeks; 90-day default
  is wasteful.
- `${{ github.run_id }}` in the artifact name prevents collisions when the same
  workflow re-runs.

### 2. Differentiated retry policy

```yaml
# .github/workflows/ci.yml — Compute retry policy step
- name: Compute retry policy
  id: retry
  run: |
    if [[ "${{ github.event_name }}" == "pull_request" ]]; then
      echo "max=2" >> "$GITHUB_OUTPUT"
      echo "strict=false" >> "$GITHUB_OUTPUT"
    else
      echo "max=0" >> "$GITHUB_OUTPUT"
      echo "strict=true" >> "$GITHUB_OUTPUT"
    fi
```

Both `Run jvmTest` and `Run desktopApp:test` receive:
```
-Pretry.maxAttempts=${{ steps.retry.outputs.max }}
-Pretry.failOnPassedAfterRetry=${{ steps.retry.outputs.strict }}
```

Gradle build configuration in both `shared/build.gradle.kts` (jvmTest task) and
`desktopApp/build.gradle.kts` (all Test tasks):

```kotlin
val retryMax: Int = (project.findProperty("retry.maxAttempts") as String?)?.toIntOrNull() ?: 0
val retryStrict: Boolean = (project.findProperty("retry.failOnPassedAfterRetry") as String?)?.toBoolean() ?: false
if (retryMax > 0) {
    retry {
        maxRetries = retryMax
        failOnPassedAfterRetry = retryStrict
    }
}
```

| Context | maxRetries | failOnPassedAfterRetry | Effect |
|---|---|---|---|
| `pull_request` | 2 | false | Transient flakes retry and pass; PR is unblocked |
| `push` to `main` | 0 | true | Tests run exactly once; any failure fails the build |

## Rationale

- `if: always()` for artifact upload is the standard GitHub Actions pattern for
  debug artifacts that may not exist on every run.
- `if-no-files-found: ignore` is the documented complement for optional artifacts.
- Distinguishing PR from main by `github.event_name` is the canonical seam in
  `ci.yml`; no new job or matrix is needed.
- `failOnPassedAfterRetry = false` on PRs aligns with the guidance in the
  Gradle documentation: "use this to unblock PRs on flaky infrastructure."
- `failOnPassedAfterRetry = true` on main is the conservative choice: a test
  that only passes after retry is a regression signal even if it eventually
  reports green.

## Consequences

- **Positive:** CI runners that disappear now leave behind a downloadable
  `desktop-failure-bundle-<run_id>` artifact with screenshot, DB dump, and log.
- **Positive:** PR flakiness no longer blocks merges; main regressions are not
  masked by retry.
- **Known limitation:** `CalendarFlowTest.every_day_of_the_month_has_an_addressable_cell`
  is a pre-existing failure (noted in `nodate-fix.md:101`). With `maxRetries=0`
  on main, this test will fail the main build and should be addressed separately.
  See `projects-flow-one-time-flake` in `deferred-backlog.md`.
  *(Note: the test body was later rewritten to assert `awaitAnyDisplayed(calendarDay(midMonth.toString()))` and today's cell — the original failure mode was addressed. The test may now be green; verify with a run.)*
- **Known limitation:** `Find unwired surfaces` and `Run detekt` still run under
  `continue-on-error: true` in the `test-and-check` job. The roadmap for
  flipping them one-by-one with a baseline is in `deferred-backlog.md` under
  `ci-gates-are-all-continue-on-error`. This ratchet does not address it.
- **Known limitation:** `Run Android debug` assemble also carries
  `continue-on-error: true` — not a test, but same pattern.
- **Artifact naming:** `desktop-failure-bundle-${{ github.run_id }}` ensures unique
  names within a workflow run but may accumulate artifacts on very active
  repositories. `retention-days: 14` mitigates storage growth; a cleanup job
  is out of scope for this ADR.

## Links

- `2026-09-30-desktop-test-diagnostics.md` (FailureBundle infrastructure)
- `deferred-backlog-archive.md#ci-gates-are-all-continue-on-error`
- `deferred-backlog-archive.md#projects-flow-one-time-flake`
- `docs/decisions/2026-09-30-nodate-fix.md:101-102`
