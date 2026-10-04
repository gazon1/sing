# verification-baseline-ratchet

## What

Add two requirements to `test-execution-integrity` that make a committed
baseline a floor in both directions: a suppression baseline may not grow, and a
measured floor may not be lowered without a recorded reason.

No product behavior changes. This is verification posture only.

## Why

The floors in this repository are committed files that a gate compares against,
and every one of them can currently be moved the wrong way with no objection.

- `config/detekt/baseline-shared.xml` holds **413** suppressions. It was observed
  growing 407 → 408 → 413 across recent branches, with no gate, no comment in
  CI, and no reviewer signal. A baseline that ratchets upward is not a record of
  known debt; it is a queue that silently accepts new entries.
- `config/docs/coverage-baseline.txt` and `test-runs-baseline.txt` state the
  rule in a comment — "record the SMALLEST value a legitimate run produces, and
  raise it in the same commit that produces a higher one" — and nothing checks
  it. A `--update-baseline` run against a broken or partial build lowers the
  floor silently, and every later run passes against the lower number.

The rule already exists in prose, in the baseline files and in
`2026-10-04-measurement-integrity`. This change makes it executable, which is
the difference the rest of that ADR is about: a gate that reports a boolean
where it could report a magnitude, and a comment where it could report a
number.

## Scope

### In scope

- A gate that fails when the detekt suppression count exceeds the committed one
- A gate that fails when a coverage or test-run floor is lowered without a
  recorded justification
- Reporting the delta, not just a boolean — the same magnitude-first rule the
  rest of the spec applies

### Out of scope

- Removing any existing suppression or raising any existing floor
- Changing which detekt rules are active
- Deciding what the 413 suppressions should become (that is issue-tracked
  separately; a ratchet makes the list stop growing, it does not shrink it)

## Impact

- Affected specs: `test-execution-integrity` (two added requirements)
- Affected code: `scripts/` (new or extended gate), `.github/workflows/ci.yml`,
  `check.sh`
- New config: the committed detekt finding count
