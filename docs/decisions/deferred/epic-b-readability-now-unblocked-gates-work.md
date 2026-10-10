---
title: "Epic B Readability Now Unblocked Gates Work"
date: 2000-01-01
status: RESOLVED
tags: ["deferred"]
---

**Status: RESOLVED (2026-10-04).** B1 (formatter merge), B2 (`TaskDetailDeps` split) and B5 (desktop harness split) are all done. B3 and B4 were phantom work and are struck in the entry below rather than carried forward.
**Found in:** 2026-10-04, after the verifiability work. Recorded because the
ordering argument for it changed, not because the items are new.

**Why it is worth doing now.** For the whole first phase of this project the
refactoring backlog was not a symptom of a bad design — it was a symptom of gates
that never ran. `TestTagsWiringTest`, `ArchitectureTest`, `HarnessConventionTest`
and the detekt rule tests all existed and none of them executed. Any
readability refactor was therefore unfalsifiable: the diff passed because nothing
checked it. That is no longer true — 1411 shared + 77 desktop tests, 90 rule
tests, and `check-gate-wiring.py` / `check-rule-intent.py` all run in CI. A
refactor now has somewhere to fail.

**Status: two of four items were phantom work and are struck below.** They were
carried in this backlog through several rewrites without anyone measuring them.
Measuring the *symbols* instead of the *files* is what caught it:

| Item | What was claimed | What is actually true | Verdict |
|---|---|---|---|
| B2 `TaskDetailDeps` split | 25 ctor params, 4 sites, 16 files | **24 params** (counted), 16 referencing files. Confirmed. | Real — **done 2026-10-04** |
| B3 decompose 4 composables | "451 / 270 / 225 / 212 lines" | Those were **file** sizes. The composables are 196 (`TaskDetailViewScreen`), 188 (`BackupScreen`), then ≤62. Nothing is near the `LongMethod` limit of 80. | **Phantom — dropped** |
| B4 `testTask()` fixture | "0 uses today" | 8 calls in 6 files (`CommonFakes`, `TasksRobot`, 3 test classes). | **Phantom — dropped** |
| B5 split `DesktopNavigation.kt` | 510 lines, 29 helpers | Confirmed exactly. | Real — **done 2026-10-04** |

The B3 and B4 numbers were never re-measured after the first draft; they were
copied forward and re-copied. B4 in particular claimed a fixture was unused when
the skill `singularity-todo-desktop-compose-ui-tests` documents it as the standard
seed (`testTask()` defaults to `TestUsers.DEFAULT`) — the two documents
contradicted each other and the backlog won by being written last. **Try this
first when a backlog item survives a rewrite:** `grep` the symbol. If the claim is
a count, re-count it.

**Not done in the 2026-10-04 pass** — B1 landed instead (see
`2026-10-04-…` for the formatter merge, which was self-contained). B2–B5 are
recorded here rather than started, because a 16-file refactor that cannot be run
to completion and verified leaves the tree worse than not starting it.




**B5 — done (2026-10-04).** `DesktopNavigation.kt` (510 lines, 29 helpers) split
into `DesktopNavigation.kt` (135, drawer + `DesktopShell`) ·
`DesktopAssertions.kt` (328, `await*`/`assert*` + `TIMEOUT_MS` + `TAG_PATTERN` +
`explainMissingTag`) · `DesktopInteractions.kt` (61, `click*`/`type*`).
Zero-behaviour move: all 29 signatures diffed identical before/after, and
`jvmTest` **is** covered by `:desktopApp:detekt` (`source.setFrom("src/main/kotlin",
"src/jvmTest/kotlin")`) so the split is linted, not just compiled. Verified by a
full `:desktopApp:test` run — 27 classes / 77 tests, 0 failures.

Note for the next splitter: `TooManyFunctions` excludes `**/jvmTest/**` and
`LargeClass` allows 600 lines, so a 510-line test helper was **not** a detekt
violation. It was split for readability, and detekt staying green through the
split is not itself evidence the split was warranted.

**B2 — DONE (2026-10-04).** `TaskDetailDeps` split into six bundles, and each
slot's constructor was narrowed to the bundles it actually reads:

**Also worth doing, cheap:** `TaskDetailState.kt` contains **no**
`TaskDetailState` — it holds only `TaskDetailDeps` (`grep -rn "class TaskDetailState"`
returns nothing). Either rename the file to `TaskDetailDeps.kt` or restore the
state class it was named for. Do this together with B2, which edits the file
anyway.




| Bundle | Fields | Read by |
|---|---|---|
| `TaskCoreDeps` | 4 | coordinator, draft, entity, completion, children, lifecycle, reminders |
| `TaskChildrenDeps` | 4 | entity, children |
| `TaskSchedulingDeps` | 3 | reminders, lifecycle |
| `TaskCollaborationDeps` | 6 | coordinator, AI, backlinks, logbook, time slot |
| `TaskAiDeps` | 5 (all nullable) | AI only |
| `TaskContextDeps` | 1 | coordinator, draft, completion, children, reminders, AI |

The grouping came from grepping each slot for `deps.X`, not from taste — that is
why `TaskChildrenDeps` merges checklist/attachments/projects/tags while
`TaskSchedulingDeps` stays separate, and why the coordinator is the only holder
of the full aggregate. The old flat class had 24 constructor parameters and every
slot held the whole bag, so `TaskAiSlot` could reach the reminder scheduler and
nothing would have failed if it had.

**The narrowing is the point, and it is checkable:** no slot file mentions
`TaskDetailDeps` any more (the coordinator is the sole holder), and every bundle
is at or under detekt's `allowedConstructorParameters: 8`. The 24-parameter class
had been invisible to `LongParameterList` only because `ignoreDataClasses: true`.

`TaskDetailState.kt` → `TaskDetailDeps.kt` in the same commit: the file held no
`TaskDetailState` at all (`grep -rn "class TaskDetailState"` returns nothing), so
renaming it is the honest fix rather than inventing a class to justify the name.

**Worth recording about the mechanics.** The refactor itself produced 293 detekt
findings — every one formatting, from a scripted edit of 29 call sites. None were
baselined. `--auto-correct` took it to 98, and the last 98 needed hand-fixing for
two reasons worth knowing: ktlint's `indent` rule is configured
`auto_correct: false` in this repo (deliberately, JDK-NPE workaround), and
auto-correct does not reformat a call that mixes named and positional arguments.
The fix that worked was making every argument named. `:shared:jvmTest` stayed
green throughout, which is the point — the gates now have somewhere to fail.

---
