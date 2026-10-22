# task-series-invariants — design

## The shape of the change

The rules live in one object, `TaskSeriesInvariants`, and the repository decides
when to call it. That split is the point: the object says what a series is, and
the repository says which write heals it.

```
TaskSeriesInvariants          (pure, no I/O)
  sharedFieldsOf(task)          the values that must agree
  divergedFields(members)       the fields the members disagree on
  alignedTo(members, source)    the members with the source's shared fields
  membersToRealign(members, src) the members that have to be written
  isOneSeries(tasks)            whether a batch is one series
  seriesIdOf(tasks)             the one series id of a batch

TaskRepositoryImpl             (decides when)
  update      → write the row → realignSeries
  setTags     → write the tags → realignSeries
  upsert      → write the row → realignSeries   (the pull path)
  createSeries → refuse a batch that is not one series → align → write
  updateAll   → refuse a batch that is not one series → align → write
```

## Why the enumeration is the rule

`TaskSharedField` already lists the fields that describe the work. The
invariants read the values off a task *through* that enumeration, so a field
that is added to the enumeration is covered by the realignment by construction.
A second list of field names — in the invariants, in the realignment, in the
test — is a second thing to keep in step, and it is the kind of thing that
drifts without anyone noticing.

The realignment is expressed as "take the value of every shared field from the
source", not as "copy these nine columns", because the first cannot fall behind
the second.

## Why the write path heals and the batch path refuses

The two situations are not the same.

A single-row write is usually right: the user renamed the task, the tool was
asked to rename the task, and the other rows are stale by definition. Refusing
would make the ordinary case an error, and the user would have to know which
occurrence to open for the edit to stick.

A batch write is not usually right to refuse — but a batch that carries two
series ids is not a stale row, it is two stories, and no rule can say which one
the user meant. The write path cannot repair that, so it does not write it.

## Why the pull path realigns to the incoming row

A remote row that disagrees with the local series is not a mistake: it is the
other device being right about a change this device has not seen yet. Refusing
would break the device forever — the row would come back on every pull, and the
two devices would never agree.

So the rule is the one the rest of the sync already follows: the last write
wins. Here it is applied to the whole series rather than to the row, because the
series is the thing the user thinks in.

The owner is the user the row belongs to. A row of another profile is not this
profile's series to touch, so the realignment is scoped by the row's own
`userId`, not by the current profile.

## Why the detach and the merge are separate operations

"Take this date out" and "put that work in here" are different questions, asked
at different moments, and the second has a direction the first does not.

The merge keeps the id of the series the user is looking at: that series's title
is the one they read, and its id is the one their other devices know. The other
series takes the id and the fields. The alternative — merging into whichever
series happens to come first — would make the result depend on the sort order,
which is not a rule a user can hold in their head.

A detach that empties a series leaves the last member standing alone: a series
of one is a task with a claim attached, and the claim would be false.

## What the screens do

- The task detail's series card gains "Оставить отдельно" — one button, one
  intent, one use case call.
- The agenda's selection panel gains a join button, shown when the selection
  covers exactly two series. The selection already knows which rows the user
  picked; no new query, no new dialog.

Both are wired through the existing intent → slot → repository path, so the
rules stay in the use cases.

## Testing

- `TaskSeriesInvariantsTest` — the pure rules: the values that must agree, the
  fields the members disagree on, the alignment, the batch checks.
- `DetachTaskFromSeriesUseCaseTest` — the detach: the row keeps its own
  fields, loses the series id, and a series of one becomes a task that stands
  alone.
- `MergeTaskSeriesUseCaseTest` — the merge: the id and the fields of the
  series the user is looking at survive, the other series' rows take them, and
  the dates of both survive.
- The repository contract test — the write path realigns, and the pull path
  realigns to the incoming row.
