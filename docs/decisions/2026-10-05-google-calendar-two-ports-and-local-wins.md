---
date: 2026-10-05
slug: google-calendar-two-ports-and-local-wins
status: accepted
---

# Google Calendar sync: two ports, and why the overlap resolves to the app

## Context

`feature/calendar_sync` exists and is wired: it pushes a task to the Android system
calendar from the Settings tab, and `CalendarSyncOrchestrator.start()` is called from
`SingularityApp.kt:127`. The plan was to extend it into a two-way sync with Google
Calendar, where foreign Google events import as editable tasks and edits on either side
converge (a real 3-way merge, not last-write-wins).

Two things about the existing code forced decisions.

**The one-way design is explicit, not incidental.** `SyncDiffMerge.kt:11` says
*"One-way merge: Task → system calendar only. No reverse sync."* and
`CalendarSyncWorker.kt:33-34` repeats it. Nothing is half-built.

**`CalendarProviderPort` is shaped for a device calendar, and Google does not fit it:**

| Port assumption | Google reality |
|---|---|
| `insertEvent → Result<Long>` | opaque ~1kbit base64 strings |
| `updateEvent` replaces the whole event | `patch` vs `put`; partial updates are the norm |
| no revision concept | `etag` + `If-Match` |
| no cancellation | `status: cancelled` — distinct from deletion |
| `queryEvents → Map<taskId, Long>` | keyed by eventId; taskId only exists in our own `extendedProperties` |
| one calendar, no cursor | per-calendar `nextSyncToken`, invalidated with 410 |

That last row is the tell. `queryEvents` presumes the calendar is a *projection of our
tasks*. Making it two-way means deliberately breaking that assumption — Google becomes a
peer — so the existing read model cannot be stretched to cover it.

## Decision

**Two ports, not one generalised port.** The device-calendar path keeps
`CalendarProviderPort` unchanged. Google gets a separate, Google-shaped port.

**The whole feature is additive.** Because nothing existing is refactored, `event_id`
stays `INTEGER`, the `calendar_sync_task_map` primary key stays `task_id`, and no
migration touches existing user data. The highest-risk item in the original plan — a
`INTEGER → TEXT` column type change with real rows behind it — is off the table entirely.

**When both sides change the same field, the app's value wins**, reported with a
one-line "Updated from Google" note. The repeat rule is the sole exception: there, the
push is held back and the task is annotated.

**New tables are keyed on `user_id`.** `calendar_sync_state`, `google_event_shadow` and
`calendar_import_event` all include it in the primary key, because they are keyed by
*remote* identifiers that are not globally unique. The existing ULID-keyed tables are
left alone — see below.

## Rationale

**Why not one port.** Extending `CalendarProviderPort` to carry string ids, etags,
cursors and cancellation would have made it a lowest-common-denominator abstraction: the
device provider would gain parameters it cannot honour, and every new provider would need
to opt out of most of the interface. Two honest ports cost one extra file and keep both
call sites readable. The device-calendar code is also *not* modified, so the new feature
cannot break working behaviour — a property worth more than the refactoring saved.

**Why local-wins rather than asking.** The competing edit is almost always the same person,
minutes later, on a second device. A dialog for "I renamed this on my laptop" is noise at
the moment the user is trying to get work done. A 3-way merge earns its keep in the
*disjoint* case — rename here, reschedule there, both survive — which is handled with no
user input. There is no meaningful union of two intents for a task title, so the overlap
case has no better answer than "pick one, and say which".

**Why the repeat rule is different.** A series regenerated locally can change how many
*occurrences* exist. Pushing that over a concurrent Google-side series change silently
adds or removes events from the user's calendar — invisible, and not something a "we kept
your version" note makes acceptable. The repo has no RFC 5545 implementation
(`RecurrenceRuleMapper` parses the app's own reminder vocabulary, and `RruleGenerator`
emits three of the eight standard fields), so Google keeps ownership of recurrence and
its rule string is stored and re-sent byte-for-byte.

**Why existing primary keys stay as they are.** `IdGen.kt:13` generates ULIDs — 80 bits
of randomness — so two profiles cannot collide on one. `calendar_sync_task_map` looks like
the exception, but its `user_id` filter is a query-discipline problem, not a key problem,
and the DAO already carries a `deleteStale` guard from a previous bug. The rule that
generalises: **global namespace for our own ids, composite for remote ones.**

## Consequences

- Two ports to implement and document, and no shared provider code. Accepted.
- `queryEvents` on the device port has no production caller and is dead under this
  design; it is a cleanup item, not a new feature.
- The `notes.task_id → tasks.id` foreign key remains the only non-user-scoped FK in the
  schema. Out of scope here, but it is the same class of problem and should be tracked.
- The repeat-rule exception is the one path that can leave a task needing attention, so it
  needs its own test — a rule carrying `INTERVAL`, `COUNT`, `UNTIL` and an exception list
  must round-trip byte-identical.
- Importing foreign events means a first sync can pull a large history; it needs a bounded
  window, progress, and a way to decline.

## Links

- `feature/calendar_sync/domain/logic/BidirectionalMerge.kt` — the merge.
- `core/id/IdGen.kt` — why existing keys are safe.
- `feature/calendar_sync/domain/logic/RecurrenceRuleMapper.kt` — why Google's rule is
  stored verbatim.
- Investigation recorded in the plan: the local-hash dedup gate that made two-way sync
  impossible, and the class-of-bug sweep that came with it.
