---
title: "A dependency-usage scan has an acceptance fixture: a dependency known to be used must be visible"
date: 2026-10-19
status: accepted
tags: ["tooling", "build", "ci", "gates"]
---

## Context

`scripts/check-dependency-usage.py` reports declared dependencies that no
scanned source set appears to import. It is a *finding* gate: it names
suspects, and the allowlist of accepted findings is reviewed by hand.

That design has one blind spot, and it is the blind spot that killed the
attempt to add a real dependency-analysis tool to this repository (see
`2026-10-19-dependency-analysis-plugin-rejected.md`): a scanner that sees
**nothing** reports nothing. The plugin that was trialled and rejected
produced zero findings in `:shared` — not because the dependencies were
unused, but because it could not see Compose or Koin usage at all
(`compose.material3` appears in 183 files, `compose.runtime` in 285, `koin-core`
in 38). An empty findings list looked identical to a healthy tree.

The failure mode is general: **"no findings" is ambiguous.** It means "all
good" or "the scan is blind", and nothing in the output distinguishes them.

## Idea

Add an **acceptance fixture**: a list of dependencies that are known to be used
and must therefore be *visible* to any scan. A scan that cannot see them is
blind and must say so.

## Decision

**`scripts/dependency-usage-known-used.txt` is the acceptance fixture, and the
gate fails with exit code 2 when the scan is blind.**

- The fixture holds seven coordinates with the number of matching imports
  measured in the current tree: `material3` (216), `room3` (69),
  `kotlinx-serialization-json` (54), `koin-core` (41), `ktor-client-core` (41),
  `okio` (24), `kermit` (13).
- `scripts/check-dependency-usage.py` gained `read_known_used` and
  `load_known_used`, plus a **blind-spot check**: for every coordinate in the
  fixture, the dependency must be declared, must resolve into one of the
  scanned source sets, and must **not** appear in the findings list. If any of
  those fails, the gate reports `could not measure` and exits **2** — a
  distinct exit code from a normal finding (1) and from success (0).
- Verified in both directions: a fabricated coordinate in the fixture makes
  the gate exit 2; the unmodified tree exits 0.

## Rationale

- **A gate must be able to say "I cannot see".** Without that, a future
  dependency-analysis tool is adopted on the strength of an empty output, and
  the empty output is the tool's failure mode. The fixture turns "no findings"
  into a two-valued statement.
- **The fixture is a list of "must be visible", not a list of findings.** That
  distinction matters: a coordinate appearing in *findings* means the scan is
  blind, not that the dependency is dead. Encoding it the other way round
  would make the fixture contradict itself the first time a scanner worked.
- **The coordinates were chosen because they are unmissable.** Compose
  Material 3 with 216 matching imports cannot be invisible to a working
  scanner. A quieter dependency would not be a useful acceptance criterion.
- **A distinct exit code is required.** Exit 1 means "there are findings";
  exit 2 means "the measurement is not possible". Collapsing them would make
  the blind case indistinguishable from a busy one.

## Consequences

- **`check-dependency-usage.py` can now fail because it is blind**, not only
  because it found something. That is the point.
- **The fixture has to be maintained.** Removing a dependency that is genuinely
  gone requires editing the fixture in the same change; the gate will say
  `could not measure` otherwise. This is the same bargain as every other
  floor in this repository (unwired surfaces, tag coverage).
- **The fixture does not validate the scan itself.** It validates that the scan
  is *pointed at the code*. A scanner that sees the code and still reports
  nothing is a different failure, and is caught by the findings list, not by
  this fixture.
- **The rejected Gradle plugin could not use this fixture.** It would have
  failed it immediately — which is exactly what the fixture is for.

## Links

- `scripts/dependency-usage-known-used.txt` — the acceptance fixture
- `scripts/check-dependency-usage.py` — the gate and the blind-spot check
- `docs/decisions/2026-10-19-dependency-analysis-plugin-rejected.md` — the tool this fixture would have rejected
- `docs/decisions/2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates.md` — the measured-instead-of-assumed rule
