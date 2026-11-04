---
title: "The formatting rules detekt and ktlint both enforced now have one owner"
date: 2026-11-03
status: accepted
tags: ["tooling", "detekt", "ktlint", "gates"]
---

# The formatting rules detekt and ktlint both enforced now have one owner

## Context

ADR `2026-11-02-ktlint-cli-replaces-the-detekt-formatting-layer` moved the layout rules out
of detekt and into the ktlint CLI. Three detekt `style:` rules stayed active: `WildcardImport`,
`UnusedImport` and `TrailingWhitespace`. Each has a ktlint twin — `standard:no-wildcard-imports`,
`standard:no-unused-imports`, `standard:no-trailing-spaces` — so the tree was governed by two
verdicts for one fact.

Two tools enforcing the same rule is not redundancy. A finding is reported by whichever tool ran
first, and a fix by one is checked by the other. The tree was clean under both tools, so the
duplication was invisible until the first wildcard import in a new file produced two findings
describing one defect.

Probing the pinned ktlint 1.8.0 showed that one of the three twins is not on by default:
`standard:no-unused-imports` is an **experimental** ktlint rule. It does not run unless it is
enabled by name (`ktlint_standard_no-unused-imports = enabled`), and `ktlint_experimental = enabled`
does not turn it on either. Detekt's `UnusedImport` was therefore the only rule *configured* to
catch an unused import in this repository — and, as the next section shows, it caught none of
them.

Enabling it surfaced 18 unused imports that detekt never reported. The tree was clean under
detekt's `UnusedImport` and no baseline carries an entry for the rule, so it had nothing to say
about any of them; ktlint's rule found all 18. Nine were in the desktop UI flow tests, seven in
the desktop test helpers, one in the desktop entry point (`okio.Path`, while
`okio.Path.Companion.toPath` was the import in use) and one in `shared/build.gradle.kts`. In
seventeen of the eighteen nothing in the file refers to the imported name; in the eighteenth the
name appears only as the qualifier of another import. Detekt's rule stayed silent because it
never ran: it implements `RequiresAnalysisApi`, and the aggregate `detekt` task this repository
invokes supplies no classpath, so detekt drops it before reading a file. That is not the two rules
disagreeing about a use — one of them was not a rule in force. ADR
`2026-11-04-detekt-runs-without-type-resolution` has the mechanism and the check.

Three more surfaced later, in code that arrived on `main` after this was written — two in
`shared-ui` (`PriorityChip`, `PriorityIndicator`, both importing `PriorityPalette`) and one in
`AgendaViewModelTest` (`kotlinx.coroutines.flow.onStart`). They were removed with the first
eighteen, so the rule that now owns unused imports catches all twenty-one.

## Idea

- Leave the detekt rules on, and accept two verdicts for one defect.
- Turn the detekt rules off and let ktlint catch all three — which means enabling the
  experimental rule first, or an unused import is caught by nothing at all.
- Turn the detekt rules off and let ktlint catch two of the three, leaving the unused-import gap
  to a later change.

## Decision

The three detekt `style:` rules are `active: false` in `config/detekt/detekt.yml` and in the
generated `config/detekt/detekt-rules-module.yml`, each with a comment naming the ktlint rule that
now owns it — the same shape as `MaxLineLength`, which was switched off for the same reason when
the ktlint layer was first added (2026-09-15). `.editorconfig` enables
`ktlint_standard_no-unused-imports`, and the imports it found were removed — 21 in all.

## Rationale

One rule, one owner. The ktlint CLI is the reference implementation of the layout rules, it runs
in seconds, and it is the tool whose verdict the Kotlin community argues about — a second,
weaker copy of the same rule inside detekt adds a verdict and no safety.

The unused-import rule is the one that needed work before detekt could let go of it: without
`ktlint_standard_no-unused-imports = enabled`, switching detekt's rule off would have left an
unused import caught by nothing at all. The imports it found are genuinely unused — in
twenty of the twenty-one nothing in the file refers to the name — and detekt's rule had not
reported any of them, so enabling the rule was a gain in coverage, not a loss of it.

## Consequences

- Wildcard imports, unused imports and trailing whitespace are caught by ktlint, in seconds,
  without a Gradle run. Detekt keeps the semantics.
- Nothing stops the duplication from returning: no check fails when a detekt rule and its
  ktlint twin are both active again (issue #2043).
- ktlint's `no-unused-imports` and detekt's `UnusedImport` did not disagree: detekt's rule was
  inert, because the aggregate `detekt` task runs without type resolution. ktlint reported 21
  imports and detekt reported nothing because it ran nothing. The imports ktlint reported were
  deleted, and the tree is clean under the rule that now owns it.
- `config/detekt/detekt-rules-module.yml` is generated by `scripts/gen-detekt-rules-config.py` —
  it is regenerated, never hand-edited. The three rules sit above `ReturnCount` in `detekt.yml`
  because that generator's rule-block replacement swallows every rule that follows the one it
  replaces, so a rule below `ReturnCount` would never reach the module's own config. That
  generator defect is separate work (issue #2042).
- The `style:` block of `detekt.yml` now reads as "what detekt keeps" rather than "what detekt
  happens to also do": the three rules that ktlint owns sit together at the top of it, next to
  `MaxLineLength`.

## Links

- ADR `2026-11-02-ktlint-cli-replaces-the-detekt-formatting-layer.md` — the move that left the
  three rules behind.
- ADR `2026-10-08-nounusedimports-implicit-receiver.md` — the detekt-side counterpart: the
  import rules have a history of false positives, and the convention is an explicit receiver for
  `launch`.
- `.editorconfig`, `config/detekt/detekt.yml`, `config/detekt/detekt-rules-module.yml`
- `scripts/ktlint-lint.py`, `scripts/gen-detekt-rules-config.py`
