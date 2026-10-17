---
title: "detekt --auto-correct is inert in this Gradle configuration"
date: 2026-10-09
status: superseded
superseded_by: 2026-10-17-detekt-auto-correct-works-use-it
---

# detekt --auto-correct is inert in this Gradle configuration

> **Superseded** by [`2026-10-17-detekt-auto-correct-works-use-it`](2026-10-17-detekt-auto-correct-works-use-it.md).
> The conclusion below is wrong: `--auto-correct` does work in this configuration. The probe
> placed in the original experiment was itself the reason nothing changed (a file no rule covers
> is not corrected — "0 findings" was reported as "0 corrections", and the two were conflated).
> The recipe asks for the correcting pass again, and the pass is now automated before the
> detekt verdict in CI and in the pre-push hook. Kept for the record because the mistaken
> belief shaped two recipes and an automation decision.

## Context

The `just detekt-fix` recipe runs detekt with `--auto-correct` in a loop over 5 modules.
The ADR `2026-10-07-detekt-auto-correct-single-task` documented that `--auto-correct` on a
single-module task produced 0 file changes, and the recipe was rewritten as a result.

## Experiment (2026-10-09)

**Single-module** (`:detekt-rules`):
```bash
./gradlew :detekt-rules:detekt --auto-correct && git diff --stat
# Result: 0 changes
```

**Multi-module** (`:shared` + `:desktopApp`, with `--rerun-tasks`):
```bash
./gradlew :shared:detekt :desktopApp:detekt --auto-correct --rerun-tasks && git diff --stat
# Result: BUILD SUCCESSFUL (1m 34s, 5 tasks executed) but 0 changes
```

Both single-module and multi-module executions are inert. The `--rerun-tasks` flag
confirms the tasks actually executed, not served from cache.

## Decision

The auto-correct pass in `just detekt-fix` is ineffective. The loop runs but corrects nothing.
The recipe works around this by running a second, non-auto-correct pass after the loop,
which is the one that actually reports findings. The auto-correct loop is vestigial.

**The `just detekt-fix` recipe still produces correct results** — the second verification
pass is what matters, and it exits non-zero when violations exist. The first pass is
harmless but does nothing.

## Consequences

- The `for module in ...; do ./gw "$module:detekt" --auto-correct` loop in `detekt-fix`
  can be removed, leaving only the verification pass.
- Alternatively, the single-task limitation can be documented and the loop kept with a
  comment explaining why it runs but does not correct.
- The gate-honesty probe (`just honesty`) should be updated to test auto-correct
  effectiveness explicitly.

## Links

- ADR `2026-10-07-detekt-auto-correct-single-task` (single-task finding)
- `justfile` `.just/tests/mod.just` `detekt-fix` recipe
