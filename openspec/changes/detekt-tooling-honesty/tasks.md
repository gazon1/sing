# Tasks — detekt-tooling-honesty

## Reproduce before fixing

- [ ] **#136 — where is the detekt plugin classpath cached?** Reproduce: `./gw --stop`, add one
      rule to `detekt-rules/src/main/kotlin`, re-run `:shared:detekt` *without* `--stop`. If the
      rule set is stale, the cache is the daemon's classloader or a Gradle transform keyed on a
      stale input hash — `--no-configuration-cache` already fails to fix it, which rules out the
      configuration cache.
- [ ] **#137 — why does `detektBaseline` not load the custom rules?** The two tasks resolve
      different plugin classpaths from the same build file. Bisect `shared/build.gradle.kts`:
      `detekt { baseline = … }` is set in the extension block, `detektPlugins(project(":detekt-rules"))`
      in a separate `dependencies { }`. Confirm which task picks up which.
- [ ] **#138 — rule out the cache before the other two hypotheses.** `./gw :shared:jvmTest
      -Pkover.jvmTest=true -Ptest.tags=fast,slow --tests '…KoinGraphValidationTest' --rerun-tasks`,
      then `koverReport`, then read `CalendarSyncDiModuleKt` out of the XML. Only if it is still
      4/18 is the attribution question real.
- [ ] **#146 — settle the order, because #138's cheapest hypothesis is the ratchet's own doing.**
      `coverage-ratchet` wipes every module's `kover` dir and then runs `koverReport`; wiping stale
      `.bin` is right, but it does not invalidate the test task, and `:shared:jvmTest` can come back
      `UP-TO-DATE` — re-executing nothing and contributing no fresh data. Settle it as
      **measure, then ratchet** (or make the wipe invalidate the test task), record the decision in
      `config/coverage-ratchet.json` next to the floors, and only then re-measure #138. Cross-links
      #59 and #138; the `feature/calendar_sync` floor's note is a loan against this and says so.

## Then guard

- [ ] One check, three uses: assert that a **planted violation of a known custom rule is still
      reported** after a daemon start, after a `detektBaseline` run, and at the start of a
      coverage measurement. This is the guard that would have caught all three, and it is the same
      shape as `RuleFiresSmokeTest` at rule level. Cross-link #135 — positive tests exist and still
      did not catch this, because the tests ran and the rules did not.
- [ ] Make the guard runnable in one command (a `just` recipe), and run it *before* anyone trusts
      a green `just lint`. A check that only CI runs is not available to the person about to
      regenerate a baseline.
- [ ] Until the guard exists: document the safe order in the rule-authoring skill — never run
      `detektBaseline` without `git diff` on the baseline immediately after, and treat a
      *shrinking* custom-rule section as a red flag rather than progress. Cross-link #137 and #58.
- [ ] **#145 — the visibility half of #135 has landed; the enforcement half has not.**
      `scripts/gen-detekt-rule-table.py` writes a `Test` column per rule and `--check` is wired into
      `check.sh` as step `[8b/21]`, but `--check` passes just as happily on an empty cell — it proves
      the table has not drifted, not that a rule is tested. Fold the enforcement into #135 rather
      than making it a second gate, and reuse the generator's existing fake-tree harness. Do not
      rebuild the inventory half: it is generated, so it cannot go stale, and it already answers
      "which rule lacks a test" without a grep.

## Close out

- [ ] Amend `2026-10-05-no-direct-dispatchers-rule-was-a-no-op.md` if the guard generalises it —
      that record is about a rule that could not fail; this is about rules that were not run, which
      is the same class with a different cause.
- [ ] Note the interaction with #58 in whichever of the two lands first. They share a file and an
      incantation: #58 is entries that never leave the baseline, #137 is entries that leave it
      silently. A fix for one that does not consider the other will reintroduce it.
