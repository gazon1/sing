---
title: "A registry is checked, not written"
date: 2026-10-28
status: accepted
tags: [gates, docs, skills, registry]
---

# A registry is checked, not written

## Context

Three places in this repository are lists that claim to be lists of something
else: the text-field transformations in
`.agents/skills/singularity-todo-text-field-states/SKILL.md`, the platform
ports in `docs/PLATFORM-REFERENCE.md`, and the Maestro debt behind
`config/docs/maestro-failure-floor.txt`. Each of them is read by somebody who
has no way to know whether it is true.

They were not equally bad, and the difference matters.

The platform reference said so itself: "nothing here is generated … if a port is
added or renamed, this file is the thing that goes stale, and no gate will
notice". It had in fact rotted — it still named `NotificationPort`, a port that
was deleted when `Notifier` replaced it, and `TimeZoneProvider`, which is gone
too, and it did not name the twenty ports the seam actually has. A reader
adding a binding to `Notifier` could not see that they were crossing the seam.

The transformations table said nothing about being checked, which is worse: an
unguarded table reads as a fact. A transformation could be added, renamed, or
have its rule changed, and the table kept claiming the old thing.

The Maestro floor was in the best shape, and the reason is worth keeping. The
floor is a number, and a number that nothing checks is a number nobody can
hold the repository to. So the run was measured against it — and the *shape* of
the debt moved into a registry of causes
(`config/docs/maestro-debt.yaml`), each cause with the issue that owns it. What
was still missing was the link from the number to the flows: a run's summary
said "30 failed" and named the causes, and not the flows that carried them.

## Idea

A registry is a promise that the list is the list. The promise is worth
nothing unless something checks it, and the thing that checks it has to read
both sides — the document and the code — because a gate that reads only the
document proves nothing and a gate that reads only the code leaves the document
free.

Each document says, per row, what is checked. A row that cannot be checked
says so in the row, so a reader can tell which half of the table is a fact and
which half is a sentence. That is the same rule the repository already wrote
down in `2026-10-21-the-registry-says-only-what-it-checks.md`: a column is
either checked or gone, and what is left over is prose and says so.

## Decision

**A registry is checked, not written.** Three gates, one per registry:

- `scripts/check-text-field-transformations-table.py` reads the skill's table
  and compares it with `TextFieldTransformations.kt`. Every transformation the
  code declares is a row; every row names a transformation the code declares;
  the row's parameters are the function's parameters; and the facts the row
  claims — the digit limit, the length bound, the range — are derived from the
  source and compared with the row. The rule sentence is checked as far as a
  sentence can be: it has to *mention* the fact, so a sentence that stops
  saying what the code does fails too.
- `scripts/check-platform-reference-ports.py` reads the reference's ports table
  and compares it with the code. A port is an interface in a `commonMain`
  source set that a platform module binds; every such port is a row, every row
  names such a port, and an implementation a row attributes to a platform has
  to exist in that platform's source set. Infrastructure bindings are excluded
  by the seam registry, which `PlatformSeamGuardTest` already holds against the
  code — so the reference is checked against the code by two gates, not by a
  list of its own.
- `scripts/ci/check-maestro-results.py` names every failing flow of a run, in
  the summary and in the JSON payload, so the floor's number is traceable to the
  flows that carry it.

The choice for the Maestro registry is deliberate. The repository has **no**
hand-written list of failing flow names — not in the docs, not in the skills —
and the cause registry already names the debt's *shapes*, with owners. A
flow-level list would be a second registry for the same debt, kept by hand,
and it could not be populated truthfully without a device run: the calibration
run's thirty failing flows are not recorded anywhere in the tree. So the
flow-level list was not written. What was delivered is the weaker, honest
thing: the number is traceable to the flows, because every run names them.

## Rationale

The alternative for each registry was to write the missing list by hand and
check it. For the platform reference that is what happened: the ports table
was rewritten from the code's twenty-five ports, and the prose that could not
be checked moved into a column that says `prose`. For the Maestro debt it
could not be done honestly, and inventing thirty flow names would have been
worse than leaving the number alone: a list of names nobody verified is a
registry that rots on the first run, and it would have looked checked.

The transformations table could not be rewritten from the code either — a
table's rule is a sentence, and the sentence is the part a human needs. So the
table grew a column that says what the code enforces, and the sentence was
held to that column by a keyword check. It is a shallow check and it says so:
it asks whether the sentence still mentions the fact, not whether it says it
well, and whether the rule is the *right* rule for the field it is used in is
not checked at all. That case belongs to the tests of the rules themselves.

Every new rule has a positive control: the sabotage mutates an input in the
way the rule hunts for and the gate has to report it. The transformations
control changes the digit limit of the recurrence rule in the table; the
reference control renames `Notifier` back to `NotificationPort`, the exact rot
the document had already suffered once; the Maestro tests write reports with
failing flows and assert that the summary names them. A rule nobody has seen
fail can be broken rather than clean.

## Consequences

- The platform reference is now true: twenty-five ports, twenty-five rows, one
  prose row (`AttachmentStorage` is a class, not a port). It was wrong before,
  and the gate is what makes it right rather than lucky.
- The transformations table is now a claim about the code, and the gate says
  which parts of it are a fact. The parts it cannot check — whether a rule is
  the right rule for its field, the order of the filter and the range check —
  are named in the document.
- The Maestro floor's number is traceable: a run's summary lists the flows that
  failed next to the count. The debt's *shape* is still the cause registry, and
  the floor file says so.
- The gates are ordinary: they read files, they fail with findings, they are
  registered in `sgate` like every other gate, and each has a control.
- The cost is a table that a change to the code must now touch. That is the
  point — the change was going to happen anyway, and the table was going to be
  wrong if nobody said so.

## Links

- `scripts/check-text-field-transformations-table.py`
- `scripts/check-platform-reference-ports.py`
- `scripts/ci/check-maestro-results.py`
- `scripts/src/sgate/commands/_registry.py`
- `scripts/src/sgate/gate_manifest.py`
- `.agents/skills/singularity-todo-text-field-states/SKILL.md`
- `docs/PLATFORM-REFERENCE.md`
- `config/docs/maestro-failure-floor.txt`
- `config/docs/maestro-debt.yaml`
- `docs/decisions/2026-10-21-the-registry-says-only-what-it-checks.md`
- `docs/decisions/2026-10-28-maestro-measurement-gate.md`
- `docs/decisions/2026-10-28-a-seed-does-not-carry-the-field-text.md`
