---
title: "Documentation links are checked by lychee, as a gate that complements the dead-reference scan"
date: 2026-10-19
status: accepted
tags: ["tooling", "docs", "ci", "gates"]
---

## Context

The repository had a documentation-reference gate,
`scripts/check-doc-dead-refs.py`, and a plan to add `lychee` for link checking.
The plan's premise was that the two would compete, and that one would replace the
other. Measuring them showed the opposite: they check **different things**, and
the gap between them was fifteen broken Markdown links that nothing in the
repository could see.

`check-doc-dead-refs.py` matches two forms — a path inside backticks, and a dated
ADR slug — and resolves them from the **repository root**. When a path does not
resolve from the root, it falls back to matching the **basename** anywhere in the
tree, and reports the result as *drift*: a warning, never a failure. That leniency
is the reason the fifteen broken links survived: they were reported as drift, and
drift does not fail a build.

`lychee` resolves a Markdown link relative to **the file that contains it**, which
is what a Markdown reader's browser does, and it fails on what it cannot resolve.

## Idea

Add `lychee` as a blocking gate in the single gate registry
(`scripts/ci/static-gates.sh`), so it runs in CI's `static` job and locally
through `check.sh` — the same surface every other gate lives on — and keep
`check-doc-dead-refs.py` for the two forms it alone covers.

## Decision

**Adopt lychee 0.24.2 as a blocking gate, and keep the dead-reference scan
alongside it.**

- `scripts/check-doc-links.sh` — the gate script. It checks that `lychee` is on
  `PATH` and fails loudly if it is not (an unreachable gate is worse than an
  absent one: the same rule the `actionlint` and `shellcheck` gates follow), and
  then runs
  `git ls-files -z -- '*.md' | xargs -0 -r lychee --offline --no-progress --root-dir "$PWD"`.
- `scripts/ci/static-gates.sh` — `gate blocking "documentation links resolve"
  sgate doc_links`, with the reasoning in the comment above the line.
- `scripts/src/sgate/commands/_registry.py` — a `GateSpec` for the gate and a
  positive control (`GATE_CONTROLS['doc-links']`) that appends a Markdown link to
  a file that does not exist.
- `scripts/check-gate-wiring.py` — the matching `SCRIPT_GATES` entry, so Part B
  executes the control and proves the gate can fail.
- `.github/actions/setup-python-tools/action.yml` — a new `with-link-lint` input
  (default `true`) that installs `lychee-bin==0.24.2` through `uv` and verifies
  the version, the same way `shellcheck-py` is installed. `ci.yml`'s `tests` job
  sets it to `false`, because that job does not run this gate.
- `.lycheeignore` — one entry: an illustrative link in the feature-scaffold skill
  that points at a file the skill describes rather than one that exists. Five
  other unresolved links are inside code fences, which lychee correctly skips.

**`--offline` is not optional.** A link check that reaches the network makes its
verdict depend on somebody else's uptime, and this repository builds reproducible
gates. External URLs are out of scope by construction; the gate covers links
*inside* the repository, which is the part a reader can actually be misled by.

**The fifteen broken links were fixed, not baselined.** They were real bugs: a
missing `../`, a renamed ADR, a file that had moved from `legal/` to
`docs/legal/`, a skill link whose target was `./SKILL-shapes.md` rather than a
subdirectory path, and one Markdown reference definition.

## Rationale

- **The two gates are complementary, and the measurement is what showed it.** The
  dead-reference scan covers backticked paths and ADR slugs — forms lychee does
  not read — and it is backed by a 354-entry baseline that a link checker would
  have to re-learn. The link checker covers Markdown links — a form the scan
  never looked at. Neither replaces the other; the fifteen fixed links are the
  proof, and they were invisible to the scan because of its basename fallback.
- **A gate must live where every gate lives.** The `lycheeverse/lychee-action@v2`
  GitHub Action is the obvious route and was rejected: it would make link
  checking a thing that exists only in a workflow, invisible to `check.sh` and
  to the registry that is the single list of gates. The repository's rule is that
  a gate cannot live on one surface and be absent from the other.
- **A missing binary must be loud.** The gate is registered as blocking and
  fails if `lychee` is not installed, rather than skipping. A gate that quietly
  checks nothing is the failure mode
  `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` is about.
- **Baselining fifteen broken links would have bought a green gate and a broken
  document.** They were fixed because a reader following them finds nothing, and
  a baseline entry is a claim that a reader is expected to accept.

## Consequences

- **The repository's Markdown links are checked in CI and locally.** Current
  state: 322 links total, 224 unique, 186 OK, 0 errors, 136 excluded, exit 0.
- **The gate proves it can fail.** The control appends a link to a non-existent
  file; the gate exits non-zero, and the file is restored afterwards.
- **`lychee-bin` is a new build-time dependency of the `static` job**, pinned to
  `0.24.2` and version-verified at install time, with the official PyPI wrapper
  (the same distribution channel as `shellcheck-py`).
- **A local run needs `lychee` on `PATH`.** `uv pip install "lychee-bin==0.24.2"`
  is the command; the gate's error message says so.
- **External links are not checked.** That is a deliberate scope decision, not an
  oversight, and it is what keeps the gate's verdict reproducible.
- **The dead-reference scan keeps its baseline and its ceiling.** The two gates
  now report on different things, so a failing one points at a different defect
  than it used to — worth knowing when reading a red CI.

## Links

- `scripts/check-doc-links.sh` — the gate
- `scripts/ci/static-gates.sh` — the registry entry
- `scripts/src/sgate/commands/_registry.py` — the `GateSpec` and the control
- `scripts/check-gate-wiring.py` — the `SCRIPT_GATES` entry that runs the control
- `.github/actions/setup-python-tools/action.yml` — the pinned install
- `.lycheeignore` — the one illustrative link that is not a link
- `scripts/check-doc-dead-refs.py` — the scan this gate complements
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` — the
  rule the loud binary check follows
