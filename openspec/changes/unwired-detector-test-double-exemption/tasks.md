# Tasks — unwired-detector-test-double-exemption

- [ ] Record the three symbols and the baseline lines this change retires
      (`MapFileSystem`, `FakeSecureStorage`, `FakeDraftStore`) in the proposal,
      so the delta is measurable rather than "fewer findings".
- [ ] Extend detector 7 in `scripts/find-unwired-surfaces.py` to treat a symbol
      declared under `test/fakes/` as production-referenced. Start with the
      directory signal alone; it covers all three cases with no naming guesswork.
- [ ] Add the positive control *before* the exemption: a test that declares a
      genuine unwired `@Composable` in a temp tree and asserts the detector still
      reports it. Without this the gate can pass by scanning nothing — the same
      failure mode `check-test-runs.py` was written to catch.
- [ ] Add the negative control: a `Fake*` double under `test/fakes/` is no longer
      reported, and the test fails if it is.
- [ ] Delete the three lines from `scripts/find-unwired-surfaces-baseline.txt`
      via `--update-baseline`, after confirming the count dropped by exactly 3
      and no other line moved.
- [ ] Assert the gate still fails when a baseline line is removed by hand, so the
      "accepted debt" path is not silently reopened.
- [ ] Close the backlog entry `test-doubles-in-commonmain-source` in place,
      recording that the exemption became unnecessary, and reference this change
      from the entry's `Tracked as` issue (#97).
- [ ] If the name-based signal (`Fake*` / `InMemory*` outside `test/fakes/`) is
      added, report those matches separately from real findings so a reviewer can
      see what the extra signal lets through. Deferring it is fine; hiding it is
      not.
