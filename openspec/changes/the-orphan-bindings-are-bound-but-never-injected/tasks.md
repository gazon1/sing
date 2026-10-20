# Tasks — the-orphan-bindings-are-bound-but-never-injected

**status:** deferred

---

## Phase 1 — The record (done)

- [x] The three orphan bindings are named: `AuthGateway` and
      `DeviceIdProvider` (`core/di/CoreDiModule.kt`), `GenuiTransport`
      (`feature/genui/di/GenuiDiModule.kt`)
- [x] Each one carries the question that has to be answered before it can be
      closed

## Phase 2 — The decisions (not started)

- [ ] `AuthGateway`: is authentication a thing the app still has, and who
      consumes the gateway?
- [ ] `DeviceIdProvider`: who needs the device id — sync, the backup format,
      or neither?
- [ ] `GenuiTransport`: does the agent/app seam get a consumer, or does it go?

## Phase 3 — The closures (not started)

- [ ] Each decision removes the binding, and the interface with it when the
      decision was "not wanted"
- [ ] Each decision that keeps a seam names the consumer that wants it
- [ ] The detector's output goes back to zero orphan bindings — and stays
      there because the interfaces went with them
