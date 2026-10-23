---
title: "The series says one thing about the work, and the write path keeps it that way"
date: 2026-10-22
status: accepted
tags: [tasks, series, sync, database]
---

# The series says one thing about the work, and the write path keeps it that way

## Context

A series is several task rows that share one id and stand for one piece of work
([2026-10-22-task-series-materialized-copies.md](2026-10-22-task-series-materialized-copies.md)).
The fields that describe *the work* — the title, the description, the project, the
tags, the priority, the accent, the emoji, the estimate, the recurrence — are
stored on every row. The fields that describe *an occurrence* — the date, the time
of day, the completion state, the pin — stay on their own row.

Nothing kept those two kinds apart in the storage. The editor writes a shared
field to the whole series, but the editor is not the only writer:

- an MCP tool or an AI tool renames one occurrence;
- a push from another device brings a row with a different title;
- a backup restore writes rows one by one.

Each of those writes one row. The series is then two things at once, and the next
person to look at it cannot tell which title is the work's. "Apply to all of them"
stops being a sentence about the series and becomes a sentence about whichever
row the editor happened to open.

There is a second question, the mirror of the first: when two writers write the
same series at the same time, which one wins? The local device and the remote
device both hold a series, and each writes one row of it. Without a rule, the
two devices keep disagreeing about the title of one piece of work forever — each
device's own row says its own title, and each device pushes that row back.

## Idea

Two rules, deliberately different, because the two situations are different.

1. **The write path heals.** A row that was written is the row that says what the
   work is; the other rows of the series are realigned to it. The series is
   repaired by the write, not by a hope that the next series edit comes along.
2. **The series write path refuses.** A batch that claims to be one series but
   carries two series ids is a mistake in the caller — and a mistake that is
   written is a series that cannot be described. There is no way to know which
   of the two stories was meant, so neither is written.

And one rule for the third writer, the one that brings a row from another device:

3. **The last write wins, and the series follows the writer.** A row that arrives
   from the server has already been aligned by the device that wrote it — that
   device had the whole series in front of it. So the local series follows the
   incoming row, and the rows that changed are pushed back.

## Decision

**The shared fields are listed in one place, and the list is the rule.**

`TaskSharedField` enumerates them, and `TaskSeriesInvariants` reads the values
off a task through that enumeration. A field that is added to the enumeration is
covered by the realignment by construction; a second list of field names would
be a second thing to keep in step.

**A single-row write realigns the series.**

`TaskRepositoryImpl.update`, `setTags` and `upsert` call `realignSeries` after
the row is written: the other members of the series are written with the
source's shared fields, and the members that already agree are not written at
all. Changing an occurrence — a date, a completion — therefore does not rewrite
the whole series.

**A series write refuses a batch that is not one series.**

`createSeries` and `updateAll` refuse a batch that carries more than one series
id, and a batch whose members disagree about whether they are a series at all —
one carries the id, the other does not. A batch whose members carry no id at
all is a batch of tasks that stand alone, and it is written as it is: the draft
of a single date is exactly such a batch, and aligning it to its first member
would give the second task of the batch the first task's title — a different
work entirely. A batch that *is* one series is aligned to its first member
before it is written, so a batch that had drifted apart is repaired rather than
written as two stories.

**A row that arrives from another device realigns the local series to it.**

The pull path writes the incoming row and then realigns the local series to that
row's shared fields, so both devices end up saying the same thing about the
work. The series that is realigned is the one this device is on: a row of
another profile — the lost-race path writes the row's own shadow — is stored as
it is, because this device does not rewrite rows it does not own.

**The tags of the source are the ones the row carries, not the ones the caller
holds.**

The tags live in the cross-ref table, so a task that was read through `get()`
carries none of them. A repair that took the tags of the task it was handed
would read that absence as the truth about the work — and strip the tags off
every other member of the series. The repository therefore reads the source's
tags from the database, and the pull path writes the tags of the row that
arrives before it repairs the series, because a pull that wrote the row and
lost the tags would leave the series saying two things about the work.

**The rules live in the domain, not in the callers.**

`TaskSeriesInvariants` is a pure object: `sharedFieldsOf`, `divergedFields`,
`alignedTo`, `alignedIfOneSeries`, `membersToRealign`, `isOneSeries`,
`isOneSeriesOrStandalone`, `seriesIdOf`. The repository decides *when* to call
it; the object decides *what the series says*.

## Rationale

**Why the write path heals instead of refusing.** A refusal is the safer rule
and the worse one. The writer is usually right: the user renamed the task, the
tool was asked to rename the task, and the other rows are stale by definition.
Refusing would make the ordinary case — the user edits one occurrence of a
series — an error, and the user would have to know which occurrence to open to
make the edit stick. Healing costs one write per member that actually differs.

**Why the batch write refuses.** The batch writer knows more than the single-row
writer does: it has the whole series in front of it. Two series ids in one batch
is not a stale row, it is two stories, and no rule can say which one the user
meant. The write path cannot repair that, so it does not write it.

**Why the pull path realigns rather than refuses.** A remote row that disagrees
with the local series is not a mistake — it is the other device being right
about a change the local device has not seen yet. Refusing would break the
device forever: the row would keep coming back on every pull, and the two
devices would never agree. Last write wins is the rule every other sync in this
app already follows; here it is applied to the whole series rather than to the
row, because the series is the thing the user thinks in.

**Why the profile is not part of the rule.** The rule is about the *work*, and
the work belongs to a user. The realignment looks at the series of the user the
incoming row belongs to — which is the only series it can be a member of — and
it writes the rows of the profile this device is on. A row of another profile
is stored as it is: healing it would mean rewriting the rows of a profile this
device is not on, and a device that rewrites another account's work is worse
than a device that leaves it alone.

## Consequences

- The write of one row can write more than one row. Every write path that goes
  through the repository realigns, so the cost is bounded by the number of
  members that actually differ.
- The rules are testable without a database: `TaskSeriesInvariants` is pure, and
  its test covers the alignment, the divergence and the batch checks.
- The write path is covered where it actually runs: `TaskSeriesWritePathTest`
  drives `TaskRepositoryImpl` against a real database and checks that a
  renamed occurrence realigns the series, that the rows which already agree are
  not written again, that a member keeps its own newer timestamp, that an
  incoming row realigns the local series, that a foreign row touches nothing,
  and that a batch write carries the tags into the cross-ref table. The fake
  cannot cover these: it stores whole `Task` objects and has no rows to realign.
- A batch write that is refused fails loudly (`Result.failure`), instead of
  writing a series that nobody can describe.
- The realignment writes the rows that changed and enqueues them, so the change
  travels to the other devices instead of being made only locally.

## Links

- [2026-10-22-task-series-materialized-copies.md](2026-10-22-task-series-materialized-copies.md) —
  what a series is, and why it is rows rather than a table.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskSeriesInvariants.kt` —
  the rules, as pure functions.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/TaskRepositoryImpl.kt` —
  where each write path calls them.
