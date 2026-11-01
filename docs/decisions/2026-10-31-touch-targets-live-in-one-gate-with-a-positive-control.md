---
title: "Touch targets are enforced by one script gate, and that gate carries a positive control"
date: 2026-10-31
status: accepted
tags: ["build", "quality", "tests"]
---

# Touch targets are enforced by one script gate, and that gate carries a positive control

## Context

Issue #1706 added a gate that checks that an interactive Compose element is at
least 48.dp (`scripts/check-touch-targets.py`), and — in the same change — a
detekt rule (`NoSmallFixedSizeWithoutTouchTargetRule`) that was meant to check
the same thing.

Both halves were unusable, in different ways.

The **detekt rule never compiled.** Detekt 2 moved its API from
`io.gitlab.arturbosch.detekt.api` to `dev.detekt.api`; the rule and its
provider still imported the old one, so `:detekt-rules:compileKotlin` failed
on every build. On top of that the provider was registered in
`META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider` instead of
the `dev.detekt.api` one, so detekt would not have loaded it either; the rule
had no KDoc (the rule inventory generator fails loudly on that), no positive
test (every custom rule owes one) and no block in `config/detekt/detekt.yml`.

The **script gate could not fail.** Its regex could not cross a newline, so it
matched only a `clickable(` that shared a line with the size; the lambda form
`clickable { ... }` was invisible to it; and it skipped every file that
mentioned `touchTarget` or `minimumInteractiveComponentSize` at all, so one
correct control next to a violation hid the violation. A gate that cannot fail
is worse than no gate: it converts "not checked" into "checked and green".

## Idea

Keep one enforcement point and make it provable.

## Decision

**1. The script gate is the enforcement point; the detekt rule is removed.**

The rule and its provider are deleted rather than ported. Two implementations
of one rule means two answers to "is this chain big enough", and the detekt one
would have needed the same chain analysis the script gate now has, plus a
positive test and a config block, before it was allowed to exist. The
accessibility rule itself is unchanged and stays in `CODING_STANDARDS.md`: put
`minimumInteractiveComponentSize(...)` (or `touchTarget(...)`) *before* the
size, so the expansion applies to the element that is clicked.

**2. The gate reads modifier chains, not lines.**

The gate cuts the source into chains — the runs of modifier calls joined by
dots — and decides **per chain**: a chain that expands the target before the
size is fine, and the other chains in the same file are not. The cut is not a
line cut: a chain may span several lines, and two chains may share a line.
Comments and string literals are blanked out before the cut, so a violation
mentioned in a KDoc is neither a violation nor an exemption for the file it
sits in.

The gate groups a chain that spans several lines, recognises the interactive
modifiers (`clickable`, `toggleable`, `combinedClickable`, `selectable`) in both
the parenthesised and the lambda form, and recognises the expansion
(`touchTarget`, `minimumInteractiveComponentSize`) wherever it sits in the
chain. A literal size below 48.dp is reported only when the expansion does not
precede it — an expansion after the size is the shape the standard forbids.

**3. The gate has a positive control.**

`scripts/tests/test_check_touch_targets.py` plants each violating shape in a
temporary copy of the tree and asserts that the gate exits 1 and names the
planted file: a multi-line chain with a lambda-style `clickable`, a multi-line
chain with a parenthesised `clickable`, a one-line chain, two chains on one
line, and a correct chain beside a violating one. The registry control in
`scripts/src/sgate/gate_controls.py` does the same against the real tree, so
`check-gate-wiring.py --can-fail` proves the gate can still fail.

## Rationale

The gate was registered and green while detecting nothing, and the check that
would have said so — a positive control — did not exist. A control is the only
thing that distinguishes a gate from a script that prints nothing; without it,
"registered" reads as "working".

The control has to plant the shapes the code actually uses. The lambda-style
`clickable { ... }` is the dominant form in this codebase, and a control that
only planted the parenthesised form would have passed against the old gate —
which is exactly the blindness the issue reports.

One enforcement point beats two: the detekt rule could not be run at all
(it did not compile), so keeping it would have meant keeping a second,
unreachable copy of the rule and the illusion of IDE feedback.

## Consequences

- `:detekt-rules:compileKotlin` compiles again; the detekt rule inventory
  (`.agents/skills/singularity-todo-detekt-rules-authoring/RULES.md`) no longer
  fails on a rule with no KDoc.
- The touch-target rule is enforced in CI by `scripts/check-touch-targets.py`
  alone, and that gate has a control that fails when the gate is blinded.
- A change that touches the gate's own logic must update
  `scripts/tests/test_check_touch_targets.py` — the control is the contract.
- The rule is not enforced inside the IDE yet. If detekt feedback is wanted
  later, it comes as a new rule with its own positive test, per the
  detekt-rules-authoring skill.

## Links

- Issue #1780 — the file-level exemption that hid the violations in the files
  that had been migrated
- Issue #1787
- `scripts/check-touch-targets.py` — the gate
- `scripts/tests/test_check_touch_targets.py` — the positive control
- `scripts/src/sgate/gate_controls.py` — the registry control
- `CODING_STANDARDS.md` — the 48.dp rule itself
- `docs/architecture/registering-a-gate.md` — how a gate and its control are registered
