# Tasks — bulk-import-port

## Phase 0 — verify before building

- [ ] **Confirm restore is currently all-or-nothing.** REQ-BI-002 is a
      strengthening, not a description. If restore today can already fail
      partway, that is a **separate defect** and this change must not absorb it —
      record it as its own issue first, or Phase 1 will be blamed for it.

## Phase 1 — the boundary

- [ ] **shared/**: declare the bulk import boundary, taking an explicit target
      owner. No behavior change yet — the importer does not use it.
      Test: a test asserting the boundary is constructible and takes an explicit
      owner; a test asserting it cannot be constructed without one.
- [ ] **shared/**: add owner-scoped repository variants for the import path, or
      confirm the existing allowlisted ones suffice.
      Test: a test per data set, importing for a non-active owner and asserting
      every row carries that owner.

## Phase 2 — route the importer

- [ ] **shared/**: move the importer's writes onto the boundary.
      Test: the full round-trip test, asserting no write occurs outside the
      boundary.
- [ ] **shared/**: make the import all-or-nothing.
      Test: **the test that matters** — force a failure partway and assert the
      pre-import state is byte-identical. A round-trip success test passes
      whether or not this holds, which is why the failure test is listed
      separately rather than folded in.

## Phase 3 — the registry

- [ ] **shared/**: the sanctioned-bypass registry, with a stated reason per
      entry.
      Test: a test pinning that the count has not grown, that every entry still
      exists, and that each carries a reason.
- [ ] **shared/**: a check that fails when a new bulk path appears outside the
      boundary.
      Test: **verify the teeth.** Add a temporary bulk path, confirm the check
      goes red, remove it. A check that has never been observed failing is not a
      check. See `docs/decisions/2026-10-04-make-the-rules-not-the-sweeps.md`.

## Phase 4 — documentation

- [ ] **docs/**: record that the boundary is a registry and not a detector, in
      the same terms the existing registry uses. A future reader who believes it
      catches cross-owner writes will not add the declaration they are supposed
      to add.

---

**Verification command:**

```bash
./gradlew :shared:jvmTest --tests "*BulkImport*" --tests "*Backup*"
```

**Note:** the teeth check in Phase 3 is not optional bookkeeping. The failure
mode this whole project keeps meeting is a gate that has never been seen to fail
and therefore cannot be distinguished from a gate that does nothing.
