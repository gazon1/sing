---
title: "The Maestro run is measured against a floor the repository owns"
date: 2026-10-28
status: accepted
tags: [maestro, e2e, tooling, gates]
---

# The Maestro run is measured against a floor the repository owns

## Context

The Maestro run produced a measurement, and nobody consumed it.

A full run of the catalogue — 94 flows — finished on 2026-10-21 with 55
passing, 39 failing and no device death. Every one of those 39 failures wrote
a JUnit XML file into `build/maestro-results/`, and every one of them said
what went wrong: `No visible element found: id: attachments_add_file_row`,
`Assertion is false: id: task_detail_title is visible`. The runner's exit
status said "39 failed"; the CI log said the same. Nothing else in the
repository knew it.

Three things were missing, and each of them is a way the debt can grow
unnoticed:

1. **No floor.** The number of failing flows lived in a log line and in the
   memory of whoever read it. A run that failed 45 flows instead of 39 was
   the same green-and-red as a run that failed 39, because the runner already
   failed on the first failure — the growth was invisible, because the only
   comparison available was "red against red".
2. **No requirement that a flow reported.** A flow that never ran produces no
   XML file, and a missing file is indistinguishable from a flow that was
   never in the suite. The runner checks this for the flows it selected
   (`_report_is_fresh`), but the check is inside the run: it is a fact about
   one run, not a fact the repository holds.
3. **No grouping.** The causes lived in a one-off script that somebody had
   run by hand to find out what was broken. Thirty-nine failures, each a
   separate line, read as thirty-nine problems; they were five.

The failure causes were also *worse* than "the app has bugs". Twenty-six of
the 39 failures were the same shape — an element the flow asserted was not on
screen — and five of them were one missing row (`attachments_add_file_row`)
in five attachment flows. That is a handful of defects, not a handful of
dozens, and no repository artefact said so.

## Idea

One gate that reads the run's reports and holds the run to three things, plus
a table that says what the debt is made of:

- every flow of the scope has a report (a flow that did not report is a
  finding, not a skip);
- every report belongs to the scope (a leftover makes the measurement a
  measurement of the wrong run);
- the number of failing flows is within a floor, and the floor is a number in
  a file that the repository owns.

## Decision

**The gate** is `scripts/ci/check-maestro-results.py`. It reads the JUnit XML
the runner wrote, and it reports four kinds of finding: a flow that did not
report, a report outside the scope, two flows that would write the same
report file, and a run that failed more flows than the floor allows.

**The floor** is `config/docs/maestro-failure-floor.txt` — one
`<scope> <max-failing-flows>` line per scope, `full 39` and `smoke 10`
today. It is data, not a constant in the gate's code, and nothing writes it:
lowering it is a deliberate edit to the file, and the diff that shows the
debt shrinking is somebody's decision. A floor a script regenerates is a
floor that moves by itself, and the moment it moves by itself nobody reads
the diff any more.

**The ratchet points one way.** More failures than the floor fails the gate;
fewer passes. An improvement never needs an argument and never needs a
change to the file — the floor stays where it is until the debt is smaller
than the floor, and then somebody lowers it on purpose.

**The scope is the suite the runner ran.** `full` is every flow in
`config/docs/maestro-flows.txt` — the nightly's four shards together.
`smoke` is the flows that declare the `smoke` **tag**, read by the same
reader the runner filters with (`scanners.maestro_flow`). Not the directory:
22 of the 29 smoke flows live outside `Maestro/flows/smoke/`, and the runner
filters on the tag, so a scope built from the directory would measure a
different suite than the one that ran. (The catalogue's own header still
says the directory decides the suite — that prose is stale, and the tag is
the runner's authority.)

**The gate runs on the shards' combined reports**, in the `maestro-results`
job of `.github/workflows/e2e.yml`, after the `e2e` job. Not inside a shard:
the floor is a number for the whole suite, so a gate that ran inside one of
the four nightly shards would measure a quarter of the suite against the
whole suite's floor, and pass on debt it could not see. The job downloads
every shard's `maestro-junit-*` artifact and merges them into one directory.

**A flow without a report is a failure, not a skip.** This is the rule that
makes the gate worth having. A run that reports 38 of 39 flows is not a
mostly-green run; it is a run that silently stopped covering one flow, and
the difference between the two is invisible in every artefact except the
catalogue — which says which flows exist, not which ones ran.

**The failures are grouped by cause.** The group key is the failure message
with the run-specific parts removed: the element's id becomes `<id>`, a
quoted expectation becomes `<text>`, a run-id or a long number becomes
`<n>`. The grouping is deliberately coarse — it produces *cause classes*
(26 + 7 + 4 + 1 + 1 flows today), and the concrete messages are printed
under each group, so the ids that make it up stay visible. A grouping that
kept every id would be a list of 39 lines wearing a table's clothes.

**The debt is recorded in the issue registry**, one record per group, in
`.specify/issues/`. Five records today, each naming the flows and the
elements involved. The backlog lives in the repository, not in the body of a
pull request, and it is the same place every other issue lives.

## Rationale

**Why a floor and not a fixed expectation of zero.** The suite has 39 known
failures. A gate that demanded zero would be red from the first day and
would be ignored from the second — the ratchet works only if the thing it
measures can be on both sides of the line. The floor makes "green" a
statement about the debt, not about the absence of debt, and it makes the
debt's size a diffable fact: `full 39` → `full 38` is a commit that says
what was fixed.

**Why the floor is a file and not a constant.** A constant in the gate's code
is changed in the same commit as the gate, and nobody reads that commit's
diff for a number. A file is data: the diff shows the number changing, the
number is reviewable, and the change is visible to whoever reviews it.
Putting the floor in a file also keeps the gate honest about what it is: it
measures, it does not decide.

**Why one gate and not two.** "Every flow reported" and "the failures are
within the floor" are two rules about one measurement, and they are read
from one artefact. Two gates would mean two runs over the same 94 XML files
and two places to look for the answer.

**Why the job runs after the shards.** The measurement has to be of the whole
suite. A shard-local measurement would have to carry a shard-local floor, and
four shard-local floors are four numbers to keep in step with one suite; the
moment the shard count changes, all four are wrong. One floor for one suite
is one fact.

**Why `always()` in the job's condition.** The measurement matters most when
the run failed. A job that only runs when its dependencies succeeded would
never measure the runs that need measuring — it would report on the runs
that were already fine.

**Why the failures are grouped at all.** Thirty-nine failures, each on its
own line, is a list; five groups is a backlog. The grouping is what makes
the debt actionable: `attachments_add_file_row` missing in five flows is one
defect, and the five-flows-one-cause shape is invisible in the flat list.

## Consequences

**The gate adds a job.** `maestro-results` in `e2e.yml` — a checkout, the
tooling install, an artifact download, and the gate. It costs about a minute
of a nightly run, and it runs after the shards, so it does not lengthen the
emulator fleet.

**The gate does not make the failing flows pass, and it does not excuse
them.** The `e2e` job is still red when any flow fails, and it should stay
that way: the floor is the *accepted* debt, not a permission. The gate's
claim is narrower and is the one the repository was missing — "the debt is no
bigger than the number in the file".

**The floor is now a thing to maintain.** A new failure has to be paid for:
either the flow is fixed, or the floor is raised deliberately, in a commit
that says so. That is the point of the exercise, and it is also the cost of
it — a suite that fails more often than it is fixed will push the floor up
until somebody notices. A floor that only ever rises is a floor that has
stopped measuring anything, and the digest of the issue records is where
that shows up: five records, each with the flows it covers.

**The records in `.specify/issues/` are hand-written.** The importer
(`scripts/openspec-import-all.py`) rebuilds the records it owns from scratch
and writes each file wholesale, so a record it owns is not a place to keep a
measurement — it would not survive the next `--replace`. These records
therefore carry `type: maestro-failure` and `speckit_imported: false`, and
they are not OpenSpec changes.

**A stale results directory produces findings.** The runner does not clean
`build/maestro-results/` before a run, so a local smoke run after a local
full run leaves the full run's reports behind, and the gate reports them as
reports outside the scope. That is the finding doing its job: the directory
holds more than the run produced. CI is unaffected — its runners are fresh.

**The digest is not refreshed by this change.** `DIGEST.md` is generated from
`docs/decisions/`; the author of the change regenerates it, so that two
writers do not race for the same file.

## Links

- `scripts/ci/check-maestro-results.py` — the gate.
- `config/docs/maestro-failure-floor.txt` — the floor, per scope.
- `config/docs/maestro-flows.txt` — the catalogue the run is measured against.
- `.specify/issues/maestro-failure-*.yaml` — the debt, one record per group.
- `scripts/check-maestro-flow-catalogue.py` — the gate that holds the tree to the catalogue.
- `docs/decisions/2026-10-21-one-manifest-and-one-placement-table.md` — the placement table this gate joins as `ELSEWHERE`.
