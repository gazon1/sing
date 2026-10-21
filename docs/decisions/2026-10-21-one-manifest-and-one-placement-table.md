---
title: "One manifest of gates, one table of placements, and a generated shell registry"
date: 2026-10-21
status: accepted
tags: [tooling, scripts, gates]
---

# One manifest of gates, one table of placements, and a generated shell registry

## Context

The script-tooling audit (`docs/engineering/script-tooling-audit.md`, findings
F10 and F11) found that the gates of this repository were declared twice, and
that the duplication was *checked* rather than removed.

Two places declared the same gates:

| | What it carried | Count |
|---|---|---|
| `scripts/ci/static-gates.sh` | name, mode, command — one `gate` line per gate | 32 gate lines |
| `sgate.commands._registry.GATES` | name, mode, dispatch | 47 entries |

The two disagreed by construction: the shell file spells a command
(`python3 scripts/check-x.py`), the registry stores a script path and a
`cmd_type` and reconstructs the command from them. Twenty-two of the shell
lines dispatch through `sgate <command>`, and the registry does not hold that
spelling at all — it holds what the command *means*. So the shell file could
not be generated from the registry, and the two were kept in step by a check:
`scripts/check-gate-registries.py` compared names and modes and failed on
drift.

A check that proves two declarations agree is not the same as one declaration.
It is a check that has to keep passing, forever, for a fact that could have
been stated once.

Three more tables described where gates run:

- `NOT_RUN_IN_STATIC` — the six gates that are deliberately not a line in the
  shell file, each with the place that runs it instead;
- the cluster membership — the nine gates that run through a cluster wrapper
  (one gate that runs several checks and reports every failure) — **inferred**
  by a regex over the wrapper's source: `sgate/gate_reachability.py` read the
  wrapper's text for the file names it mentions;
- `COMMAND_MODULES` and `GRADLE_TASK_PARITY` — other tables, other questions.

The inference was the sharp edge. Renaming a line inside a cluster wrapper
dropped a gate from the covered set: the gate still ran, but nothing knew it
did, so a control for it measured nothing and a claim about it was a lie
nobody could catch.

## Idea

One table per question:

- the **manifest** (`_registry.GATES`): what a gate is — its name, its mode,
  how it is dispatched;
- the **placement table** (`sgate/gate_manifest.PLACEMENTS`): where it runs —
  a line in the shell registry (with the command and the prose that explains
  it), a member of a cluster (with the cluster's name), or somewhere else
  (with the reason and the pointer that resolves).

The shell registry becomes a generated artifact. The prose moves with the gate
it explains, because a comment that says why a gate exists is part of the
declaration of that gate.

The cluster membership moves into the table, and the wrappers keep their own
`MEMBERS` list — the two are compared, so neither can drift alone.

## Decision

1. **`scripts/src/sgate/gate_manifest.py` holds the placement table.** One
   entry per gate: `STATIC` (the shell command and the prose above the line),
   `CLUSTER` (the cluster that runs it), `ELSEWHERE` (why it is not a shell
   line, and the path or workflow step that runs it). `NOT_RUN_IN_STATIC`
   remains as a *view* over that table, not a second one.

2. **`scripts/ci/static-gates.sh` is generated.** The region between
   `# >>> BEGIN GENERATED: sgate render-static-gates … >>>` and its end marker
   is rendered from the table by `sgate render-static-gates`. The rest of the
   file — the `gate()` helper, the shell functions, the report — stays by hand.

3. **The freshness of the file is a gate.** `the shell registry is current`
   runs `sgate render-static-gates --check`, and fails when the file is not
   what the table renders. The command checks by default and writes only with
   `--write`: the JSON-contract probe runs every `sgate` gate with `--json`
   and no other flag, so a command that wrote by default would repair the
   file it was asked to certify.

4. **The cluster membership is declared.** `CLUSTERS` names the members of
   each cluster. `sgate.gate_reachability.covered_scripts` uses the
   declaration instead of the regex, so a member is covered because it is
   declared — not because a wrapper's source happens to mention it.
   `scripts/check-gate-registries.py` compares the declaration with the
   wrapper's own `MEMBERS` list.

5. **The prose moved to the table.** A gate's explanation is part of the
   gate. The file is rendered from the table, so the comment has to live
   where the declaration lives.

## Rationale

- **One fact, one place.** The shell file was a copy nobody generated. A copy
  needs a check; a declaration does not. The check still exists — it is now
  the freshness gate — but it compares a file with its source, not two
  sources with each other.

- **The generated region is a region, not the whole file.** The `gate()`
  helper, the `traceability` / `github_workflows_valid` /
  `yaml_is_structurally_valid` functions and the report are code, not
  registry. Generating the whole file would have required generating code.

- **The prose is not decoration.** A comment that says why a gate exists
  ("the drift is paid", "one gate for six checks over one subject") is the
  reason the gate exists. Left in the shell file it would have been a comment
  about a generated line — a description of something that is not written
  there any more.

- **Declared membership cannot fall out silently.** The regex inference
  answered "which scripts does this wrapper mention?" and called it "which
  gates does this wrapper run?". The two differ exactly when the answer
  matters.

- **The gate's own contract did not change.** `check-gate-registries.py`
  still compares the shell registry with the Python registry; it gained the
  cluster comparison and the requirement that a placement says where its gate
  runs. The synthetic-drift tests of the gate script (twelve gate scripts
  gained their own tests in the same round) keep their contract.

## Consequences

- The shell registry cannot drift from the manifest: the freshness gate fails
  when it does, and the file is a rendering of the table.
- Adding a gate is now one declaration (a `GateSpec` and a placement) plus
  `sgate render-static-gates --write`. Before, it was two declarations plus
  the hope that the check agreed.
- The prose that explained the gates moved from a shell file to a Python
  module. It is read in the same place now, and it is read by the tools that
  render it, so it cannot describe a gate line that is no longer there.
- `sgate render-static-gates` checks by default and writes only with
  `--write` — the opposite of its sibling `render-registry`, deliberately: the
  JSON-contract probe runs gates with `--json` alone, and a generator that
  wrote by default would make the probe certify a file it had just rewritten.
- A gate that is neither a shell line, nor a cluster member, nor declared as
  running elsewhere has no placement — and the manifest tests say so.

## Links

- `docs/engineering/script-tooling-audit.md` — findings F10 and F11.
- `scripts/src/sgate/gate_manifest.py` — the placement table and the renderer.
- `scripts/src/sgate/commands/render_static_gates.py` — the generator and the
  freshness gate.
- `scripts/src/sgate/gate_reachability.py` — declared cluster membership.
- `scripts/check-gate-registries.py` — the comparison with the wrappers.
- `scripts/tests/test_gate_manifest.py` — what the table must satisfy.
- ADR `2026-10-20-gate-clusters-and-the-two-registries.md` — the clusters and
  the two registries this decision replaces.
- ADR `2026-10-06-ci-single-gate-registry-and-leaf-split.md` — the registry
  split this completes.
- ADR `2026-10-21-one-implementation-per-question.md` — the same principle
  applied to the readers: one implementation per question.
