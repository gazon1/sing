---
title: "The Generated Rule Inventory Enforces Nothing"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "detekt-tooling-honesty"]
---

**Status: CLOSED** (2026-10-05) — `--check` now fails on a rule with no positive control.

**Tracked as:** [#145](https://github.com/gazon1/sing/issues/145) ·
`openspec/changes/detekt-tooling-honesty/`

**Found in:** 2026-10-05, running `python3 scripts/gen-detekt-rule-table.py --check` to confirm the
inventory still matched the source. It reported `OK — 25 rules, table matches source`.

**Symptom.** That `OK` proved the table had not drifted. It said nothing about the `Test` column, and
it passed just as happily when a rule had no positive control and the cell was empty. The column was
added so an untested rule is *visible* in the file a rule author opens. Visible is not enforced.

**Already ruled out.** Not a gap in the inventory — it is generated, so it cannot go stale, and it
makes the "which rule lacks a test" question answerable without a grep. #135's correction about
matching is what the enforcement had to honour: a dedicated-filename-only check reports five untested
rules and should report zero, because `RuleFiresSmokeTest` covers them. So both shapes count as
tested, and the column says which one it found.

**Fix.** `--check` now fails and names the rules. Proven by deleting the new rule's dedicated test
class and confirming red — which took two runs, because the first failed on *staleness* instead: the
table had to be regenerated before the empty `Test` cell was visible to the check. Worth recording,
because a sabotage test that fails for the adjacent reason looks like a passing one.

`just cr` now also refuses to compare floors against a run in which the test tasks did not execute.

---
