# Tasks — log-export-surface

**Status:** proposed · **Blocked on:** #log-messages-user-content-sweep

## Blocking dependency

- [ ] **Redaction inventory (separate change)** — decide, per log call site, whether
      user content is needed for diagnosis. A share surface ships whatever the log
      contains, so this is sequenced first on purpose.

## Decision

- [ ] **Choose the trigger surface** and write down the reasoning. Candidates:
      Settings → Developer row, an offer on the failure screen, or both. The
      failure-screen offer is the one that fires when the user is already
      frustrated; the settings row is the one that works when nothing crashed.

## Implementation

- [ ] **Export action** — hand the captured log to the platform share mechanism via
      the existing `SharePort` / `FileSharePort` ports. No new port required.
- [ ] **Redaction to sharing level** — per #log-messages-user-content-sweep.
- [ ] **Failure-bundle coexistence** — the developer bundle and the user share
      path read the same file. Verify cancelling a share leaves the bundle
      intact.
- [ ] **Android parity** — the write side already works on both platforms; verify
      the share path does too rather than assuming it.

## Verification

- [ ] `LogBundleExporterTest` extended to cover the share path, not just writing
- [ ] A shared log demonstrably contains no user-authored text
- [ ] Declining a share leaves the developer's diagnostics usable
- [ ] Desktop UI test: share action reachable and functional

## Explicitly not doing

- Log rotation or retention policy
- Bulk/shared log history (only the current session's log)
