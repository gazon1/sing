---
title: "The broad scan is measured, and it is not the path the product reaches"
date: 2026-10-22
status: accepted
tags: [search, performance, measurement, database]
---

# The broad scan is measured, and it is not the path the product reaches

## Context

The search asks the index which records carry the word, and reads only those
(ADR 2026-10-21, ADR 2026-10-22). A rule the index *cannot* answer is
answered in memory: the rows are read and looked at. The plan that says so is
`TextMatchPlan.BroadScan`, and it is reached by a rule with no positive term —
a rule that says only what a record must not carry.

Issue #944 proposes making that path cheap: answer the negated terms by
subtraction, so the index says which records carry the word and the negation
takes the rest away. The roadmap says to measure the share of the broad scan
*before* starting the optimisation — "not to begin an optimisation without a
number".

## What was measured

The plan of every query of a corpus, and what each plan costs.

**The corpus is the queries this project exercises** — the free-text queries
of the search tests, the queries the Maestro flows type, the filters the filter
sheet produces, and the two shapes the product's own surfaces cannot produce.
It is not a sample of what people type, and the measurement does not pretend to
be: the app keeps no history of the queries it was given, so there is nothing
to sample. A number taken from a corpus the project made up would be a number
about that corpus.

What the corpus can answer is which *shapes* reach the broad scan, and whether
the product's surfaces can produce them. That is the question the decision
turns on: a plan change that makes the broad scan cheaper is worth its cost if
people reach it.

**The numbers** (held by `SearchPlanShareTest`, so a change in the plan
semantics breaks the measurement instead of quietly changing what the search
costs):

- 20 queries in the corpus;
- **14 are narrowed** — the index answers them (70%);
- **6 reach the broad scan** (30%): the four filters of the sheet
  (`priority:high`, `due:today`, `pinned`, `tag:work`), and the two shapes the
  product cannot produce (`NOT beta`, `alpha OR NOT beta`).

## The two costs, which are not the same

A broad scan is not one thing, and the share alone does not say what it costs.

- A rule with **only negated terms** reads every task of the profile and
  filters it in memory. This is the full scan the index was built to avoid.
  In the corpus: **2 of 20** — and both are shapes no surface of the app
  produces. The filter sheet has no "not this text" filter, and the quick
  search's free text is a positive term.
- A rule with **no terms at all** — a query of filters only — reads every
  note, project and tag to find that it matches none of them. The tasks are
  filtered by the database and are *not* scanned; the other three kinds are
  read for nothing, and the read is a live flow: it runs again on every change
  to the notes, to the projects and to the tags. In the corpus: **4 of 20** —
  and this is the everyday case, because every filter from the sheet is a
  query of filters only.

## Decision

**The waste is removed now.** A query with no free text matches no note, no
project and no tag, and the answer is known before the question is asked: the
search does not read them. The cost of the fix is three lines and a reading
that was made for nothing.

**#944 stays in the backlog.** The path it would make cheaper is the one the
product's surfaces cannot reach, and the number of people who reach it by
hand is not knowable: the app keeps no history of its queries, and a
measurement taken over a corpus the project wrote is a measurement of the
corpus. The plan-semantics change — a "narrowed" plan with no positive term —
is a real change to what the plan *means*, and it buys a path that the
measured corpus says nobody walks on.

The number is attached to the issue, so the decision can be revisited when a
number that is about people rather than about a corpus exists.

## Consequences

- A search of filters only no longer reads the notes, the projects and the
  tags. The results are the same — they were empty before, and they are empty
  now — and the read is gone.
- The measurement is a test, and it holds the numbers. A plan change — #944,
  or anything else — has to change the measurement on purpose.
- The broad scan still exists for a rule that says only what a record must not
  carry. It is rare, and it is the honest answer for that shape: the index
  has no `NOT`, and a rule that only subtracts has to read what it subtracts
  from.
- The corpus is stated in the test, with the file each query comes from. A
  corpus without a source is a list of strings.

## Links

- ADR 2026-10-22: the index answers the positive terms — `docs/decisions/2026-10-22-the-index-answers-the-positive-terms.md`
- ADR 2026-10-22: the index says where the word is — `docs/decisions/2026-10-22-the-index-says-where-the-word-is.md`
- The measurement — `shared/src/commonTest/kotlin/com/singularity/todo/feature/search/query/SearchPlanShareTest.kt`
- The grammar of the free text — `docs/specs/search-query-grammar.md`
- Issue — [#944](https://github.com/gazon1/sing/issues/944)
