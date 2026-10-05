# test-run-is-judged-by-its-results

Issues: #172 · Backlog entry: none — this work is in flight, not deferred. The related deferred
items are #171 (seed graph) and #173 (class-body scanning).

## What

Add to `test-execution-integrity` the two properties of the executed-test gate that
`3dbf625f` shipped without them, plus the one that protects the floor itself:

1. a test class the sources declare but the run did not execute **fails the build**;
2. a gate that **cannot evaluate its own check** fails the build, rather than
   reporting success for a check it did not run;
3. a floor entry is rewritten **only from a measurement** — a source set that did
   not run keeps the entry it had.

## Why

This change closes a spec-lag defect, not a design gap. The behaviour is already on
`main`; the normative spec does not describe it, and the spec is the artifact CI and
reviewers read.

`test-execution-integrity` opens by naming three faces of one defect — a test silently
skipped, coverage silently lost, a gate script silently broken — and says the spec
"pins all of them". There is now a fourth check, and the spec describes a weaker gate
than the one that runs. A spec that understates what is enforced is worse than one
that overstates it, because it is the one people trust.

**Why the count floor could not have caught this.** REQ-1 compares a run against a
number recorded earlier. That number is a floor, so it notices when *fewer* things run
than last time. It cannot notice a class that is newer than the number — adding a test
and having it silently skipped lowers nothing the floor can see. The two untagged
recurrence classes were exactly this: the floor was satisfied, and CI had never run
either class.

**Why property 2 is a separate requirement and not a footnote.** The by-results check
depends on a scanner in another module. The first implementation returned "unavailable"
and the caller treated it as "nothing to check". Appending one failing `import` line
to `infra/kiwi/sync.py` produced `Test run floors met` and exit 0 — the gate
reporting a verdict for a check that was not running. That is the same shape as the
defect it exists to catch, one level down, and it is the cheapest possible way to
reintroduce it: model *unavailable* as *nothing to check*.

**Why property 3 belongs with them.** `--update-baseline` emitted only the source sets
it happened to observe, so running it on a machine without a device deleted the
`shared:testAndroidHostTest` floor. That is precisely the failure the baseline file's
own header documents — a number that was never a measurement of a real run, which then
failed a CI job on first use. A floor that can be silently deleted is not a floor.

## What this does NOT do

- It does not change what the app does. No requirement here touches product behaviour.
- It does not require a test class to be `@Tag("fast")`; the check is scoped to `fast`
  because `shared/build.gradle.kts` maps an absent `-Ptest.tags` to
  `excludeTags("slow")`, so a plain local run executes exactly the `fast` classes and
  a fuller run is a superset. Extending it to `slow` would fail every local run and
  mean nothing in CI.
- It does not replace REQ-1. The floor and the by-results check measure different
  things: the floor catches a *narrowed filter* over classes it already knows about,
  the by-results check catches a class the floor has never heard of.
- It does not introduce a ratchet baseline of known holes. The measured state is zero
  declared-but-unexecuted classes across all three source sets, so such a baseline
  would start empty and could only ever be regenerated downward — which is the
  "regenerated on failure until it means nothing" failure the Kiwi ceiling ADR warns
  about.
- It does not retire the never-run ceiling that #157 covers.

## Rollback risk

Low, and the rollback is a revert. The three properties are additive to a gate that
already exists; removing them returns it to REQ-1's behaviour, which is the state that
let two classes be reported clean while CI skipped them. There is no data migration and
no product change. The one non-obvious risk is property 3: a contributor who has been
regenerating the floor file wholesale will now find unmeasured lines preserved, and may
read that as the command failing to update something.
