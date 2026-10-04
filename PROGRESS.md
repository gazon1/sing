# Progress Journal

**Current state, not a history.** Finished epics live in `docs/progress-archive/`; this
file answers "where are we now and what is next".

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
| Tests | `:shared:jvmTest` passes. 28 script-level gate tests pass. |
| OpenSpec | 1 capability spec (`nav/nav3-entry-dispatch`). 3 changes in flight. `validate --all --strict` passes 4/4. |
| Docs | 396 accepted / 9 deferred / 6 superseded ADRs; 14 findings docs archived. DIGEST 1215/1250. |
| Gates | 8 blocking gates green; `just docs-audit` exits 0. |

## In flight

Three OpenSpec changes, tracked in `openspec/changes/`:

| Change | Tasks | Notes |
|---|---|---|
| `jvm-coroutine-diagnostics` | 22/24 | Test-only diagnostics infrastructure. `skip_specs: true` — it changes no runtime behaviour a capability spec would describe. Nearest to done. |
| `add-log-export` | 3/28 | Smallest real end-to-end change. Good candidate for the next spec to land. |
| `baseline-write-pipeline` | 0/13 | Not started. Would produce the `core/write-pipeline` spec, absorbing `2026-09-21-generic-user-scoped-repository` and `2026-09-27-write-layer-soundness`. |

Before starting a fourth: read `openspec/specs/MODULE-INDEX.md`. It lists covered **and**
uncovered modules, so it will tell you whether the change you are about to write needs a
spec — and OpenSpec's guidance is that you add it *because* the change touches that
module, never as a separate backfill exercise.

## Spec coverage is deliberately small

`openspec/specs/` holds one capability. That is the intended state, not an oversight:

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
