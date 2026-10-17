---
title: CI: detekt auto-fix commits its own result to a feature branch
date: 2026-10-17
status: accepted
tags: [ci, detekt, automation, workflow]
---

## Context

Detekt reports are deterministic for a given codebase snapshot, but the `--auto-correct` flag rewrites source files in place. In CI, this creates an asymmetry: the `static` and `tests` jobs run detekt auto-correct, but the rewritten files were never committed — only reported. Any developer pulling the branch after a green CI run would see fresh violations that CI claimed to have fixed.

## Idea

When detekt auto-correct makes changes, commit those changes to a dedicated feature branch (`detekt-auto-fix/<job>/<run-id>`) and force-push it. CI always passes (the script exits 0), but the commit history of that branch is the source of truth for what was auto-corrected. A human can review the diff, and the branch can be merged via PR when appropriate.

## Decision

- `scripts/ci/detekt-auto-fix.sh` runs detekt `--auto-correct` on all modules in a single Gradle invocation (one JVM start).
- If detekt finds and corrects issues, the script creates a commit on `detekt-auto-fix/<job>/<run-id>` and force-pushes.
- If no issues are found, or if detekt itself fails (non-zero exit), the script exits 0 — it never causes CI to fail.
- The script always runs as the **last step** of the `static` and `tests` jobs, so it captures all changes from previous steps.

## Rationale

### Why not fail CI on findings?

Detekt debt is a known, managed state. Forcing CI red on auto-correctable findings creates noise without solving the root cause. The goal is clean code, not a clean CI badge.

### Why commit to a separate branch?

Committing directly to the PR branch would bypass review. Force-pushing to `detekt-auto-fix/<job>/<run-id>` preserves the main branch's history while making the fix visible and mergeable.

### Why exit 0 always?

The script is a **mitigation** tool, not a gate. Its job is to reduce debt, not to punish accumulation. Exiting 0 ensures it never blocks merges.

## Consequences

- **Positive**: Auto-corrected debt is visible, reviewable, and mergeable. The diff is the report.
- **Positive**: No CI noise from managed detekt debt.
- **Negative**: A developer pulling `main` after a green run will see the same debt until they merge the fix branch. Mitigation: the fix branch is labeled clearly and can be merged in one click.
- **Negative**: Force-push to a per-run branch creates many ephemeral refs. Mitigation: branches older than 7 days can be pruned automatically.

## Alternative considered: commit to PR branch directly

**Rejected** because it bypasses review and would make the PR history noisy with automated commits.

## Alternative considered: exit non-zero on findings

**Rejected** because it converts managed debt into CI failures, without solving the underlying discipline problem.

## Links

- `scripts/ci/detekt-auto-fix.sh` — the implementation
- `ci.yml` — `static` job (lines 133–154), `tests` job (lines 233–241)
