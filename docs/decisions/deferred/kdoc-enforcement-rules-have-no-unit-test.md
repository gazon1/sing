---
title: "Kdoc Enforcement Rules Have No Unit Test"
date: 2000-01-01
status: RESOLVED
tags: ["deferred"]
---

**Status: RESOLVED (2026-10-04).** `KDocEnforcementRulesTest.kt` now exists and covers both rules, including the nested-declaration case that guards the tree-walk fix. The rule suite is 90 tests, 0 failures, and runs in `check.sh` and `ci.yml`.
**Found in:** 2026-10-04 rule-verifiability inventory — the last rule class in
`detekt-rules/` with no test file.

**Symptom:** `KDocEnforcementRules.kt` contains `ViewModelMustHaveKDoc` and
`RepositoryInterfaceMustHaveKDoc`, both active in `detekt.yml`, neither tested.
The audit's own fix in the same change — switching them from `root.declarations`
(top-level only) to a full tree walk — landed without a test to catch it if it
were reverted or half-reverted.

**Already ruled out:** not inert. The rules do fire; the repo is simply clean
against them, which is indistinguishable from "never ran" until a violating file
exists.

**Try next — small and self-contained, roughly 30 lines of test:**
- a top-level `class TaskViewModel` with no KDoc → 1 finding
- the same class nested inside an `object` with no KDoc → 1 finding *(this is the
  regression guard for the tree-walk fix)*
- either of the above with a KDoc block → 0 findings
- a `class XRepository` that is not an `interface` → 0 findings

Assert exact counts. A `> 0` assertion would pass even if the tree walk
regressed to top-level for the nested case only in some configurations.

---
