# Tasks — projects-flow-test-flake

**Status:** proposed

## Reproduce deliberately — do not wait for it

- [ ] Run the class repeatedly against a **warm** daemon
- [ ] Run it once against a **cold** daemon

  This tests the daemon-sharing theory directly. It was recorded as a suspicion
  and never actually measured.

## If it does not reproduce, read before you measure

- [ ] Inspect the projects ViewModel for the ordering shape the flake already
      names: a property read from `init` before the collector that seeds it.

  This is the cheaper explanation and it is already written down. Timing analysis
  is expensive to do speculatively.

- [ ] **Add the missing companion graph test** for the projects screen, mirroring
      `TaskDetailCoordinatorGraphTest`. Do this **even without a reproduction** —
      it converts a silent hazard into a checked one, which is the actual
      deliverable here.

## Only if the ordering theory is wrong

- [ ] Capture per-test timing with `--scan` before touching anything

## Explicitly not doing

- Rewriting the test — it is correct and caught a real hazard
- Changing shared daemon configuration
- Speeding up the suite
