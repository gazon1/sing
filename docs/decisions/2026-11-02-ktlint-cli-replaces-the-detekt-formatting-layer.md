---
title: "ktlint CLI owns the formatting; detekt keeps the semantics"
date: 2026-11-02
status: accepted
tags: ["tooling", "detekt", "ktlint", "gates"]
---

# ktlint CLI owns the formatting; detekt keeps the semantics

## Context

Formatting was a detekt plugin: `dev.detekt:detekt-rules-ktlint-wrapper` was
declared in nine build files, and the whole `ktlint:` section of
`config/detekt/detekt.yml` configured it. Two consequences, both measured:

- **The cheap half was expensive.** `:shared:detekt` took **4 min 36 s** on
  2026-11-02. A question about one file — "is this formatted?" — was answered
  in minutes, and the answer arrived together with a verdict about a tree the
  author did not touch.
- **The tree was not clean anyway.** The wrapper reported 248 findings on the
  tree as it stood, and the `ktlint:` section was the only reason the detekt
  run was slow: the semantic rules are a fraction of that time.

The tool that was supposed to replace it did not work. `ktrs` (a Rust rewrite
of ktlint) was tried first and rejected on measurements, not on taste:

- `when-entry-bracing` loses the conditions of a `when` entry — it rewrites
  code that means something else;
- `standard:indent` crashes with `IllegalArgumentException: Stack should be
  empty` under `--format`;
- it does not implement `no-unused-imports`.

## Idea

Take the layout rules out of detekt and give them to the reference
implementation: the **ktlint CLI**, standalone, pinned. detekt keeps what only
detekt can judge — logic, complexity, naming, performance, exceptions, style.

## Decision

- **ktlint CLI 1.8.0 is the formatter and the formatting gate.** The version
  lives in `config/toolchain-versions.env` (`KTLINT_VERSION`) and nowhere else.
- **The `ktlint:` section is gone from `config/detekt/detekt.yml`**, and the
  `detekt-formatting` plugin (`dev.detekt:detekt-rules-ktlint-wrapper`) is gone
  from all nine build files and from `gradle/libs.versions.toml`. Two rules
  that were native detekt rules but lived in that section (`EmptyFunctionBlock`,
  `ComplexCondition`) moved to their own sections (`empty-blocks`, `complexity`)
  — they were never ktlint rules.
- **`.editorconfig` is the only place the layout rules are configured**
  (`ktlint_code_style = ktlint_official`, `max_line_length = 120`). The
  `ktlint_class_signature_rule_force_multiline_when_parameter_count_greater_or_equal_than`
  exemption of ADR `2026-10-09-detekt-class-signature-gotcha` stays.
- **The rules run in the places the old ones ran**: `just ktlint` / `just
  ktlint-fix`, the `detekt` CI job, the pre-push hook, and `lint-changed`.
  The step is `scripts/ktlint-lint.py`, and it is the same script in all four.
- **The tree is at zero findings.** There is no ktlint baseline: ktlint's
  baseline is line-based and rots with every line shift, so the debt is paid
  once instead of being carried forever.

## Rationale

ktlint CLI 1.8.0 measures **14.6 s** over the whole tree (2317 Kotlin files),
against 4 min 36 s for detekt with the formatting layer. It is the reference
implementation, so its verdict is the one the Kotlin community argues about,
and it has no bugs of its own in the two places `ktrs` had them.

The formatting of the tree is the price of the switch: `ktlint_official` wraps
call chains and function signatures that the old style kept on one line, and
the diff is large — 2022 files, +97 879 / −78 078 lines. The alternatives were
worse: keeping the detekt wrapper keeps the minutes, and a baseline keeps a
gate that fails for a reason nobody remembers.

`FunctionSignature` was disabled in the detekt config and is now active
(ktlint_official forces a multiline signature at two or more parameters). It
was switched on deliberately: with the formatting rules gone from detekt,
there is one owner for the layout, and it is the tool that implements the
rule.

## Consequences

- **`just lint` is now two tools** (`just ktlint` + `just detekt`), and the two
  verdicts are separate. A change that fails the formatter no longer waits for
  the analyser.
- **`just detekt-fix` no longer formats.** The layout is corrected by
  `just ktlint-fix` (or `python3 scripts/ktlint-lint.py --format`). The
  `autofix` workflow runs both.
- **The CI `detekt` job judges the formatting too**, as a separate step after
  the detekt auto-correct: a tree that detekt rewrote is a tree nobody has
  formatted yet.
- **The pre-push hook runs ktlint** against the tree being pushed, and skips
  itself when ktlint is not installed — a gate that cannot run must not stop a
  push, and CI runs the same check either way.
- **The baselines shrank**: the wrapper-only rules are gone from
  `config/detekt/baseline-*.xml` (`baseline-shared.xml` −34 entries,
  `baseline-desktopApp.xml` — all 21).
- **One checker had to learn the new shape.** `check-test-task-inputs.py`
  matched `inputs.file(…)` on one line; the formatter wraps a call chain into
  several lines, so the declarations stopped being seen and the gate reported
  missing inputs on a tree that had them. The pattern now tolerates the
  whitespace.

## Links

- ADR `docs/decisions/ktlint-cli-formatter-editorconfig-detekt-alignment.md`
  (2026-10-11) — the earlier alignment of `.editorconfig` with detekt; this
  ADR completes it.
- ADR `2026-10-09-detekt-class-signature-gotcha` — the class-signature
  exemption that is kept.
- ADR `2026-09-15-detekt-ktlint-kover-setup` — the original bundling of ktlint
  into detekt, which this ADR supersedes.
- `.editorconfig`, `config/detekt/detekt.yml`, `config/toolchain-versions.env`
- `scripts/ktlint-lint.py`, `scripts/ci/install-ktlint.sh`
