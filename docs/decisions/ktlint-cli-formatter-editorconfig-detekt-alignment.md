---
title: "ktlint CLI is the formatter; .editorconfig aligned to detekt (120, class-signature)"
date: 2026-10-11
status: accepted
tags: ["tooling", "detekt", "ktlint", "gates"]
---

# ktlint CLI is the formatter; .editorconfig aligned to detekt

## Context

Issue #539: `:shared:detekt` reported 206+ findings, nearly all ktlint formatting
rules (ArgumentListWrapping, ClassSignature, NoUnusedImports, …), blocking CI.
`just detekt-fix` cannot help — `--auto-correct` is inert in this Gradle
configuration (ADR `2026-10-09-detekt-auto-correct-inert-multi-module`).

Running the standalone ktlint CLI 1.3.0 (`com.pinterest.ktlint.Main --format`)
made things *worse* before it made them better: 213 → 231 findings. Two
authority conflicts caused it, both measured, not inferred:

1. **Line length.** ktlint read `.editorconfig max_line_length = 140` and kept
   lines up to 140; detekt's ktlint `MaximumLineLength` rule runs at 120
   (its unconfigured default, later declared in `config/detekt/detekt.yml`).
   Every line in the 121–140 band was "fine" for the formatter and a finding
   for the gate — the open "two-line-length authorities" question.
2. **Class signatures.** `ktlint_official` code style forces multiline primary
   constructors at 2+ parameters; the project's `ClassSignature` rule expects
   single-line (ADR `2026-10-09-detekt-class-signature-gotcha`). Each format
   run traded ClassSignature findings for ArgumentListWrapping ones.

## Decision

- **ktlint CLI `--format` is the formatter of record** for ktlint-rule findings;
  detekt stays the gate. Invoke it with the resolved classpath
  (`com.pinterest.ktlint.Main --format <files>`), after which a detekt re-run
  decides what remains.
- **`.editorconfig` is aligned with detekt** so the two can never fight again:
  - `max_line_length = 120` — the value detekt has been enforcing all along;
    this closes the open 120-vs-140 question in favour of the effective one.
    Effective behaviour changes for nobody: detekt already rejected 121+.
  - `ktlint_class_signature_rule_force_multiline_when_parameter_count_greater_or_equal_than = 2147483647`
    — disables ktlint_official's force-multiline so class signatures stay on one
    line, matching `ClassSignature`. Without it, `--format` and the gate pull
    the same lines in opposite directions forever.
- Never raise `max_line_length` without changing `MaximumLineLength.maxLineLength`
  in the same commit; the two now move together.

## Rationale

With the aligned config the same `--format` run took `:shared` from 213 findings
to 24, and the remainder were non-formatting rules fixed by hand. The alternative
— hand-formatting hundreds of call sites — was what the previous session hit
before measuring the version skew; the planted-violation method from
ADR `2026-10-07` is what turned "the rules are unfixable" into "the config
disagrees".

## Consequences

- `just detekt-fix` remains report-only (auto-correct inert); the documented
  fix loop is "ktlint CLI --format, then detekt".
- **TestTagsCatalog parses `TestTags.kt` line-by-line**: a dynamic tag function
  is only recognised when the `"prefix_${slug(...)}"` template sits on the
  signature line. A format wrap (e.g. from an FQN parameter type) silently
  drops the function from the catalog and `MaestroFlowTagsTest` fails with an
  "unknown id". Keep tag signatures short — import the type, never inline FQNs.
  (This is how `reminderOffsetRadioRow` broke and was fixed.)
- The "wrap long constructors into secondary constructors" workaround from the
  ClassSignature ADR is no longer triggered by format runs.

## Links

- ADR `2026-10-09-detekt-auto-correct-inert-multi-module`
- ADR `2026-10-09-detekt-class-signature-gotcha`
- ADR `2026-10-07-detekt-auto-correct-single-task`
- `.editorconfig`, `config/detekt/detekt.yml`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/ui/TestTagsCatalog.kt`
- Issue #539
