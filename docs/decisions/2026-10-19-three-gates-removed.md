---
title: "Three gates removed: doc sizes, req id uniqueness, backlog issue refs"
date: 2026-10-19
status: accepted
tags: ["tooling", "ci", "gates"]
---

## Context

Three registered gates were removed on 2026-10-19. Each had a different reason, and
none of the three reasons is "it was inconvenient".

### `backlog issue refs are resolved`

The gate was retired in #657 — the registration went, the script stayed on disk, and
Part H carried a reachability exemption for it. It was invoked by nothing: not by
`scripts/ci/static-gates.sh`, not by a workflow, not by `check.sh`, not by any recipe.

The script itself could not be proved either. It reads the live GitHub issue list and
reports every OPEN issue without a `Backlog:` field, and that snapshot is *already* in
the failure state the gate detects: 94 open issues, none carrying the field. A control
has to tell a clean run from a sabotaged run, and here both exit 1. The registry said
so itself — the exemption read "the snapshot is already in the failure state the gate
detects … The real fix is resolving the backlog-issue-refs violations in the snapshot".

So the gate could not pass, could not fail for a reason anyone could act on, and never
ran. Fixing it was not a code change; it was a GitHub-side cleanup of 94 issues, and
the script was not the place to start that.

### `req id uniqueness`

An advisory registry entry, invoked by nothing. Its corpus is red today and has been:
`REQ-LE-003` and `REQ-LE-004` are each defined twice, in
`openspec/changes/add-log-export/` and `openspec/changes/log-export-surface/`, and
`REQ-UA-015` re-declares a requirement the live spec already defines
(`openspec/specs/user-authentication/spec.md:253`).

The gate was never wired, and the reason it could not be wired is in the tree: the
duplicates live in two *active* change directories, and the fix is to settle which of
the two changes owns the requirement. A gate that reports "these two requirements
collide" does not settle it; it reports it on every run of every change until someone
decides, which is why it was advisory, and why it never ran.

### `doc sizes are within budget`

This one is the interesting one, because it *was* running — and not as the gate its own
registry entry claimed.

Its registry entry said "local doc-size budgets … are a house-keeping invariant; not run
in CI, must not block a PR". That was wrong twice over. The script was the second step
of the `decisions digest within budget` sgate command, which *is* a blocking gate in
CI: `scripts/src/sgate/commands/decisions_digest_within_budget.py` ran
`refresh-decisions-digest.py` and then `check-doc-sizes.py`, and either failing failed
the gate. So the budgets were enforced in CI by a gate whose name says nothing about
doc sizes, while the gate that was named for them was exempt and ran nowhere.

The script also held a second copy of the digest budgets. `DIGEST_MAX = 120` mirrored
`MAX_ORIENTATION_LINES` in the generator and `DIGEST_FULL_MAX = 3000` mirrored
`MAX_DIGEST_FULL_LINES`, with a unit test asserting the two agreed — the #520 shape
(one rule, two owners) that had already drifted once in this repository.

## Idea

Remove the three gates, their scripts, their registry entries, their controls, their
exemptions and their unit tests, and fix every reference that pointed at them. Where
something the removed gate owned has no new owner, say so in the consequences rather
than leave the gap to be discovered later.

## Decision

Removed:

- `scripts/check-backlog-issue-refs.py`, its registry entry, its exemption, its
  `just` recipe reference, and `scripts/tests/test_check_backlog_issue_refs.py`.
- `scripts/check-req-id-uniqueness.py`, its registry entry, its fixture control and
  its fixture builder in `scripts/check-gate-wiring.py`, its reachability exemption,
  and `scripts/tests/test_check_req_id_uniqueness.py`.
- `scripts/check-doc-sizes.py`, its registry entry, its control, its exemption, the
  `sgate doc_sizes` command, the `doc-sizes` entry in `SCRIPT_GATES`, the
  `just docs-audit` step, and the cross-check in
  `scripts/tests/test_refresh_decisions_digest.py`.

Two consequences of the removal needed handling rather than noting:

1. **`decisions digest within budget` lost its second step.** The command now runs the
   digest generator alone. It still enforces the digest budgets — the generator prints
   the number it holds, and the command parses that number — but it no longer checks
   AGENTS.md, SKILL.md, `description`, ARCHITECTURE.md or PROGRESS.md. Those budgets
   have no owner now. The generator's own constants became the single copy, and the
   test that compared the two copies was replaced by a test that asserts no second
   copy exists.

2. **The `gate-liveness` registry control was removed rather than re-pointed.** It
   corrupted `scripts/check-backlog-issue-refs.py` — the file this change deletes — and
   it had already been inert: measured on this tree before the removal, injecting a
   raise into that file left the liveness gate green ("30 gate(s) probed, 0 died"),
   because the liveness gate probes the `gate` lines in `static-gates.sh` and that
   script was not among them. A control of that shape cannot be repaired by choosing a
   different file: the property that made it inert was a property of the harness, not
   of the target. The proving for that gate stays where the registry already said it
   was — `scripts/tests/test_check_gate_liveness.py` plus `check.sh`, which runs the
   gate for real — and the exemption text now records the measurement.

## Rationale

The repository's rule is that a gate which cannot fail is worse than no gate, because
it is reported as a passing one. Two of these three gates could not fail; the third
could fail, but did so under a name that had nothing to do with what it checked, and
was registered under a name that said it was not running at all.

Keeping them was not the conservative choice. Each was a live reference in a registry
that Part J and `manifest_verify` read as the description of what CI runs; a registry
entry that describes a gate nobody invokes, or a gate that is invoked under another
name, is a false statement about the repository. The two RED corpora they policed
remain facts about the repository, and the ADR that records them — the sweep of
2026-10-10, the requirement-identifier collisions in the two active change
directories — is where a reader now has to look. That is a real cost, and it is
smaller than the cost of a registry that says the opposite of what happens.

## Consequences

- **The document line budgets are no longer enforced by anything.** AGENTS.md
  (240), SKILL.md (500), `description` (1024), ARCHITECTURE.md (600) and PROGRESS.md
  (300) had exactly one enforcer, and it is gone. The digest budgets survive: the
  generator owns them and prints them.
- **A reused requirement identifier is no longer reported by a gate.** The three
  known collisions (REQ-LE-003, REQ-LE-004, REQ-UA-015) are still in the tree.
- **The `Backlog:` field on open issues is no longer checked.** The 94 open issues
  without it are still open.
- **Part H's candidate set drops by two**, and two reachability exemptions with it;
  the `REACHABILITY_EXCLUSIONS` list keeps only `check-adr-references.py`, whose
  exemption text was itself stale (the script *is* invoked by
  `scripts/ci/static-gates.sh:300`).
- The doc-sizes constants that had two owners now have one, and a test says so.

## Links

- `scripts/src/sgate/commands/_registry.py` — the registry, its controls and exemptions
- `scripts/check-gate-wiring.py` — Parts B, F, H and J
- `scripts/src/sgate/commands/decisions_digest_within_budget.py` — the gate that lost its second step
- `scripts/check-gate-liveness.py` — the gate whose inert control was removed
- `docs/decisions/2026-10-15-backlog-status-gate-removal.md` — the earlier removal of the gate this script outlived
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` — the rule these removals apply
