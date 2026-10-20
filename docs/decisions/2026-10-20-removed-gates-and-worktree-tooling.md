---
title: "The log-interpolation gate, the gate-honesty probing and the worktree tooling are gone"
date: 2026-10-20
status: accepted
tags: ["tooling", "ci", "gates", "just"]
---

## Context

Three surfaces came up for removal in one request, and they are unrelated to each other.
Only the first is a gate; the second is a gate *about* gates; the third is not a gate at
all.

### The `log interpolation allowlist` gate

The gate policed one thing: a log call that interpolates a value of a type nobody
allowlisted. It was registered as `log interpolation allowlist` in
`scripts/src/sgate/commands/_registry.py`, it ran from `scripts/ci/static-gates.sh` as
`python3 scripts/check-log-interpolation-allowlist.py`, and it carried a positive control
in both registries: a `sabotage` that planted a `log.i { "planted=$Task" }` line in
`RemoteConfigCacheRepositoryImpl.kt`, and a `ScriptGate` entry in
`scripts/check-gate-wiring.py` that did the same thing.

Its history is a history of the allowlist being hard to keep honest. The allowlist is a
code-embedded constant (`ALLOWLISTED_TYPES`), and the gate's own detector was rewritten
on 2026-10-19 because it scanned line by line and therefore missed 28 of 74
interpolations. The replacement control planted a violation — the previous one *deleted*
an allowlist entry, which changed nothing, so it reported a pass for a gate that had
never been shown to fail.

What it actually guards is narrow: a log line that interpolates a domain value. The
sibling control for that — `scripts/check-doc-dead-refs.py` — is a different shape
entirely, and the two do not overlap.

### `doc sizes are within budget` and `req id uniqueness`

Both were removed on 2026-10-19 (ADR `2026-10-19-three-gates-removed.md`), but their
names survived in three places that still read like a live inventory:

- `NOT_RUN_IN_STATIC` in `scripts/src/sgate/commands/manifest_verify.py`, which lists
  the registry gates that `sgate run-all` never dispatches.
- The "Deliberately NOT here" block in `scripts/ci/static-gates.sh`.
- `KNOWN_NON_GATES` in `manifest_verify.py`, which still carried `doc_sizes` for an
  `sgate` command module that no longer exists.

Two stale names in a list that a reader takes as "the gates that are not running" is
the same defect the 2026-10-19 removal was about, one layer down: the removal left
the *inventory* of the removal behind.

### The `gate honesty` gate and the worktree tooling

`scripts/check-gate-honesty.py` planted a real detekt violation in a disposable
`git worktree`, ran `:shared:detekt` there, and asserted that the report named the
planted violation. It existed because three defects in one session shared a shape: a
measurement that could not be distinguished from its own failure (#136, #137, #138).

It was never part of `check.sh` or CI. It was a `just` recipe (`just honesty`), and it
was registered in the shared registry as a gate that is deliberately not run there,
because each probe is a full `:shared:detekt` run (~1.5 min) and blows the 300 s budget
the gate runner gives every gate. Its companion `scripts/ci/prune-gate-honesty.sh`
reaped the probe worktrees a killed run left behind — it existed only because the
probing existed.

The `wt` module in the justfile was the other half of the worktree surface:
`just wt new <name>` created a worktree on a `feature/*` branch, `just wt code <name>`
created one and handed the terminal to `mcode`, `just wt code-in <name>` attached to an
existing one.

### `doc links` stays

The request named four gates, and the fourth — the `lychee` link checker behind
`scripts/check-doc-links.sh` — was explicitly kept. It is the only gate that checks
Markdown *links*; `check-doc-dead-refs.py` matches a path inside backticks and a dated
ADR slug, so the two never overlapped.

## Idea

Remove what was asked for, keep what was kept, and leave no name behind that reads
like a live gate.

## Decision

Removed:

- **`log interpolation allowlist`** — the registry `GateSpec`, the `GATE_CONTROLS`
  entry, the `ScriptGate` entry in `scripts/check-gate-wiring.py`, the `gate` line in
  `scripts/ci/static-gates.sh`, the script
  `scripts/check-log-interpolation-allowlist.py`, and the unit test that pinned the
  control's shape in `scripts/tests/test_sgate_controls.py`.
- **`doc sizes are within budget`, `req id uniqueness` and `backlog issue refs are
  resolved`** — their stale inventory entries: `NOT_RUN_IN_STATIC` and
  `KNOWN_NON_GATES` in `manifest_verify.py`, and the "Deliberately NOT here" block
  in `scripts/ci/static-gates.sh`. The first two gates were removed on 2026-10-19; the
  backlog gate on 2026-10-15. All three names had outlived their gates.
- **`gate honesty`** — the registry `GateSpec`, the `GATE_EXEMPTIONS` entry, the
  `NOT_RUN_IN_STATIC` entry, the script `scripts/check-gate-honesty.py`, the prune
  helper `scripts/ci/prune-gate-honesty.sh`, the unit test
  `scripts/tests/test_check_gate_honesty.py`, the `gate-honesty` recipe in
  `.just/tests/mod.just` and the `honesty` alias in `justfile`.
- **The `wt` module** — the `mod wt '.just/worktree'` declaration, the `wt-new` and
  `wt-code` aliases, and `.just/worktree/mod.just`.

Kept: `doc links` (the lychee gate), its registry entry, its control, its
`ScriptGate` entry, its script, `.lycheeignore`, and the pinned `lychee-bin` install
in `.github/actions/setup-python-tools`.

## Rationale

A registry is a description of what CI runs. A name in it that describes nothing is a
false statement about the repository, and this repository has spent several ADRs
establishing that a gate which cannot fail is worse than no gate, because it is
reported as a passing one. Removing a gate's *registration* while leaving its name in
the inventory keeps the false statement in the one list a reader consults to learn
what is not being checked.

The `log-interpolation` gate was a real gate with a real control. It was removed
because it was asked for, not because it was inert — and the honest thing to record is
that distinction, because "this gate could not fail" is a claim about the gate and
"this gate was removed" is a claim about the decision. They are not the same sentence.

The honesty probing was removed with its worktrees. It was a gate about the lint gate,
run by hand, at ~1.5 min per probe, with a prune script that existed only to clean up
after it. Leaving the probe script behind without the prune script would leave a
directory of stale `git worktree` entries in `.git/worktrees/` with no reaper; leaving
the `wt` module behind would leave two ways to create a worktree, one of which has
nothing to do with testing anything.

## Consequences

- **A log call that interpolates a non-allowlisted type is no longer reported by a
  gate.** The allowlist constant is gone with the script. The `scanners/source_text.py`
  character walk that was written to replace the old line-by-line detector stays — it is
  a shared scanner, and other gates use it.
- **Nothing reports a stale gate name in the inventory anymore.** `manifest-verify`
  compares the registry against `static-gates.sh` in both directions, and the "not run
  in static" list now names only gates that exist — `doc dead refs`, `flaky tests are
  acknowledged`, `rebase compiles` and `test runs`.
- **`just honesty` and `just wt …` no longer exist.** `setup-hooks` still installs
  `core.hooksPath` and still configures attached worktrees — that is the hook
  installer, not the worktree tooling, and the advisory gate `git hooks path is
  per-worktree` still checks it.
- **The `detekt` probes that proved the lint gate could fail are gone.** The
  `TestRegexRepl` and `TestUnchangedValidation` classes stay: the `Edit` semantics
  they pin (an empty replacement is a deletion, an unset one is refused) were the fix
  for the 2026-10-19 import crash, and they are independent of the removed gate.
- The `log-interpolation` control in `scripts/tests/test_sgate_controls.py` is gone; the
  test that asserted *some* registry control still uses a regex edit stays, and other
  controls still do.

## Links

- `scripts/src/sgate/commands/_registry.py` — the registry, its controls and exemptions
- `scripts/src/sgate/commands/manifest_verify.py` — `NOT_RUN_IN_STATIC` and
  `KNOWN_NON_GATES`
- `scripts/ci/static-gates.sh` — the gate registry `sgate run-all` reads
- `scripts/check-gate-wiring.py` — Parts B, F, H and J
- `docs/decisions/2026-10-19-three-gates-removed.md` — the earlier removal of the
  doc-sizes and req-id gates
- `docs/decisions/2026-10-19-gate-controls-must-be-executable.md` — why the
  log-interpolation control was rebuilt before it was removed
- `docs/decisions/2026-10-19-doc-links-gate-lychee.md` — why `doc links` stays
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` — the rule
  these removals apply
