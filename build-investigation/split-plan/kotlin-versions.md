# H9 — the fallback is not tied to the Kotlin version

Question: does a newer (or older) Kotlin compile the minimal KMP reproducer incrementally? If yes, the upgrade is the
lever and the whole split line of work stops.

Protocol: the committed reproducer `build-investigation/kt-62686/probe` (a one-file KMP module plus a `kotlin("jvm")`
control), the same forced → settle → body-only edit → measured cycle the ADR used, run through the shared instrument
(`cycle.sh` with `PROJECT_DIR`, `EDIT_HOOK` and `-PkotlinVersion`). The edit is byte-identical to the ADR's.

| Kotlin | verdict | task_s | evidence |
|---|---|---|---|
| 2.4.10 — the project's version, control | **fallback**, KT-62686 | 0.536 | `results/h9/h9-2.4.10/` |
| 2.4.20 | **fallback**, KT-62686 | 0.464 | `results/h9/h9-2.4.20/` |
| 2.4.21 — the latest 2.4.x | **fallback**, KT-62686 | 0.461 | `results/h9/h9-2.4.21/` |
| 2.5.0-Beta2 — the latest published | **fallback**, KT-62686 | 0.499 | `results/h9/h9-2.5.0-Beta2/` |

Every row reports `REBUILD_REASON: Incremental compilation might be incorrect (KT-62686)` and
`Classpath changes info passed from Gradle task: NoChanges`. Task time is irrelevant at one file; the verdict is the
report's.

Upstream, same day, via the YouTrack API: **KT-82893 — `UNSAFE_INCREMENTAL_CHANGE_KT_62686` — state Backlog,
unresolved, affected versions 2.2.21, no "Available in"**. So the behaviour predates 2.4.10 and no fixed release exists
to upgrade to.

## Gate A: crossed

The plan staged Gate A in two steps — the probe first, then one `:shared` cycle on that version if the probe turned
incremental — because a one-file fix would not have proved anything about `:shared`, whose plugin interaction the ADR
left untested. Here no version moved the verdict at all, so the second stage had nothing to confirm.

**Consequence: the Kotlin version is not a lever.** The compile-price question stays open and the cost model (H1/H2)
proceeds. `2.5.0-Beta2` is a release to watch, not an upgrade to plan.

## What this does not establish

- Only the probe was re-run per version; `:shared` itself was measured on 2.4.10 only (Phase 0). If a future release
  turns the probe incremental, Gate A's second stage is still owed before any upgrade is called a fix.
- The matrix is four versions, not every release: 2.4.10 is the project's, 2.4.20/2.4.21 are the latest patches of the
  same line, 2.5.0-Beta2 is the newest published. A regression that appeared and disappeared inside 2.4.x would be
  invisible here — but KT-82893's "affected 2.2.21" says this is not a recent regression.
