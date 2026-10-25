# The proposal reads strictly — Tasks

## 1. The format is told by its shape

- [x] `decodeKind` decides by the shape: a blob with a structure is a sealed
      blob, a blob without is a flat one.
- [x] The sealed reader is strict: the `Json` instance no longer ignores
      unknown keys.
- [x] A sealed blob is never read as a flat one.
- [x] `Unknown` carries the reason it cannot be read, and the reader names it.
- [x] The written format did not change.

## 2. The change says what it is about

- [x] `ProposalTarget` with the five groups and the unreadable one.
- [x] The group interfaces, sealed, implemented by the kinds.
- [x] Every kind declares its target; the target carries the kind.
- [x] The planner has one function per group, each exhaustive.
- [x] The use case routes by the target; the five predicates are gone.
- [x] The names a change carries are part of the change (`tagNames`).

## 3. The decisions keep the reasons

- [x] `ProposalDecisions` owns the store and exposes the reasons.
- [x] The three surfaces subscribe instead of handing one in.

## 4. The names, and the check on them

- [x] `ProposedRowId` — the identity of a row has a name of its own.
- [x] The test: every field name a value declares is a name the reader knows,
      and the reader reads it as that very field.

## 5. The test names the kind of ViewModel

- [x] The agenda's tests use the harness with `VmScope.Background`.
- [x] The exemption list is empty; the gate that keeps it empty still runs.
- [x] The stale exemption ADR is marked superseded.

## 6. The flat reader's removal

- [x] The checklist, the trigger and the order are in the ADR.

## 7. The checks

- [x] `:shared:compileTestKotlinJvm`.
- [x] `:shared:jvmTest` — the whole set.
- [x] `just lint-changed`.
- [x] `scripts/ci/static-gates.sh`.
- [x] The Python suite.
