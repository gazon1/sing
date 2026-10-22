---
title: The rules that guard the rules are cheap, and they are checked
date: 2026-10-22
status: accepted
tags: [process, gates, review]
---

# The rules that guard the rules are cheap, and they are checked

## Context

Four rules were written down in `AGENTS.md` over the last weeks, and each of
them cost a session a stop:

- *the `EXEMPT_VM_TEST` exemptions live here* — the note pointed at the
  harness file; they live in the conventions test that reads them;
- *a change is registered in `.specify/issues/<slug>.yaml`* — the importer
  wrote the record with four document names whether the change had them or
  not, so the record pointed at a `design.md` that was never written;
- *a `slow` test is a test the local loop never runs* —
  `McpServerEndToEndTest` was tagged `slow`, and it sat broken on `main`
  since the read surface changed the shape of its answer (`4e6e4d62`): the
  default local run excludes `slow`, so the breakage was invisible until CI
  found it;
- *a change gets a review by a separate agent before the PR is opened* — the
  review of the issue-registry gate found four real defects in a diff that
  had been written, reviewed and pushed, and nothing in the tree said so. The
  finding existed in the reviewer's answer, in a session that was about to
  end.

Each of these is a rule that is *written* and not *checked*. The registry got
a gate in the previous change; the other three did not.

The cost of checking them was measured before anything was written, because
the objection to more gates is always the same: the loop gets slower.

The static gate suite, on this tree, `sgate run-all`: **1 m 17 s** wall
(2 m 41 s CPU), 40 blocking gates. The three expensive ones are
*unwired surfaces* (70 s), *gates speak the JSON contract* (69 s) and
*declared dependencies are used* (23 s) — and all three are expensive
*because they run everything on purpose*: the first scans the whole Kotlin
tree, the second probes every gate to prove it can fail. They are the gates
that make the suite a suite.

The rules in question cost: the registry gate **1.1 s**, the review-verdict
gate **0.2 s**, the pointers gate **0.1 s**. The pre-push hook runs three
targeted gates today and takes seconds; adding three more that cost 1.4 s in
total changes nothing anyone can feel.

## Decision

Four changes, three of them new gates, all of them cheap:

1. **The pointers in `AGENTS.md` are checked.**
   `scripts/check-agents-pointers.py` reads the paths the file names and
   checks that they resolve. `{a,b}` and `...` are resolved; a path relative
   to prose around it is not checked, because where it is relative to is not
   written down and the gate would be guessing; a path inside a generated
   directory is not checked, because `build/` is a build product. A document
   that names no pointer at all is a failure: a check that finds nothing has
   proved nothing.

2. **A `slow` class that did not run is a failure.**
   `scripts/check-slow-tests-ran.py` reads the JUnit report of the full run
   and checks that every `@Tag("slow")` class is in it, with no skipped test.
   It is a CI step rather than a static gate, because it needs the report the
   test run produced — and it is placed immediately after the run, because
   every later Gradle invocation can re-run a test task and overwrite the
   report it reads.

3. **A change carries the verdict of its review.**
   `.specify/reviews/<slug>.md` states the change, the verdict, the date, the
   pull request, and what the review said. `scripts/check-review-verdicts.py`
   requires one for every open change. A `fail` is allowed and is the
   interesting case — it says the review found something; what is not allowed
   is a verdict nobody wrote.

4. **A record names only documents that exist.**
   The importer writes the documents the change has, not the four names it
   always used to write. The registry gate holds it to that: a record naming a
   document that is not there is a link to nothing.

### The baseline, and why it only shrinks

The changes that predate the review rule are in
`config/docs/review-verdicts-baseline.txt` — 77 slugs, the shape every other
baseline in this repository has. `--update-baseline` *prunes* it and refuses
to add to it: accepting a new exception is an edit in the diff that a
reviewer can see, not a side effect of a maintenance command.

The same reasoning turned down the reverse direction of the registry check.
The gate walks the changes and looks up their records; it does not walk the
records, because 575 of the 681 records name a change that no longer exists
anywhere in the tree — they were deleted, not archived. A baseline of 575
entries is a list, not a rule, and it would have to be regenerated every time
a change was deleted. The direction that can still go wrong for work
happening now is the one that is checked.

### What the local loop carries

The pre-push hook runs the three new rules against the **pushed tree** — the
temporary worktree, not the worktree the author happens to be in — because a
rule that reads a different tree than the one being pushed checks the wrong
thing. That is where a contributor meets the rules: in seconds, not in a CI
round trip.

The suite as a whole is deliberately not in the hook. It is a minute and a
half, and two thirds of it is two gates that must run everything to prove
what they prove. Making those incremental would be making them weaker: the
scan of the whole tree is the point of both.

## Rationale

- The rules were already written. The gap was that nothing asked whether they
  held.
- The measured cost is what made this a decision rather than a wish: 1.4 s
  for the three rules, against a suite whose expensive part is expensive on
  purpose.
- A review that leaves no trace cannot be read by the next session, and
  cannot be checked by a gate. Making it a file is what turns it from a
  practice into a record.
- The baseline that only shrinks keeps the rule from becoming a dumping
  ground: the failure mode of every baseline in every repository is that it
  grows until it is the answer to nothing.

## Consequences

- A change that lands without a review verdict is a red CI run, with the
  fix named: write the verdict, or accept the change in the baseline. Every
  open change from before this rule is in the baseline.
- A PR that adds a change has to bring the review with it. That is the rule
  `AGENTS.md` already stated, now enforced.
- The registry records changed in bulk: 100 of them lost the `design.md` and
  `adr.md` lines they never had. The diff is mechanical and is what
  `python3 scripts/openspec-import-all.py --replace` writes.
- A `slow` class that is renamed, moved, or deleted changes the full run's
  report, and the CI step reads that report right after the run.

## Links

- `scripts/check-agents-pointers.py`
- `scripts/check-slow-tests-ran.py`
- `scripts/check-review-verdicts.py`
- `scripts/check-issue-registry.py`
- `config/docs/review-verdicts-baseline.txt`
- `docs/decisions/2026-10-22-a-change-is-registered-or-it-is-not-a-change.md`
- `docs/decisions/2026-10-09-detekt-class-signature-gotcha.md`
