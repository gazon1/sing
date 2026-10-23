---
title: "The gate registry carries controls as data; the floor that lived in Python now lives next to its constant"
date: 2026-10-15
status: accepted
tags: ["ci", "gates", "gradle", "kover", "registry"]
---

## Context

Two problems in the same area, found while planning a simplification of
`scripts/check-gate-wiring.py`.

**The coverage floor had two homes.** `detekt-rules/build.gradle.kts:114` declares
`DETEKT_RULE_BRANCH_FLOOR = 60` and Kover reads it. `check-gate-wiring.py` also
carried `_KOVER_MIN_FLOOR = 60` and re-derived the bound by brace-matching
`kover { }` blocks, resolving `val NAME = 60` with a regex. That second home was
the only thing checking the floor's value, because the positive control
`detekt-rule-coverage-floor` raises the constant by pattern and requires a
non-zero exit — so a floor lowered to 1 still passes the control: raising it to
100 still fails. And commit #650, which moved product coverage onto native
kover three hours before this work started, added three more floors
(`build.gradle.kts`, 27/20/27) that **nothing** checked at all, because the
check globs `*/build.gradle.kts` and the root file is one level above that.

**The control registry had five hand-maintained tables.** `SCRIPT_GATES`,
`FIXTURE_GATES`, `SABOTAGE_ONLY_GATES`, `GATE_EXEMPTIONS` and `GATE_PARITY` in
the gate script restated what `_registry.py` already declared. Nine defects came
out of that, among them: `cmd[1]` indexing that silently dropped two gates
(`detekt-registrations`, `skill-frontmatter` had no `cmd[1]` at all), an
exemption that said "already in SCRIPT_GATES … Part J is the backstop" for a
gate Part J therefore could not see, and a reachability fallback that made any
`sgate <anything>` mark every registered script reachable.

A third, smaller finding fits the same shape: `sgate` is an editable install, and
on this host `import sgate` resolved into a *sibling worktree* while the gate
under test was a different one. The wiring gate could have cross-checked another
checkout's registry and reported it clean.

## Idea

A registry should say what exists; code should say what to do. Put the
control's **declaration** in the registry and its **application** in the gate.
Put a floor's minimum next to the floor.

## Decision

- **The build file owns the floor's minimum.** `require(DETEKT_RULE_BRANCH_FLOOR >= 60)`
  sits beside the constant in `detekt-rules/build.gradle.kts`; the product floors
  became named constants in `build.gradle.kts` with the same `require` treatment.
  Verified: lowering the constant to 50 fails configuration with the intended
  message. The check runs on every configuration, so a mistake is a build
  failure rather than a passing gate.
  — *The minimum still stands beside its declaration: since 2026-10-23 that
  declaration is in `settings.gradle.kts`, where every coverage floor of this
  repository lives, and the module binds it (the amendment of 2026-10-23 in
  ADR `2026-10-15-kover-floor-lives-in-the-build-file`).*
- **`scripts/src/sgate/controls.py` holds types, `_registry.py` holds data.** The
  types module has no `subprocess`, no `os.utime` and no git — because the
  registry is imported by every gate, executable content there would be loaded on
  every `sgate run-all`. `Control` carries `proves` (the script it covers),
  `path`, `argv`, `why`, and a declarative `Edit`; the appliers stay in the gate.
- **Validation refuses unrepresentable states.** An `Edit` with nothing to match,
  a control with no command, an exemption with no reason, and a clean-run waiver
  that points at nothing are all rejected at import. The waiver rule is the one
  deviation from the plan: it accepts a `tracked_by` reference as well as an
  `until` date, because three waivers are not time-boxed at all — two are red
  because an imported corpus stores absolute paths, and the third is proved by
  the very next CI step. A date would have been invented.
- **The registry loader refuses a foreign `sgate`.** `_load_registry()` puts this
  checkout's `scripts/src` first and fails if the resolved package is not under
  `ROOT`. No fallback: an unimportable registry is a failed gate, never a skip.
- **`GATE_WIRING_GRADLE=1` now reaches CI.** The gradle-backed positive control
  was previously enabled only by `check.sh`, so in CI it had only ever been
  skipped — it proved nothing where it mattered most.

## Rationale

- A duplicated number is two chances to disagree while both readers keep passing.
  Removing the Python copy only works if something better takes its place, and an
  assertion at the declaration is the only thing that cannot be satisfied by a
  copy drifting.
- Executable registry content is a dependency in disguise. The registry is on the
  import path of all 46 gates; a control module that shells out would make every
  gate's cold start carry the machinery of the harness that tests them.
- "Points at nothing" is the failure mode worth blocking. A waiver that names an
  artefact whose re-import would end it is legible; one that names nothing is the
  same permanent-red advisory noise `check-gate-liveness` exists to catch,
  reproduced inside the registry.
- A gate that cannot find its own registry has no verdict. "Skipped" and "checked
  and green" are indistinguishable downstream, which is the exact confusion this
  file exists to prevent.

## Consequences

- `scripts/check-gate-wiring.py` still carries the old tables; Part J still reads
  its own `GATE_EXEMPTIONS`. The new registry is populated and validated, but the
  readers have not been flipped, so **this change is additive and the gate's
  behaviour is unchanged**. A known consequence is visible today: the live gate
  `scripts/tests` fails Part J with "no positive control", because the old
  exemption is keyed `unittest` while the registry knows the script as
  `scripts/tests`. That failure predates this work — it reproduces with these
  changes stashed — and the registry's exemption is already keyed correctly, so
  it resolves when the reader flips.
- 31 of 46 registry gates now carry a control; 7 controls cover scripts that are
  local-only and deliberately outside the registry, and 17 exemptions are
  recorded with their reasons.
- Two waivers are unchanged in substance and now point at what would end them:
  `openspec/changes/2026-10-06-publication-residue-is-a-gate` and
  `scripts/openspec-import-all.py`.
- All owed items are now complete. The readers were flipped in stranger step 3
  (PR #652); PyYAML replaced the workflow regex; `shlex` replaced
  `_strip_comments`; Click replaced argparse; sabotage in a disposable worktree
  with `GIT_CONFIG_*` was added in check-gate-honesty (PR #654); and the two
  test files were rewritten (PR #655 — `advisory_test_check_gate_wiring.py`
  renamed to `test_gate_wiring_advisory.py`, the bypass-detection test fixed,
  and the test that was invisible to `unittest discover` is now discoverable).
  The Gradle-side move of Parts A/C/I is wave (б) of the plan and was
  deliberately kept separate.

## Links

- `openspec/changes/detekt-rule-coverage-floor` — the change that put the floor in
  the build file.
- `docs/decisions/2026-10-15-kover-floor-lives-in-the-build-file.md` — §Consequences
  still describes `_KOVER_MIN_FLOOR` as deliberate friction; this supersedes that.
- `docs/decisions/2026-10-05-positive-control-registry-is-derived.md` — why the
  registry, rather than a list, is the right place for a control's declaration.
- `docs/decisions/2026-10-04-gradle-daemon-isolation.md` — why `./gw` and why a
  sabotage that runs Gradle cannot be moved into a worktree that shares
  `~/.gradle`.
- `scripts/src/sgate/controls.py`, `scripts/src/sgate/commands/_registry.py`.