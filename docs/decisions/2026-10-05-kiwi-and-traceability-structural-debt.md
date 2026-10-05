---
title: Structural debt around the Kiwi stand and the traceability layer
date: 2026-10-05
status: accepted
tags: [kiwi, tcm, testing, infra, tooling, tech-debt]
---

## Context

Building the scenario traceability layer
(`2026-10-05-scenario-test-cases-in-kiwi.md`) turned up a handful of structural
problems in and around `infra/kiwi/`. None of them is a red test, and none was
cheap to fix inside that change, so they are recorded here instead of being
quietly left for the next person to rediscover.

Each entry states **what was checked**, because the failure mode for debt
inventory is an entry that decays into a claim nobody re-measured. Nothing below
is a hunch: every item was verified against this repository on 2026-10-05, and
each says how.

These are recorded rather than fixed because fixing them means changing
contracts that other work depends on. That is a decision, not an omission.

---

**Tracked as:** #155 (#1), #158 (#2), #149 (#3), #152 (#4), #153 (#5), #154 (#6).
Item 3 additionally carries an OpenSpec change, because it changes what CI
enforces rather than only how the code is arranged — see
`openspec/changes/scenario-results-are-authoritative-in-ci/`.

---

## 1. `infra/kiwi` is a directory of scripts, not a package

**Checked:** `find infra -name "__init__.py"` → empty. Every module assumes its
own directory is on `sys.path`: `sync.py` and `gaps.py` import `kiwi_client` at
top level with no setup, `prune.py` and the new `traceability` package each
insert the path themselves, and `scripts/tests/test_kiwi_sync.py` loads `sync.py`
through `importlib.util.spec_from_file_location` plus a manual `sys.modules`
registration (needed because `@dataclass` looks itself up at class-definition
time).

**Why it hurts:** the cost is paid again by every new consumer. Three
independent copies of the same path bootstrap exist, and the test loader needs a
non-obvious incantation that has to be re-derived rather than reused.

**What to try first:** add `infra/kiwi/__init__.py`, make the scripts thin
`__main__` shims, and let tests import normally. Sequence it *after* the legacy
`Automated/*` plans are frozen for good, because it touches every consumer at
once. `traceability.kiwi_module()` in `infra/kiwi/traceability/__init__.py` is
the one place the bootstrap now lives, so it is the natural seam.

---

## 2. `sync.sync_results` has no test at all

**Checked:** the only mentions of `sync_results` in `scripts/tests/` are two
prose comments, not a test. The untested surface is the FQN↔path join, the
ambiguous-FQN drop, and the per-class status rollup (inline, `sync.py` around
the `failed`/`skipped` branch) — plus the 24-hour staleness warning.

**Why it matters here specifically:** the traceability work needed the same
*concept* (outcome → Kiwi status) and had to invent a second table, because the
legacy one is inline and unreachable. The divergence that now exists
(`skipped → WAIVED` in `kiwi_publish`, `skipped → IDLE` in the legacy rollup) is
recorded in one place and is deliberate, but the legacy side of it is untested,
so a refactor could change it without anything noticing.

**What to try first:** extract the rollup into a module-level function and give
it a table-driven test before changing it. That is the minimum step that makes
the two mappings comparable, and it is a pure refactor with no behaviour change.

---

## 3. The `Scenarios` plan has no never-run floor

**Checked:** `config/docs/kiwi-gaps-baseline.txt` lists only the five
`Automated — *` plans. `check-kiwi-gaps.py` skips a plan with no line, so the
scenario plan is invisible to the never-run gate: adding a scenario case that has
never executed cannot fail anything.

**Why it matters:** a scenario is a *claim* that something is verified. A claim
with no run behind it is exactly the hole the coverage matrix is built to show —
but the coverage matrix is about automation existing, not about the automation
having run. The two together are the real gate, and only one of them is wired.

**What to try first:** decide whether the scenario plan belongs in that baseline
at all. It probably wants the opposite polarity — a scenario that has *never*
executed is normal (it may be new), whereas a scenario whose run is *missing for
a commit that claims it* is a real failure. That is a different gate, not a new
number in this file, so do not add a line before deciding which it is.

---

## 4. `ModalBottomSheet` is unreachable from a desktop JVM Compose test

**Checked:** attempting to reach selectors inside
`RecurrencePickerSheet` after adding `TestTags.RECURRENCE_OPTION_*` still failed
with "Tag … is not in the semantics tree" and an *empty* tag list, while the
sheet was open. Compose Multiplatform desktop renders a `ModalBottomSheet` into
a separate semantics root, so `onNodeWithTag` on the test's root cannot see into
it. `ConfirmActionDialog` is a real `AlertDialog` and **is** reachable —
`SavedAgendaEditFlowTest:190` asserts `TestTags.Dialog.CONFIRM` inside one.

**Why it matters beyond this feature:** any editor sheet in this app is
untestable from `desktopApp/jvmTest`. That is a whole class of UI that currently
has no JVM test path, and it is invisible until someone tries and burns an hour
on an apparently missing tag.

**What to try first:** confirm whether a `Popup`- or `Dialog`-based replacement
renders in the main tree on desktop, or whether `onAllNodes` across roots is
supported for this Compose version. If neither, the honest answer is that sheets
are Maestro-only surfaces, and that belongs in `Maestro/CONVENTIONS.md` so the
next author picks the right tier *before* adding tags.

---

## 5. `desktopApp` and `shared` test helpers are duplicated, structurally

**Checked:** `grep -rn "testFixtures" --include=*.kts .` → no matches.
`implementation(project(":shared"))` is main-only, so `desktopApp/jvmTest` cannot
see `shared/jvmTest` sources. Helpers such as `DesktopAssertions`/`awaitTag`
therefore exist in both source sets.

**Why it matters:** the same helper fix has to be made twice, and a divergence is
invisible because neither suite exercises the other's copy. It is also *why* the
scenario linkage had to be `@DisplayName` rather than a custom annotation — a
shared annotation would have needed this refactor first.

**What to try first:** a `testFixtures` source set in `:shared`, consumed by both
`jvmTest`s. Sequence it before any further shared-test-helper work; the payoff is
that future helpers are written once.

---

## 6. Unifying `@Tag`, Maestro tags and `TestTags` is still open

**Checked:** the project carries four overlapping tag systems — Kotlin `@Tag`
(`TestTagCoverageTest`), Maestro flow `tags:`, `Maestro/TAGS.md`, and
`TestTags.kt` selector ids — plus an allow-list in `check-tags.sh`. The scenario
work added one namespaced entry (`scenario:` / `TASK-*`) and a validator that
keeps it disjoint from `TestTags`; it did not unify anything.

**Why it is still separate:** a single registry would touch the tag gates, the
Maestro conventions and the selector inventory at once, and would be entangled
with items 4 and 5 above. Merging them while the sheet question and the fixtures
question are open would make the change unreviewable.

**What to try first:** items 4 and 5. Both shrink the surface this refactor has
to touch.

---

## Links

- `2026-10-05-scenario-test-cases-in-kiwi.md` — the change that surfaced all of
  the above
- `openspec/changes/scenario-results-are-authoritative-in-ci/` — the one item
  here (3) that changes a contract rather than an arrangement
- `infra/kiwi/README.md` — the stand's own documentation, including the layer
  split between the frozen `Automated/*` plans and the `Scenarios` plan
- `singularity-todo-kiwi-tcm-stand` skill — the operational summary, kept in step
  with the above
