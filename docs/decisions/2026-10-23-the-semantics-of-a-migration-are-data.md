---
title: "The semantics of a migration are data"
date: 2026-10-23
status: accepted
tags: ["room", "schema", "migration"]
---

# The semantics of a migration are data

## Context

The round-trip test proves the *schema*: a database of version N survives the
chain and lands on the head schema. It says nothing about the *meaning* of a
step.

The meaning was checked by hand-written SQL. Two of those tests — the v31 → v32
backfill and the v44 → v45 link grammar — carried the claim that mattered:
`ai_proposal.task_id` is backfilled into `target_id` before the column is
dropped, and `notes.task_id` is filled from the note's own links. Both are
statements about *data*, and neither is a statement about a schema.

What those test classes had in common was not the claim but the plumbing. Each
one carried its own copy of the fixture — a temp directory, a file path, a
teardown — its own copy of "open the file, run the chain, read it back", and a
wall of SQL in which the *arrangement* and the *point* looked the same. In the
v31 → v32 test, the line that says what the migration did and the twelve lines
that say how the fixture was built were the same kind of line. A reader had to
know the schema to tell them apart.

And the other ten steps had no such test at all.

## Idea

Write the claim down as data, and generate the test from it.

A scenario is: the version the fixture starts at, the rows to plant, the
queries to run, and the values those queries must return. That is the whole
sentence. The parts that every test shared — the fixture, the chain, the
round trip — are the parts that are *not* the claim, and they have exactly one
owner.

## Decision

The scenarios live in YAML under `shared/src/jvmTest/resources/migrations`, one
file per step. `python3 scripts/check-room-schema-integrity.py --write` writes
the Kotlin test from them, and the rule `migration-scenarios-current` fails
when the committed test is not what the scenarios say — so editing a scenario
without regenerating is a red build rather than a test of something else.

The generated test is a **parameterized** test with one case per scenario, not
one function per scenario: detekt allows sixteen functions in a class, and a
repository with sixty migrations would have sixty scenarios. The round-trip
test made the same move for the same reason.

The generator writes rows as SQL and task rows through the existing legacy-task
fixture — a task carries a dozen columns the schema demands and the scenario
does not care about, and the list changes with every schema change. The
fixture asks the database what it wants.

The generator checks its own output before writing it (the detekt thresholds:
120 columns, 16 functions), for the reason the round-trip generator does: two
Gradle round trips on a busy host is the price of a fact a Python check can
answer in milliseconds.

## Rationale

The version pair in a scenario file is *provenance*, not a range. Room applies
the whole chain from the fixture's version, so a scenario written for 31 → 32
is checked against the head schema. The step's own effect is still what the
scenario asserts — a backfill that happened at 31 → 32 is visible at the head —
but a scenario cannot claim that the *only* thing that changed between 31 and
32 was its own. A scenario that needs that has to say so in its own name.

The fixture is Room's own export of the older version, so it is what a user of
that version would have had. Opening the file through Room then runs the chain
*and* validates the schema it produced: the scenario only has to hand Room a
database worth checking.

## Consequences

- The meaning of a step is a file a reader can read in one sitting, and the
  generated test is a mechanical consequence of it.
- A scenario is cheap to write, so the ten steps without a test are no longer
  special: they are the steps nobody has written a scenario for yet.
- The hand-written tests are not deleted by this. They are the *other* kind of
  test — the ones that assert a property the schema cannot express at all —
  and they stay until a scenario says the same thing more clearly.

## Links

- `scripts/src/sgate/gatekit/scenarios.py` — the format and the renderer.
- `scripts/check-room-schema-integrity.py` — the rule
  `migration-scenarios-current` and the `--write` that generates the test.
- `shared/src/jvmTest/resources/migrations/` — the scenarios.
- `docs/decisions/2026-10-23-the-migration-chain-has-a-generated-round-trip-test.md`
  — the schema half of the same story.
