---
title: "A gate that reads less than it claims is a gate that lies: five defects in the tooling, found by an inventory"
date: 2026-10-20
status: accepted
tags: ["tooling", "ci", "gates"]
---

## Context

Three rounds of gate cleanup (ADR `2026-10-20-removed-gates-and-worktree-tooling.md`,
`2026-10-20-gate-clusters-and-the-two-registries.md`,
`2026-10-20-gate-truth-round-two.md`) made the gate corpus coherent: one copy of each
control, one truth about where a gate runs, no gate registered in one place and
absent from the other.

What those rounds did not do was read the gates themselves. They removed duplication
between the gates; nobody checked whether each gate, once executed, examined what it
said it examined.

An inventory of the tooling (`docs/engineering/script-tooling-audit.md`, 2026-10-20)
read every script, every package module and every registry entry against its own
claims. Five defects came out of it. All five share one shape: **the gate reported a
verdict about a corpus it had only partly read.**

- `yaml_has_no_duplicate_keys` ran `scripts/check-yaml-duplicate-keys.py`, deleted in
  commit `9b3fa75a` on 2026-10-15 — the round that removed 26 gates and replaced this
  one with `.yamllint.yml`'s `key-duplicates` rule. The CLI wrapper outlived the script
  it invoked and would have exited `IO_ERROR` (2) on every run. It was not a line in
  `static-gates.sh`, so CI was green because the gate never ran. The `.yamllint.yml`
  comment still claimed a second, custom-PyYAML detector existed.
- `check-workflow-env-context` returned `[]` when `.github/workflows/` was absent, and
  printed "every `${{ env.X }}` resolves to a name the file defines". Its sibling
  `check-workflow-exit-status` refuses the same situation at `scripts/check-workflow-exit-status.py:233`.
- `check-script-conventions` advertised eight rules and enforced two. R5.4's condition
  was `if "ADR" not in content and "#" not in content.split("\n")[0]` — the first line
  of a script is its shebang, which always contains `#`, so the rule could never fire.
  `check_json_flag` had a body that continued or fell through every branch, so its
  error list was always empty, while its docstring claimed to enforce R2.2 and R3.7.
- The generated gate catalogue `docs/scripts/gate-registry.md` was built from the
  command-modules directory rather than from `_registry.GATES`. It listed private
  helpers (`_gate-runner`, `_parsers`, `_registry`, `_runner`) as gates, missed every
  gate whose command is generated from a `GateSpec`, and reported `mode=unknown` for
  most rows. `render-registry --check` existed and passed — it compared the file
  against the same imperfect generator — and **no CI step or `check.sh` step called
  it**, so the document could drift silently. It had.
- `check-ui-wait-budgets` scanned Maestro flows with three regexes over raw lines and
  skipped any wait whose target was not written as `id: …`. Measured against the
  committed corpus: **26 of 435 waits** were skipped by a `continue` that said nothing,
  and the gate still printed "ui wait budgets are consistent".

## Idea

A gate's verdict is a claim about a corpus. When the claim is narrower than the
corpus, the gate is not weak — it is wrong in the direction that matters: it reports
"checked and clean" about something it never looked at.

Three responses are available for each defect, and they are not interchangeable:

- **Remove the claim.** A gate with no script, no rule and no consumer is dead code.
- **Report the gap.** A limit that is counted and bounded is honest; a limit that is
  invisible is not.
- **Refuse.** When the corpus cannot be found at all, that is a failure, not a pass.

Which one applies depends on what the gate is for, and that is a repository decision,
not a mechanical one.

## Decision

1. **The dead gate is gone.** `sgate/commands/yaml_has_no_duplicate_keys.py` and its
   registry entry are deleted. `.yamllint.yml`'s `key-duplicates: error` (ruamel, over
   every tracked YAML) is the single detector, and the comment says so — including the
   fact that `yaml.safe_load`, which the PyYAML-based gates use, silently overwrites a
   duplicate key and is therefore blind to this property.

2. **A missing corpus is an error.** `check-workflow-env-context` refuses when the
   workflows directory yields no files, copying the guard its sibling already has.

3. **A rule that cannot fire is removed, and the docstring is corrected.**
   `check-script-conventions` now enforces three rules (R5.1, R5.2, R2.1) and says
   which those are. R5.4 was **removed rather than repaired**: making it fire exposes
   26 scripts that cite no ADR, which is adding lint coverage, and expanding lint
   rules indiscriminately is its own decision with its own cost. `check_json_flag`
   stays as a named function with an empty body, so the gap is a visible line rather
   than a rule that quietly vanished between two versions.

4. **The catalogue is generated from the registry, and something checks it.**
   `render-registry` reads `_registry.GATES`; the table has one row per registered
   gate with the mode the registry declares. A new blocking gate, `gate registry is
   current`, runs `sgate render-registry --check` in the static job. Its sabotage
   control edits the table's mode column and requires the gate to fail — a comparison
   that could not tell a stale table from a current one would prove nothing.

5. **The wait scanner is a YAML parse, and the waits it cannot price are counted.**
   `maestro_waits` uses `yaml.safe_load_all`, which is what the repository already
   does for Maestro files (`scripts/ci/validate-maestro-selectors.py:30`). A wait that
   names a *text* rather than an element id has no kind — the catalogue is keyed by
   element id — so it still cannot be held to a budget. But it is now counted, and
   counted against a floor: `config/docs/ui-wait-text-waits-floor.txt`, currently 26.
   A new text wait is a finding; lowering the count is the debt being paid down. The
   floor is one-directional, for the same reason `unwired-surfaces`' floor is.

## Rationale

**Why a floor rather than a rule for the text waits.** Pricing a text wait would mean
deciding, per wait, which kind of element it waits for — a product decision about 26
flows, each of which may legitimately be a label, a list row or a field. The gate does
not need that decision to be honest: it needs to know how many waits it is not
holding to a budget, and to fail when that number grows. The floor is the same
mechanism `unwired-surfaces` already uses, and it keeps the debt visible and
one-directional.

**Why the catalogue's freshness is a gate rather than a pre-commit hook.** The
document is read by agents and by people who did not write the registry. Its
correctness is a property of the repository, and the repository's mechanism for
enforcing a property is a gate. A pre-commit hook would check the change that
happened to touch the file and miss the change that made it stale.

**Why the catalogue's control edits the file.** The gate reads one artefact: the
committed table. A control that changed something else would prove that some other
gate can fail. Editing the mode column of a row is the smallest change that makes the
comparison mean something — and it is the field the table exists to carry.

**Why R5.4 was removed rather than fixed.** A rule that has never fired has no
coverage to preserve. Turning it on is a change to the lint policy, and it would
require 26 scripts to grow an ADR reference in the same change. Bundling a policy
change with a defect fix is how a fix stops being reviewable.

## Consequences

- `docs/scripts/gate-registry.md` changes shape: one row per registered gate (45
  gates plus the header), instead of 23 module rows with `unknown` modes. Anything
  that parsed the old table needs updating; nothing in the repository does.
- The static job has one more gate (31 lines) and the Python registry one more
  `GateSpec` (46 entries). `check-gate-registries` still reports both registries in
  agreement.
- `config/docs/ui-wait-text-waits-floor.txt` is a new baseline. A flow that adds a
  text wait without an element id makes `ui wait budgets are consistent` red, with a
  message that says what to do about it.
- `check-ui-wait-budgets` gains `--update-floor`, mirroring
  `find-unwired-surfaces.py --update-floor`.
- The audit (`docs/engineering/script-tooling-audit.md`) records the remaining open
  findings: three Kotlin comment-strippers, two Maestro header parsers that disagree,
  the shared Gradle parser used by two of three callers, the dead aliases in the
  emulator packages, five `sgate` adapters that scrape human-readable output, two
  registries of "where does this live", one script with no caller at all, and twelve
  gate scripts with no unit test.

## Links

- `docs/engineering/script-tooling-audit.md` — the inventory, the findings and the
  baseline this decision came from
- `docs/decisions/2026-10-20-gate-truth-round-two.md` — the round that made the gate
  corpus coherent without reading the gates
- `docs/decisions/2026-10-20-gate-clusters-and-the-two-registries.md` — the two
  registries this decision adds a third consistency check to
- `docs/decisions/2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates.md` — the
  measured rejection of a Kotlin parser in Python gates, which this decision upholds
- `scripts/check-ui-wait-budgets.py` — the gate whose scanner was replaced
- `scripts/src/sgate/commands/render_registry.py` — the generator that now reads the
  registry
