# Tasks — a-pull-outcome-is-not-always-success

Issues: #175, #177. Spec: `offline-sync` REQ-OS-020 … REQ-OS-023.

## REQ-OS-020 — a payload-less change is skipped, not applied

- [x] `shared/` Add `Skipped` and `Failed` to the apply outcome, and state for each whether
      a retry could help — that is what decides the position. **Test:** an event with no
      payload yields `Skipped`; one that is not a document yields `Skipped` too.
- [x] `shared/` Return `Skipped` from the apply path when the payload is absent or is not
      a document. **Test:** both cases, at the handler.
- [x] `shared/` Step the position past a skipped change and count it as dropped.
      **Test:** the changes after it are applied, and the position reaches the end of the
      page.
- [x] `shared/` Give the event builder a `data` field. It had none, so every event in the
      suite was payload-less — the one shape no real handler can apply. **Test:** the
      shared task-event fixture now carries a document.

## REQ-OS-021 — a delete that did not happen

- [x] `shared/` Return `Failed` when a delete's repository call fails, instead of
      `Applied`. **Test:** a failing delete leaves the position before it and the cycle
      reports the stall.

## REQ-OS-022 — a stopped cycle is a failure

- [x] `shared/` Track why the pull loop broke and report the cycle as a failure with a
      code, rather than falling through to the success path. **Test:** a stalled pull
      reports `sync.pull_stalled` and the engine is not `Idle`.
- [x] `shared/` Name the position the device is stuck at in the message. **Test:** the
      message contains the lsn.
- [x] `shared/` Keep the stored position at the last change actually applied.
      **Test:** unchanged from before, still asserted.

## REQ-OS-023 — a type this device uploads is a type it can apply

- [x] `shared/` Stop seeding time entries, and drop the now-unused repository
      dependency. **Test:** the seed's output contains no time entry.
- [x] `shared/` Declare the seeded types once, beside the repositories that produce them.
      **Test:** the set is compared against the registered handlers.
- [x] `shared/` Assert both directions, so the failure names which side is wrong: the
      handler table covers the seeded types, and no seeded type lacks a handler.
- [x] `shared/` Keep the test that pins *why* time entries have no handler, and point it
      at the same change so the two move together.

## Supporting: the clock

Not a requirement of this change, but the two sync tests above needed it and so did the
backoff ones that were unwritable before.

- [x] `shared/` Inject a clock into the sync engine and the preference store, replacing
      eight `System.currentTimeMillis()` reads. Required rather than defaulted, so a new
      call site has to decide instead of inheriting the wall clock. **Test:** every sync
      test constructs the engine with an explicit clock.
- [x] `shared/test/helpers` Add a movable clock, not a fixed one: the backoff is measured
      in wall-clock hours and stored in the database, so a test has to move wall time
      rather than virtual time. **Test:** a deferral is ineligible before its time and
      eligible after it, with no waiting.
- [x] `shared/` Pin that an attempt count persisted by an earlier run is read back rather
      than restarted. **Test:** a row seeded with prior attempts dead-letters on the next
      failure instead of needing the full budget again in this process.

## Verification

- [x] `shared/` `./gw :shared:jvmTest` — 1955 tests, 0 failed, 0 skipped.
- [x] `shared/` `./gw detekt`.
- [x] `openspec validate --all --strict`.
- [x] Both fixes reverted in place to confirm a test fails without them: the
      `Applied`-for-no-payload revert fails three tests, and re-adding the time-entry
      type to the seeded set fails two.
