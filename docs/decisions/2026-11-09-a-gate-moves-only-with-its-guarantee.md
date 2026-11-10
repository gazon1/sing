---
title: A gate moves to the language's tools only when the tools can keep its guarantee
date: 2026-11-09
status: accepted
tags: [tooling, gates, detekt, konsist]
---

# A gate moves to the language's tools only when the tools can keep its guarantee

## Context

`scripts/` holds about ninety repository gates, a gate runner, and a registry that
runs them from CI and from the local loop. The suite works, and the cost is a second
subsystem to maintain: a Python parser for Kotlin sources, a registry split across
four declarations, and gates that check what other gates are registered.

Three classes of gate are candidates for a move to the tools the project already has:

- a check of the Gradle build's own configuration;
- a check of Kotlin source semantics (Detekt and Konsist are already wired — 33 custom
  Detekt rules, 18 architecture tests);
- a check of an external tool (Maestro, ADB, GitHub) that only Python orchestrates.

The tempting move — rewrite the Python on Kotlin — changes the language of the
maintenance without reducing the number of mechanisms. The opposite temptation — move
every check into `./gw` — replaces small Python parsers with Gradle plugins and
TestKit, which is a new mechanism rather than a removed one.

## Idea

A gate may move only when the destination can keep the guarantee it makes, and when
the move is proved on the same input that proves the gate today. The comparison is a
**differential control**: the same sabotage is applied to the same tree, both
analyzers run, and the new one must name the same file and add no findings of its own.

A destination that cannot hold the guarantee keeps the gate where it is. That is a
result, not a failure.

## Decision

**Moved:**

- `coverage task list`, `coverage floor declaration`, `coverage timeout` → one cluster
  gate (`scripts/check-coverage-contract.py`). The members keep their scripts, tests
  and controls; the surface is one row instead of three.
- `the link grammar has one home` → Detekt rule `NoHandWrittenLinkScheme`. The
  source-text script, its registry entry, placement and control are deleted; the
  sabotage is a case in the rule's test. Both analyzers named the same file on the
  same sabotage before the script was removed.

**Kept on Python, with the reason:**

- `room schema integrity` — it compares the `@Database` annotation, the exported
  `NN.json` files and the `Migration*.kt` chain from the sources, without a build.
  `MigrationRoundTripSupport` executes the round trip and the script *generates* the
  test that calls it; they are complementary, not two copies.
- `changed-modules.py` — it answers "which modules does this change reach" from the
  sources in 0.05 s, inside pre-commit, where no Gradle daemon may be assumed. It
  already fails when its graph and the build disagree, so the drift check the move
  would buy exists.
- `selectable text coverage` — the registry test compares imports with a table; the
  scan guesses what "prose" means. They are different questions, and the script says
  so in its own docstring.
- `compose stability contract`, `DI fixture matches the definitions`,
  `mobile build carries no external-agent code`, `unwired surfaces` — each needs a
  cross-file or whole-tree view that a per-file Detekt rule does not have, or a type
  resolution that Detekt without the type-resolution classpath does not have.
- `test-impact-map.py` — Gradle has no source-to-test-class graph; a Kotlin rewrite
  would be the same heuristic in another language.

**Deferred:** `declared dependencies are used` (a dependency-analysis spike on KMP) and
`test-task external inputs declared` (the check is about a build script, and the
Gradle-native form needs TestKit).

**Not done, and why:** `screen state collections` stays for now. A Detekt rule sees one
file and therefore cannot report the other direction — a register entry whose property
is gone — which is half of that gate. The whole-tree form is a Konsist scope over five
source roots (the shared architecture scope covers three), and that scope is a
separate change with its own differential matrix.

## Rationale

The differential control is the whole argument. "Both are green on this tree" is not
equivalence: a per-file PSI rule, a whole-tree Konsist scope and a source-text scan see
different things — comments, triple-quoted strings, source sets, typealiases — and the
one that does not see a defect reports green about it.

The kept list is not conservatism for its own sake. Each entry names the view the
destination lacks, and a gate that moves into a tool that cannot see its subject stops
being a gate while still looking like one.

## Consequences

- One shell row fewer for the coverage checks, one gate fewer in the registry and the
  shell registry (90 → 89 entries), one Python script fewer that parses Kotlin.
- A link-grammar violation now surfaces in `:shared:detekt`, which is later than the
  static suite and is part of the existing detekt step — the guarantee moved in time
  and the message says where.
- The Detekt rule is stricter than the script it replaced: a triple-quoted literal is a
  string template too. No production literal in the tree was one.
- The next candidate (`screen state collections`) has a written entry condition: a
  Konsist scope over all five source roots, with the stale-register direction checked
  from the same register file.

## Links

- `scripts/check-coverage-contract.py` — the cluster
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoHandWrittenLinkScheme.kt`
- `scripts/check-room-schema-integrity.py`, `scripts/changed-modules.py` — kept
- `docs/architecture/registering-a-gate.md` — how a gate is registered
- ADR `2026-10-20-gate-clusters-and-the-two-registries.md`
