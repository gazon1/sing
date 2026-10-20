---
title: "The pre-push detekt pass re-runs detekt, not the build"
date: 2026-10-20
status: accepted
tags: [build, detekt, tooling, process]
---

# The pre-push detekt pass re-runs detekt, not the build

## Context

The pre-push hook (`.githooks/pre-push`) runs a detekt auto-correct pass over the current
worktree before a push. It is the one deliberate exception to the hook's rule of checking
the tree that is being pushed: a detached throwaway tree is thrown away a second later, so a
correction made there would be lost.

The pass was invoked as:

```bash
./gradlew "$task" --auto-correct --rerun-tasks --no-daemon --no-parallel --console=plain
```

`--rerun-tasks` re-runs the **whole task graph** of the invocation. For
`:shared:detekt` and `:desktopApp:detekt` that means every Kotlin compilation of both
modules — the whole of `:shared` and `:desktopApp` recompiled from scratch on every push —
only for detekt to re-analyse sources that were compiled anyway.

The hook's own comment claimed the detekt step costs "~2 min". It did not. Measured on this
tree on 2026-10-20, the `--rerun-tasks` invocation was **still running after 18 minutes** and
was stopped; the same work with `--rerun` takes **2 min 3 s**.

The flag was not there for decoration: without a forced re-analysis Gradle reports the task
UP-TO-DATE and corrects nothing, which is what ADR `2026-10-17-detekt-auto-correct-works-use-it`
records. The intent was right, the flag was too broad.

## Decision

`--rerun` replaces `--rerun-tasks`.

`--rerun` re-runs **the task it is given** and leaves its dependencies alone: detekt is
re-analysed, the compilations stay up-to-date. Gradle reports it as
`5 actionable tasks: 2 executed, 3 up-to-date` — the two detekt tasks and nothing else.

`--no-parallel` went with it: it was there to keep the forced re-run from competing with
itself, and the forced re-run of the whole graph is gone. `--no-daemon` stayed: a daemon
holds locks on a project's build directory, and this repository deletes worktrees.

## Rationale

The hook's contract is stated in its own header: *"Pre-push must be fast enough to run on
every push without making people reach for --no-verify."* A step that takes 18 minutes
breaks that contract in the only way that matters — the author's response to a hook that
blocks for a quarter of an hour is `--no-verify`, which costs every other gate too.

The measurement is the argument, not the flag's semantics: 18+ minutes versus 2 minutes for
the same outcome.

## Consequences

- The detekt step of a push costs about two minutes instead of a quarter of an hour.
- The hook's own claim ("~2 min") is true again, which matters: a comment that says 2 min and
  a step that takes 18 is a discrepancy that hides the regression until someone waits it out.
- A hook that blocks on 141 now says so. `141` is SIGPIPE — a pipeline closed before it
  finished writing — and under `pipefail` a pipeline reports it. It is not a gate verdict,
  and the cleanup trap names it as such, including the possibility that the 141 came from the
  SSH transport rather than from the hook.
- Both exit paths of the hook end with the same marker, `=== Pre-push OK ===`. A marker that
  appears on only one path is a marker a reader has to know about.

## Links

- `.githooks/pre-push`
- `docs/decisions/2026-10-17-detekt-auto-correct-works-use-it.md` — why a forced re-analysis
  is needed at all
