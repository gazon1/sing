---
title: "Detekt Rules Test Was Never Run By Any Gate"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Found in:** 2026-10-04 verifiability audit, while proving that the two
unconfigured rulesets could fire. The proof required running
`:detekt-rules:test` — and nothing in `check.sh`, `ci.yml` or the `justfile`
ran it.

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** #135

**Symptom:** `detekt-rules/src/test/` holds 10 test classes (56 tests) covering
the project's own custom rules. On first execution **4 failed**:

- `NoDirectDispatchersRuleTest > Dispatchers_IO is flagged`
- `NoDirectDispatchersRuleTest > Dispatchers_Default is flagged`
- `NoDirectDispatchersRuleTest > Dispatchers_IO in FileLogWriter is whitelisted`
- `NoEmptyOnClickLambdaRuleTest > onClick with empty lambda consumed via elvis is flagged`

Two of them proved `NoDirectDispatchers` **could not fire on the code it
targets**: the rule required `expr.selectorExpression as? KtCallExpression`,
but `Dispatchers.IO` is a property reference, so every real call site returned
early. The rule had never caught anything, and its own test said so. A third
passed a file path where `compileContentForTest` wants a package name (an
`IllegalArgumentException`, not a failed assertion). The fourth asserted
elvis-default handling the rule never implemented.

**Already ruled out:** not a stale Gradle cache — the failures reproduce from
clean, and the same PSI defect was independently observed in a live detekt run
against a deliberately-violating file.

**Resolved in the 2026-10-04 change:** the rule was fixed to accept both
selector forms, the two broken tests were corrected, the elvis shape was
implemented (empty-lambda *default parameter*, not just call-site argument), and
`:detekt-rules:test` was wired into `check.sh` and `ci.yml`. Kept here because
the general lesson is **not** enforced: a rule class with no test can still be
added, and nothing notices.

**Corrected 2026-10-05.** The "9 of the 18 rule classes have no unit test at
all" in this entry is **stale and was measured wrong**. It counted dedicated
`XxxRuleTest.kt` files. `RuleFiresSmokeTest.kt` gives a positive control to every
rule that had no dedicated file — all 20 rules are covered, and
`:detekt-rules:test` is 146 green tests. The counting mistake is itself the
subject of #135: a check that matches less than intended is indistinguishable
from a check with nothing to match.

**Try next (unchanged, now the only part that is open):** add a check that
**fails when a rule class has no positive control**, and prove that check can
fail by deleting one. Do not re-implement it as "grep for a test file named
after the rule" — that is the check that produced the wrong number above.
A rule is only as trustworthy as the test that proves it fires.

---
