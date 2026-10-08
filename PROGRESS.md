# Progress Journal

**Current state, not a history.** Finished epics live in `docs/progress-archive/`; this
file answers "where are we now and what is next".

Revised 2026-10-07: the "where things stand" table and the in-flight list were three days
stale and claimed 3 changes in flight while 48 directories sat in `openspec/changes/`. Seven
finished changes were archived on that date and the count is derived from the directory, not
remembered. See `## Where things stand` for what is measured now.

Rewritten 2026-10-05. The previous version was a 472-line chronological journal of 4
completed epics, every one still marked `**Status:** in progress` because nothing updated
the marker. A file that says "in progress" for work that merged weeks ago cannot be used
to decide what to do next — which is what a progress journal is for. Finished epics are
now in `docs/progress-archive/`, and this file carries a budget
(`PROGRESS_MAX = 300` in `scripts/check-doc-sizes.py`) so it cannot become that again.

## Where things stand

| Area | State |
|---|---|
| KMP architecture | Clean. expect/actual 32/32/32, no orphans; Android and JVM actual sets identical. |
| ViewModels | 28/28 on the injected `AutoCloseableCoroutineScope`. 0 use `viewModelScope`, 0 use `stateIn`, 0 unwired. |
| Detekt | 17 custom rules, all registered **and** all with a `detekt.yml` block. `:shared:detekt` passes with 0 findings. |
| Arch tests | 11 Konsist rules, 18 allowlist entries, every one ADR-annotated. |
| Tests | `:shared:jvmTest` passes: 326 classes / 2672 tests, 0 skipped. |
| OpenSpec | `openspec/specs/` holds the folded capabilities; 41 change directories remain in `openspec/changes/` (13 archived). **The count is not the state** — several are finished with unticked boxes, which is what the 2026-10-07 sweep measured. |
| Docs | 396 accepted / 9 deferred / 6 superseded ADRs; 14 findings docs archived. DIGEST 1215/1250. |
| Gates | One registry (`scripts/ci/static-gates.sh`), called by both CI's `static` job and `check.sh`. `requirement identifiers are unique` is **advisory and red today** — see the exit plan in `scripts/check-req-id-uniqueness.py`. |

## In flight

Swept on 2026-10-07 with a per-change verdict against the tree, because the checkboxes are not
evidence. The headline: **the checkbox is unmaintained.** `enqueue-failure-is-reported-at-the-seam`
reads `0/8` although the work is in `abf88fc2`; `entitlement-belongs-to-a-sync-scope` reads `0/16`
with the implementation present. So a change folder's `n/m` reads as "not started" for work that
shipped, and an unticked box is not a to-do.

Seven changes were verified complete and archived on that date:
`auth-outcome-is-reported-not-thrown`, `test-run-is-judged-by-its-results`,
`a-cycle-drains-the-feed-or-says-it-could-not`, `a-pull-outcome-is-not-always-success`,
`a-patch-states-the-version-it-builds-on`, `terminal-errors-are-not-retried-forever`,
`androidapp-debug-lint-policy`.

Remaining, tracked in `openspec/changes/`:

| Change | Tasks | Verdict from the sweep | Notes |
|---|---|---|---|
| `jvm-coroutine-diagnostics` | 22/24 | PARTIAL | Test-only diagnostics. Two open items are a global `@Timeout` and a convention plugin. |
| `add-log-export` | 3/28 | PARTIAL, **unchecked** | ~20 of 28 tasks landed while 3 boxes are ticked. `log-export-surface` is the same requirement written twice — see the advisory gate. |
| `apptracer-integration` | 40/44 | PARTIAL | Code landed; four checks need a device with credentials. |
| `entitlement-belongs-to-a-sync-scope` | 0/16 | PARTIAL, **unchecked** | Implemented at `Entitlement.kt` and covered by `EntitlementTest`; only the `single` binding note and the gate run are open. |
| `genui-catalog-contract`, `genui-answer-contract`, `scope-reporter-agreement`, `account-switch-and-clock-drift` | 23/24 … 20/39 | PARTIAL | Each is at "code done, a review or a gate run open". |
| `baseline-write-pipeline` | 5/13 | PARTIAL, **unchecked** | Documentation-only baseline; unticked WP-030/WP-031 are satisfied in code. |
| 12 changes | 0/N | NOT_STARTED | Verified absent, not merely unticked — e.g. `bulk-task-operations` (the use case exists and is Koin-bound, but nothing injects it), `theme-mode-offers-system-light-and-dark` (still a Boolean), `desktop-nav-goBack-blank-screen`. |
| 5 changes | — | Each declares in its own `tasks.md` why it is **not** archivable | e.g. `failure-visibility` — its requirements do not describe reality, so archiving would write a spec for behaviour that was never built. |

Three of these are honest about being unfinished; most of the rest are a ledger nobody maintains.
Before starting another: read `openspec/specs/MODULE-INDEX.md`. It lists covered **and**
uncovered modules, so it will tell you whether the change you are about to write needs a
spec — and OpenSpec's guidance is that you add it *because* the change touches that
module, never as a separate backfill exercise.

## Spec coverage is deliberately small

`openspec/specs/` holds one capability. That was the intended state on 2026-10-05; it now holds
more, because archived changes fold their deltas into it. That is the intended direction, not an oversight:

> Resist the urge to back-fill everything. Writing specs for code you aren't changing
> feels productive and usually isn't. Those specs go stale, because nothing forces them
> to track reality. Let real changes drive your specs.

`MODULE-INDEX.md` makes the gap list visible instead of implicit, which is the point: an
entry under **Not covered** means "the first change that touches this module adds the
spec", not "someone owes a spec".

## Recent work

| Date | What | Where |
|---|---|---|
| 2026-10-05 | Spec-governance sweep, phases 1–2: 10 governance defects fixed, gates made parse-and-fail, 24 stale ADRs resolved, 14 findings docs archived | `2026-10-05-doc-gates-must-parse-structure` |
| 2026-10-03 | Time hub + AI proposal confirmation, MR-0..MR-9 | `docs/progress-archive/2026-10-time-hub-ai-proposals.md` |
| 2026-10-03 | OpenSpec adoption, phases 0–7 infrastructure | `docs/progress-archive/2026-10-openspec-adoption.md` |
| 2026-09-26 | Docs + skills hygiene, 13 phases | `docs/progress-archive/2026-09-docs-and-skills-hygiene.md` |

## Known debt, not scheduled work

Audited 2026-10-05 and deliberately **not** actioned — each is a separate decision, not a
documentation defect:

- 7 custom detekt rules have no dedicated tests.
- `androidHostTest` is empty; Robolectric is provisioned but unused. Zero tests for
  Android actuals; 6 JVM actuals have no test reference.
- `androidApp` is on `detekt-minimal.yml`, so no custom rule applies to it.
- 42 `System.currentTimeMillis()` call sites.
- The ADR normalizer cannot round-trip 6 ADRs whose frontmatter uses YAML list or
  block-scalar values. They are reported and skipped, not corrupted.

Tracked in `docs/decisions/deferred-backlog.md`.

## Adding an epic here

One line in the table under **In flight**, with a link to its OpenSpec change. Move it to
`docs/progress-archive/` when it merges, and set a status. Do not paste phase tables back
in — that is how this file reached 472 lines while four merged epics were still labelled
`in progress`.
