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
(2 m 41 s CPU), 40 blocking gates before this change and 42 after it. The three expensive ones are
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
records, because 575 of the 676 change records name a change that no longer
exists anywhere in the tree — they were deleted, not archived. (The registry
holds 681 records; the other five are not changes.) A baseline of 575 entries
is a list, not a rule, and it would have to be regenerated every time a
change was deleted. The direction that can still go wrong for work happening
now is the one that is checked.

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
- The registry records changed in bulk: 101 records changed, 101 of them
  lost the `adr.md` line they never had and 82 lost the `design.md` line they
  never had, 7 records' `specs_dir` became `null` (the change has no
  `specs/`), and one record's path was corrected to where the change was
  archived. The diff is mechanical and is what
  `python3 scripts/openspec-import-all.py --replace` writes.
- A `slow` class that is renamed, moved, or deleted changes the full run's
  report, and the CI step reads that report right after the run.
- **Archiving a change needs no bookkeeping.** The record keeps the path the
  change had before the move, and the gate looks its documents up by slug in
  the archived directory — the same convention the slug comparison already
  followed. A verdict for an archived change stays: it is a record of a review
  that happened. The first version of this change got both of these wrong and
  turned a routine archive operation red; the review caught it, and the
  archive is now a move and nothing else.
- **A change the spec-create workflow has just scaffolded is exempt** until it
  is written up. The scaffold is detected by the workflow's own marker and
  the placeholder it leaves in the proposal — nobody has written the change
  up, so nobody has reviewed it, and there is nothing to have reviewed yet.
  The exemption ends by itself when the author writes the proposal.
- **`:adr-cli:test` and `:adr-core:jvmTest` now run in CI.** Both modules hold
  a `@Tag("slow")` class, and neither test task ran anywhere — so the rule
  this change introduces was, at its first version, blind to two slow classes
  in the two modules that never ran at all. The gate's mapping is now checked
  against the repository by a test, so a slow class in an unmapped root fails
  the build rather than passing unseen.

## What the review found

The change was reviewed by a separate agent that got the diff and the ADR and
no session history. It returned **FAIL** on three findings, all of which are
fixed here:

- the slow rule did not cover *every* slow class: two modules were unmapped,
  and the tests in them ran nowhere — the exact failure the rule was written
  to prevent, live in the tree the rule was written for;
- archiving a change turned both new gates red, and the verdict gate named a
  fix that does not apply — the same class of error the predecessor decision
  was corrected for twice: the rule was not what the prose said;
- the pre-push hook ran *today's* rules against an *older* pushed tree, so a
  branch cut before the change could not be pushed without `--no-verify`, for
  violations CI would never report on it. The hook now runs the rule that is
  in the pushed tree — the same copy CI would run — and says so when the tree
  does not have it.

The review also found that the success message claimed more than the code did
("every open change carries a verdict", in a tree where none did) and that
the baseline remedy contradicted the rule that the baseline only shrinks. Both
are fixed: the gate now reports the split, and the remedy says that adding an
entry is an edit in the diff.

A second pass over the fixes found one more blocking defect, and it was the
one this repository already has a name for: **a gate that cannot pass**. The
slow gate matched a Kotlin Multiplatform suite by its name, and a KMP suite
is named `ClassName[target]` — so every slow class in a KMP module read as
"did not run", and the CI step would have failed on every run of the tree it
was written to protect. The suffix is stripped now.

The second pass also found that the scaffold exemption keyed on a leftover
placeholder *line*, which an author could have left in place while writing
the proposal up; the exemption now compares the proposal with the workflow's
own template, line for line, so it ends when the proposal is written. And it
found that the spec-create workflow wrote a registry record naming a
`design.md` and a `tasks.md` it never creates — the same defect the importer
had, in the workflow; the workflow now names only the document it writes, and
its `specs_dir` is written as `null` when the change has no `specs/` — the
same word the importer uses, because a record that names a directory that
never existed is the finding the registry gate exists to report.

A third pass returned **pass**: no blocking finding, and the branch's gates were
falsifiable in the ways the earlier passes had found them not to be — the slow
gate fails on a missing suite, on a skipped test and on an unmapped root, the
verdict gate's baseline cannot grow, and the pre-push hook was pushed end to
end against a clone, an old tree and two broken ones. What it did leave is the
placement this file discusses below, plus two small things that are fixed here:
the suite name of a KMP suite had no regression test, so the defect the second
pass found could have come back unnoticed, and the workflow said the slow
classes live in six source sets when they live in seven.

The ADR invocation in CI also had to move. It is the second test-capable
Gradle invocation in the job, and `check-build-output-readers.py` requires
every step that reads build output to read it *before* an invocation that can
rewrite it — so the ADR invocation sits below the traceability normaliser,
the matrix and the flake pass, and the `slow` gate sits below the ADR
invocation because it reads what both invocations wrote. The dependency edge
that makes this safe is the one `:adr-cli`'s build file exists to enforce: it
depends on `:adr-core` and `:core-base`, never on `:shared`, so the ADR
invocation cannot rewrite the reports the readers above it measured.

One placement in that job is not checked by the placement rule, and the reason
is worth writing down rather than discovering later.
`check-build-output-readers.py` recognises a reader by the build paths in the
step's command text, and the `slow` gate's command names the script, not the
paths — so the gate sees a step that reads nothing, and a step moved below
`Coverage floors` would be accepted silently. Naming the paths in the command
would make the gate see it, and would make the gate fail: the ADR invocation
above is the second test-capable invocation in the job, and the rule says a
reader reads before an invocation that can rewrite what it reads, while this
step has to read exactly what that invocation wrote. The two cannot both hold
inside one job.

Three ways out were considered. The ADR modules could join the main
invocation, which would leave one invocation in the job and make the rule
apply — but a test task in the kover invocation joins the coverage aggregate,
and the ADR modules are 93 covered lines of 1008: the floor would move, and
moving the floor is a decision about what is measured, not a fix for a
placement. They could run in a job of their own, with their reports handed to
this one as an artifact — that works, and it costs an artifact contract and a
serialised job. Or the placement can be kept and written down where the next
reader of the workflow will see it.

The third is what this branch does, and it is the weakest of the three: the
placement is maintained by hand, and the comment in `ci.yml` is what makes it
maintainable. It is recorded here as an open edge rather than presented as
solved, because the honest description of it is "a rule that is written down
and not checked" — the exact thing this change set is about.

## Links

- `scripts/check-agents-pointers.py`
- `scripts/check-slow-tests-ran.py`
- `scripts/check-review-verdicts.py`
- `scripts/check-issue-registry.py`
- `config/docs/review-verdicts-baseline.txt`
- `docs/decisions/2026-10-22-a-change-is-registered-or-it-is-not-a-change.md`
- `docs/decisions/2026-10-09-detekt-class-signature-gotcha.md`
