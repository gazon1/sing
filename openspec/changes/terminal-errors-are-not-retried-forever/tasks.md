# Tasks — terminal-errors-are-not-retried-forever

Issue: #180. Spec: `offline-sync` REQ-OS-024.

- [x] `shared/` Name the whole server error vocabulary as constants, so the classifier,
      the tests and the SQL that produces the codes cannot drift on a spelling.
      **Test:** the classification table is written in wire strings and compared against
      the named set.
- [x] `shared/` Make `not_found` and `too_large` terminal alongside the two that
      already were. **Test:** both are in the table as not retriable.
- [x] `shared/` Add `field_not_writable` and `row_unavailable` — the two refusals
      `sync_batch_apply` states deliberately. **Test:** both are in the table.
- [x] `shared/` Replace the two-example test with a table over every code, and keep an
      unrecognised code on the retried side to pin that default deliberately.
- [x] `shared/` State in the classifier why an unknown code is retried rather than
      dropped, so the default reads as a choice.
- [x] `shared/` Guard the terminal set against gaining a member for the wrong reason.
      **Test:** a test asserts the exact set, so a member added without the argument is
      visible in review.

## Verification

- [x] `shared/` `./gw :shared:jvmTest`.
- [x] `shared/` `./gw detekt`.
- [x] `openspec validate --all --strict`.
