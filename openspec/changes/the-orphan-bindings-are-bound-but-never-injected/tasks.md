# Tasks — the-orphan-bindings-are-bound-but-never-injected

**status:** deferred

---

## Phase 1 — The record (done)

- [x] The three orphan bindings are named: `AuthGateway` and
      `DeviceIdProvider` (`core/di/CoreDiModule.kt`), `GenuiTransport`
      (`feature/genui/di/GenuiDiModule.kt`)
- [x] Each one carries the question that has to be answered before it can be
      closed

## Phase 2 — The decisions (in progress)

- [x] `GenuiTransport`: the seam went. `the-turn-is-one-model-call` deleted it
      with its interface — it was the last place in the app that could make a
      second model call, and the turn asks the model now. Deleted, not given a
      consumer.
- [ ] `AuthGateway`: is authentication a thing the app still has, and who
      consumes the gateway?
- [ ] `DeviceIdProvider`: who needs the device id — sync, the backup format,
      or neither?

## Phase 3 — The closures (in progress)

- [ ] Each remaining decision removes the binding, and the interface with it
      when the decision was "not wanted"
- [ ] Each decision that keeps a seam names the consumer that wants it
- [x] The detector's output lost one orphan: `GenuiTransport` is gone, and the
      interface went with it
- [ ] The detector's output goes back to zero orphan bindings — and stays
      there because the interfaces went with them
