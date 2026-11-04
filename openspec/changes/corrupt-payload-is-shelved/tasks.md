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
- [x] `shared/` Keep the download position advancing past a corrupt event, and keep a
      failure to write the row from failing the pull. **Test:** `PullPhaseTest` — the
      position advances, and the event is counted as dropped.

## The record of the decision

- [x] `docs/decisions/` Write the ADR: why the question is not a choice between skipping
      and dead-lettering, which payloads count as corrupt, and why the shelf is reused
      rather than a new table added.
- [x] `openspec/` Add the requirement delta for `offline-sync`.

## Verification

- [ ] `shared/` `./gw :shared:jvmTest`
- [ ] `shared/` `./gw detekt`
- [ ] `openspec validate --all --strict`
