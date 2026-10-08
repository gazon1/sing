# a-cycle-drains-the-feed-or-says-it-could-not

**Status:** proposed · **Issue:** #176

## What

A download cycle reads pages until the feed says it has no more, rather than issuing one
request and treating the answer as the whole feed. Requirements REQ-OS-015, REQ-OS-016.

## Why

There was one `getEventsSince` per cycle and no loop. An account with 250 pending
changes received 50 of them, reported a successful sync next to a current "last synced"
time, and left 200 behind for a cycle that would only run if something else triggered it.

The client cannot tell a truncated page from a complete one — a server-side `max-rows`
cap is invisible from here. What it can do is ask again when a page comes back exactly
full, and treat the empty answer as the proof of the end. That is weaker than a
server-side `hasMore`, and it says so: a full page is logged as "the feed may hold more
and a client-only loop cannot tell", not as "there is more".

## The part that needed care

A loop is not only a loop. Three things had to be true at once, and the third is the one
a naive version gets wrong:

- **The read position and the stored position are different things.** The next request
  resumes after the last change *read*; the stored position is the last change *dealt
  with*. If they were one value, a page that was fetched but not fully applied would
  either be skipped or fetched twice. They are separate, and the second is what survives
  into the database.
- **A feed that will not advance must end the cycle.** A server that ignores the
  position answers with the same page forever, and the loop never ends — which in a sync
  cycle is a hang the user sees as a busy app rather than as a cycle that made no
  progress. The guard compares the highest position in the page with the one asked from,
  and stops when there is none.
- **The empty last page is a real cost.** An account with nothing to do must not pay for
  a second round trip forever, so a short page ends the cycle without asking. The
  accounts that do pay one extra request are the ones that had a page exactly full, which
  is the case where not knowing is the honest answer.

## Scope

**In scope:** the loop, the two positions, the no-progress guard, the truncation note,
and the four tests.

**Out of scope, deliberately:**

- **A server-side `hasMore`.** Better in every way — one request instead of two, and the
  distinction becomes a fact rather than an inference. It is a protocol change and a
  coordinated one, so the client does what it can alone and the spec does not pretend the
  result is exact.
- **The cursor that overshoots the feed head.** A position left beyond the feed by a
  rollback, an empty response or a retention window idles silently forever, and no
  full-resync path exists. Pagination makes it easier to reach and does not fix it.
- **A `bigserial` lsn read under concurrent transactions.** An event whose transaction
  commits late can be skipped by a cursor that has already passed its number. The test
  plan raises it as SQ-21; it is a server-side concern and not one this change can
  reach.
- **Applying a page atomically.** A page is still applied row by row. See the record on
  who owns a row and the patch that describes it — the same question, on the read side.
