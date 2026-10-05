# Tasks — local-storage-failure-classification

Issues: #161, #165. ADR: `2026-10-05-sync-storage-failure-is-not-a-server-refusal`.
Spec: `core/local-storage-failures` (added by this change).

The sync cycle already satisfies every requirement below; the work is the settings layer
and the last unclassified write path. Each task names the test that verifies it, as the
config requires.

## REQ-LSF-001 — classification

- [ ] `shared/` Wrap the change-queueing path so a failure to read local state or write
      the queue is reported as a local-storage failure. **Test:** a queueing failure reports
      the storage classification, and a remote refusal on the same path does not.
- [ ] `shared/` Decide, per preference wrapper, whether a storage failure is absorbed or
      propagated, and record the decision. This is #165 and it blocks nothing else here —
      a propagated failure must still be *classified*, which REQ-LSF-001 covers either way.
- [ ] `shared/` Test: the two wrappers that do not classify today report a storage failure
      as classified, whichever way the previous task decided they propagate it.

## REQ-LSF-002 — the wording

- [ ] `shared/` Every classification names the operation it was performing, in the same
      voice as the sync cycle already uses.
- [ ] `shared/` Test: the message names the operation, and does not contain the storage
      driver's own text.

## REQ-LSF-003 — the terminal reported state

- [ ] `shared/` Confirm by reading the code, not by grep, that every exit from every local
      read and write in the settings and sync layers lands the reported state in a terminal
      condition. Record which paths were checked.
- [ ] `shared/` Test: after a failure on each path, the reported state does not read as
      running, and a later cycle is permitted to start.

## REQ-LSF-004 — the cycle that did not start

- [ ] `shared/` Already implemented for the sync cycle; verify the requirement holds for a
      failure that happens *before* the cycle enters either direction, not only inside one.
- [ ] `androidApp/` Test: the background worker retries a cycle that could not start, and
      does so without the attempt cap spinning.

## REQ-LSF-005 — the cause survives

- [ ] `shared/` Confirm every path that wraps a throwable retains it. The sync cycle does;
      the settings layer routes through the shared helper, so this is a check rather than
      a change.
- [ ] `shared/` Test: the underlying throwable is reachable from the report, and its own
      description is preserved.

## REQ-LSF-006 — nothing is discarded to fit

- [ ] `shared/` Test: a cancellation is not reported as a storage failure and is not
      swallowed.
- [ ] `shared/` Test: a non-storage, non-remote failure is reported as unclassified rather
      than folded into the storage classification.

## Housekeeping

- [ ] Add `core/local-storage-failures` to **Covered** in `openspec/specs/MODULE-INDEX.md`,
      in this change. Do not add rows for the modules it touches: the capability is about
      an invariant that crosses them, and the index's own rule is that a module row appears
      when a spec for that module is authored.
- [ ] `shared/` Do **not** close #165 from this change. Its product decision is unowned, and
      closing it would record a resolution nobody made.
