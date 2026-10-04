# unwired-detector-test-double-exemption

Issue: #97 · Backlog entry: `test-doubles-in-commonmain-source`

## What

Teach detector 7 of `scripts/find-unwired-surfaces.py` not to report a test
double, and delete the three baseline lines the exemption currently needs.

No production behaviour changes. This is verification posture only.

## Why

`MapFileSystem`, `FakeSecureStorage` and `FakeDraftStore` live in
`shared/src/commonMain/.../test/fakes/`, so `commonTest` can reach them without
depending on a JVM- or Android-only source set. They are reachable, used by
hundreds of tests, and not dead.

The gate cannot tell that apart from dead code. It reports "referenced by tests,
referenced by nothing in production", and for these three the second half is a
property of where they live rather than a property of whether anything uses
them. Each therefore needs a line in `scripts/find-unwired-surfaces-baseline.txt`
carrying a live backlog reference — which is the arrangement that exists today,
and which is a permanent exemption bought by keeping a backlog entry alive
purely so the baseline line is not `BacklogRef: none`.

The shape is the one this repository already rejects elsewhere: an accepted
false positive that costs a maintenance ritual. The right fix is to make the
detector's notion of "production reference" match the language's, not to keep
the exception.

The fakes cannot simply move. `commonTest` cannot see `jvmTest` sources, so
moving them to a test-only source set breaks compilation of every test that uses
them. That is why the fix belongs in the detector.

## How

Detector 7 should treat a symbol as production-referenced when it is declared
under a `test/fakes/` path or matches the project's fake naming
(`Fake*`, `InMemory*`, `*Fake`). Both signals are already the convention —
`test/fakes/FakeRepositories.kt` is the single home for doubles — so the
exemption is a statement about a rule the codebase already follows, not a
blanket skip.

The exemption must be explicit about its blast radius. A file named `Fake*` in
`commonMain` that is *not* a test double is possible, so the rule should be
narrowed to the fakes directory first, and the name-based signal should be
reported separately so a future reviewer can see what it lets through.

**Invariant:** a change to this detector needs a positive control, like every
other gate in this repository. A test that constructs a genuine unwired surface
and asserts the detector reports it — otherwise "detector finds nothing" and
"detector looks in the wrong place" are the same result.
