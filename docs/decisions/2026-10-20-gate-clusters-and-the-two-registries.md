---
title: "The gate registry has one truth, the heavy gates moved out of the static job, and the clusters are one gate each"
date: 2026-10-20
status: accepted
tags: ["tooling", "ci", "gates", "just"]
---

## Context

A cleanup pass on 2026-10-20 removed the `log interpolation allowlist` gate, the
`gate honesty` probing and the `wt` worktree module (ADR
`2026-10-20-removed-gates-and-worktree-tooling.md`). Measuring what was left
turned up four things that were not broken — they were *shape*.

**Two registries, one job.** `scripts/ci/static-gates.sh` is what `sgate run-all`
reads and what CI's `static` job runs. `scripts/src/sgate/commands/_registry.py`
is what `sgate <name>` and `load_gates()` read. They are separate files with
separate names, and they disagreed: nine names in one and not the other, and a
gate called "documentation links resolve" in the shell file against "doc links"
in the Python one. The check that was supposed to notice — `sgate
manifest-verify` — compared the Python registry with itself (`load_gates()`
reads `_registry.GATES`, not the shell file), and it was invoked by nothing.
The one checker that did compare them, `scripts/check-gate-registries.py`,
reported drift as a **warning** and exited 0.

**A gate that ran on no one.** `doc dead refs` was registered, had a positive
control in both control registries, was green, and had no gate line anywhere.
`check-gate-wiring.py` called it "wired", because its reachability scan counts a
mention of the script *in a comment* in `static-gates.sh` as an invocation. So
the shape of a Markdown path in backticks — the thing `check-doc-dead-refs.py`
exists to check — was not checked by anything.

**Two heavy gates in the cheap job.** The `static` job's wall time is the sum
of its slowest gate. `gate scripts' own unit tests` (1140 assertions, 70 s) and
`gates are alive` (a probe that re-runs the whole gate corpus, 63 s) were both
in it, and the 35 remaining gates take 13 s together. `gates are alive` also
probed the file it was in, which is why it carried a `# no-self-probe` marker.

**Nine gates over two subjects.** Six of them governed the detekt rules and
three of them the shape of the CI workflow's Gradle block: nine CI rows, nine
registry entries, nine places to look.

Measured, 2026-10-20, sequential: 121 s for the static registry, of which 70 s
was the unit tests and 13 s the `unwired surfaces` advisory. `check-gate-wiring.py`
alone was 72 s.

## Idea

One registry per fact, one gate per subject, and the expensive checks in the
job that has slack.

## Decision

**One truth about which gates exist.** `scripts/check-gate-registries.py` became
the gate that compares the two registries — names, modes, and what actually
runs — and it is registered in both of them, so it is itself checked. The
vacuous `sgate manifest-verify` was deleted; its `NOT_RUN_IN_STATIC` list moved
to `_registry.py` as a name → reason mapping, and the six gates that run
somewhere else are named there with where. The two lint gates that were inline
`bash -c` snippets became named functions in `static-gates.sh`, so the Python
registry can dispatch them too (`CmdType.BASH_C`, which now sets
`STATIC_GATES_SOURCED=1` — without it, sourcing the registry ran the whole
suite inside the gate).

**A comment is not an invocation.** `registered_gate_scripts()` in
`check-gate-wiring.py` now drops comment lines before it scans, the way the
reachability scan already did. `REACHABILITY_EXCLUSIONS` is empty — its two
entries were stale, one of them claiming a script was wired to nothing while
`static-gates.sh` ran it — and the check that guards the list now reports an
exclusion whose subject has become reachable.

**The heavy gates moved.** `gate scripts' own unit tests` and `gates are alive`
are steps of the `tests` job now. Nothing is lost: they still run on every push
and pull request, and they land in the 40-minute Gradle-bound job instead of
the one whose wall time is the sum of its slowest gate. `gates are alive` no
longer probes the file it lives in, so the `# no-self-probe` marker went with
the line.

**The clusters are one gate each.** `scripts/check-ci-workflow-shape.py` runs
the three CI-shape checks; `scripts/check-detekt-governance.py` runs the six
detekt-governance checks. Each wrapper reports *every* member that failed, not
the first, and takes `--only <member>` so a control can drive one member.
The members keep their own scripts, unit tests, registry entries and positive
controls — a control proves that a check can fail, and one control for a
cluster would prove only that something in it can fail. Each cluster has a
control of its own, because the aggregate is a gate too.

**`script conventions` is green.** The rule that gate scripts use Click was a
text search for `import argparse`, and the checker that held the rule contained
the string it looked for — so the gate reported itself as a violation on every
run and could only ever fail that way. The rule reads the AST now. The gate
has a positive control (the probe adds the import the rule looks for), and its
exemption is gone.

**The unwired surfaces are zero.** Three findings: two were the static scan's
known limit (a type consumed through a bare `get()` in another binding's
lambda), one was a binding with no production call site. All three are named in
`DECLARED_INTENT` with their reasons, and the floor went from 3 to 0 — a
ratchet that cannot rise unnoticed.

## Rationale

A registry is a description of what CI runs. Two descriptions of the same thing
drift, and a drift check that compares one description with itself cannot see
the drift. The rules this repository already wrote — "a gate that cannot fail
is worse than no gate", "a check task nobody invokes is worse than no check" —
are rules about *reachability* and *verdicts*, and the two registries were
breaking both: one gate ran on no one, and one check reported on a file that
has not existed in the shape it named for a month.

The heavy gates were in the wrong job for the simplest reason there is: a job
whose wall time is the sum of its slowest gate cannot afford a gate that is
slower than everything else it runs. Seventy seconds of unit tests next to
thirteen seconds of actual checks is a job that waits for its slowest child.

The clusters were nine rows over two subjects. Merging them is not a
performance change — it costs nothing either way, and the members still run
every time. It is a surface change: one row to read, one place to look, one
name to remember. The controls stayed per member because that is the property
the repository pays for: a cluster with one control would prove that *something*
in it can fail, and a member that had stopped detecting anything would be
invisible.

## Consequences

- **The static job got faster.** The two heaviest gates left it; what remains
  is 13 seconds of checks plus the setup.
- **The static job lost two signals.** `gates are alive` no longer runs there —
  the probe is a second pass over the corpus, and CI runs every gate anyway, so
  what it adds there is timing, not verdicts. The unit tests run in the `tests`
  job instead of the `static` one.
- **A new gate exists**: `gate registries agree`. It compares the two
  registries — names, modes, and whether a registered gate is actually executed
  by a gate line, directly or through a cluster wrapper — and it fails on any
  disagreement. Its control adds a phantom gate line to `static-gates.sh`.
- **A registered gate now needs a reason to stay out of the static job.**
  `NOT_RUN_IN_STATIC` is a name → reason mapping, and the checker reports an
  entry whose gate no longer exists.
- **The mode of a gate is checked.** `script conventions` was `blocking` in the
  Python registry and `advisory` in the shell one; the registry was wrong, and
  the checker now says so.
- **Two new scripts**: `scripts/check-ci-workflow-shape.py` and
  `scripts/check-detekt-governance.py`. Both run their members as subprocesses
  and report every failure.
- **`sgate manifest-verify` is gone.** `scripts/check-gate-registries.py` took
  its job and does it against the file the job actually reads.
- **A stale control copy was found and fixed.** The registry's `rule-intent`
  control named a `detekt.yml` pattern that has not been in the file since the
  rules were reorganised. The twin in `check-gate-wiring.py` was the copy that
  ran, so nothing had said so — and Part J's twin comparison covered only
  `SCRIPT_GATES`, not `SABOTAGE_ONLY_GATES`. It covers both now.

## Links

- `scripts/check-gate-registries.py` — the gate that compares the registries
- `scripts/src/sgate/commands/_registry.py` — the registry, `NOT_RUN_IN_STATIC`
  and the controls
- `scripts/ci/static-gates.sh` — the registry `sgate run-all` reads
- `scripts/check-gate-wiring.py` — Parts B, F, H and J
- `scripts/check-ci-workflow-shape.py`, `scripts/check-detekt-governance.py` —
  the two clusters
- `docs/decisions/2026-10-20-removed-gates-and-worktree-tooling.md` — the
  removal that preceded this
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` — the
  rule these changes apply
