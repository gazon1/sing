# Tasks

## The requirement

- [x] `PatchResult.lost` — the field the server sends (`ok: true, lost: true`) and the
      client dropped at the parse boundary. `StableJson` sets
      `ignoreUnknownKeys = true`, so it arrived and vanished without a word.
- [x] Count it distinctly. `PushSummary.lost` is a fourth number, not a `succeeded` and
      not a `failed`: the server did not refuse the patch, and it did not land.
- [x] Stop recording the patch as delivered. The outbox row is dropped — re-sending an
      identical patch would lose identically — and the branch is ordered `ok && lost`,
      because `ok` alone is exactly the misreading the requirement exists to prevent.
- [x] Return the row to the state the server holds, read from the shadow's own
      `confirmed_json`. Not from `PatchResult.serverState`: the server never populates
      it, which is why the decision as first worded was not executable.
- [x] Release the in-flight marker **after** the revert succeeds. The other order is a
      silent infinite loop: the marker drops, the row still holds the losing edit, the
      next diff regenerates it, and it loses again.
- [x] On a failed revert, keep the marker. The queued edit stays owned by the shadow so
      nothing regenerates it, and the loss is logged rather than resolved into a state
      neither side holds.

## The refactor it required

Routing a shadow document through the per-type apply path meant that path was not
reachable: "put this document in the right table" was a lambda closed over six
repositories inside `SyncBootstrapper.registerHandlers`.

- [x] `SyncDocumentWriter` — one statement of which repository stores which `DocType`,
      with the delete half beside the upsert half.
- [x] `SyncBootstrapper` registers from `writer.supportedTypes` rather than listing six
      types itself, so the two cannot disagree.
- [x] `SyncedEntityDeleteIsSoftTest` follows the dispatch table to its new file. This is
      the only thing standing between that refactor and a hard delete on every receiving
      device.

## Verified by mutation, not by inspection

- [x] Ignoring `lost` (the pre-fix branch) fails 5 of 7 tests. The two that still pass
      are the wire-level decode and the won-race case — neither should be affected, which
      is the point of them being separate.
- [x] Releasing the marker unconditionally fails exactly the revert-failure test.

## Not done, and why

- [ ] **The user is not warned.** The sync layer reports the loss and the summary can
      count it; the surface that shows it is a separate decision that the proposal
      deliberately left open. Silence in the UI is the same defect in miniature, so this
      is recorded rather than forgotten.
- [ ] **A revert that fails leaves the edit un-sent.** Held by the in-flight marker and
      logged. Better than resolving it into a state neither the server nor the device
      holds, but not a repair.