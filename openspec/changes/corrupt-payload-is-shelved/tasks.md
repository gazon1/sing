# Tasks — corrupt-payload-is-shelved

Spec: `offline-sync` REQ-OS-032. Issue: #1422.

## The classification

- [x] `shared/` Say in the outcome that a payload was present but unreadable, without
      adding an arm: the three arms answer the cursor question, and the corrupt flag
      answers a different one. **Test:** `ApplyOutcomeArmsTest` pins the arms at three
      and pins the skipped arm's shape.
- [x] `shared/` Classify a payload that is not a document, and a payload that does not
      decode, as corrupt. **Test:** `PullEventHandlerTest` — both are corrupt; an
      absent payload, a newer protocol version and a legacy document are not.

## The record

- [x] `shared/` Write the event to the dead-letter store when it is corrupt, with the
      payload as it arrived, the reason the read failed, and the account that received
      it. **Test:** `PullPhaseTest` — the row carries the payload, the reason and the
      owner.
- [x] `shared/` Give the row a key that says it came in and cannot be retried, so it is
      told apart from a shelved outbound patch. **Test:** the same event delivered twice
      is one row, not two.
- [x] `shared/` Keep the download position advancing past a corrupt event. **Test:**
      `PullPhaseTest` — the position advances, and the event is counted as dropped.
- [x] `shared/` Keep a failure to write the row from failing the pull. **Test:**
      `PullPhaseTest` — `a shelf that cannot be written does not fail the pull` pins
      `PhaseResult.Ok`, `dropped = 1` and `lastLsn = 10` against a fake whose
      `insertInboundIfAbsent` throws. That is REQ-OS-032's *a record that cannot be
      written*: a storage error on the shelf must not stop the account synchronising.
- [x] `shared/` Count a redelivery instead of only ignoring it. **Test:**
      `PullPhaseTest` — `the same corrupt event delivered again is one row, not two` pins
      one row **and** `attempts == 2`, with the first sighting's `createdAt` / `failedAt`
      left where they were. One row alone is what a plain IGNORE gives, so without the
      count and the timestamps that test would not notice
      `bumpInboundDelivery` never being reached.
- [x] `shared/` Hand the shelf to the pull phase the application assembles. The shelf is
      a **required** argument of the pull phase — no default, no stand-in — so every
      construction site says which shelf it writes to, and one that leaves it out is a
      compile error rather than a quiet loss of the record. The DI module builds the
      phase by hand and hands over the real DAO; the engine's own phase hands over the
      one the engine was given.
      **Test:** the requirement is the type — a construction that omits the shelf does
      not compile. The behaviour it enables is covered by `PullPhaseTest`.

## The record of the decision

- [x] `docs/decisions/` Write the ADR: why the question is not a choice between skipping
      and dead-lettering, which payloads count as corrupt, and why the shelf is reused
      rather than a new table added.
- [x] `openspec/` Add the requirement delta for `offline-sync`.

## Verification

- [ ] `shared/` `./gw :shared:jvmTest` — **not run**: this run's sandbox refuses Gradle
      ("this run does not compile"), so CI carries it. What was done here instead was
      read: every construction site of the pull phase was audited by hand, and the
      path that has to hold — `PullEventHandler` returning `Skipped(corrupt = true)`,
      the pull phase's `Corrupt` arm writing the row — was read against the tests that
      assert it.
- [ ] `shared/` `./gw detekt` — **not run**, same reason.
- [x] `openspec validate --all --json --strict` — ran, exit 0: 54 changes and 39 specs
      valid, none failed.
