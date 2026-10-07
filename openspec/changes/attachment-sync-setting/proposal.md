# attachment-sync-setting

## What

Store a per-account-and-profile preference for uploading attachment payloads, and show it
in the app as a control that says it is not ready yet.

## Why

Two findings, in the order they were hit.

**The sync settings screen did not exist.** The plan asked for a row "next to the existing
sync settings". `SyncViewModel` is bound in the Koin graph and never injected;
`SyncButton` has no call site at all; `SettingsTab` has no Sync entry. So the setting has
no home, and two finished artefacts are unreachable. Building the screen delivers the
setting and makes both reachable.

**There is no transport to switch on.** `DocType` carries six types, fixed by the Flutter
`sync_core` contract. The word "attachment" does not occur anywhere in the sync core.
`AttachmentUploadService` is a stub whose `upload()` returns a local path as if it were a
remote one. Metadata and bytes are different transports: JSON goes through `sync_apply_ops`,
bytes need storage that does not exist.

## How

- `sync_state.attachments_sync_enabled`, default `0`, via `AutoMigration(40 to 41)`.
- `SyncStateRepository.setAttachmentsSyncEnabled(scope, enabled)` — scoped like its
  siblings, so `SyncScope` rejects an incomplete pair itself.
- `SettingsTab.Sync` renders `SyncViewModel`'s state and hosts the attachment row.
- `attachmentsSyncTransportAvailable`, a `const val = false` read by the row. It is a
  constant rather than a comment so the row's refusal to pretend is a thing with a reader.
- `SettingsSwitchRow.onCheckedChange` becomes nullable, so a row with no handler cannot
  render as live.

## Deliberately not done

- **No `SyncRunner` observer.** The plan asks for one. A collector for this value would
  read it and pass it to nothing, because nothing in the sync core can act on it — that is
  the inert-surface defect one layer down. The observation belongs with stage 2, where it
  has a consumer. ADR `2026-10-07-attachment-sync-is-staged-behind-a-server-blocker`.
- **No intent on `SyncViewModel`.** The row cannot be dispatched, so a write intent would
  be a handler with no caller. The write path is the repository method, which is scoped and
  tested.
- **`DocType.Attachment`.** Adding it client-side before the server accepts it means
  sending a type the server does not know and being rejected on every attachment.

## Blocked on work outside this repository

Stage 0: `DocType` on the server, a branch in `sync_apply_ops`, a `sync_shadow` write, a
writable-field allowlist, and binary storage. `StubAttachmentUploadService` is the binary
transport and is scheduled to be closed here rather than relabelled.

## References

- `docs/decisions/2026-10-07-the-sync-settings-screen-did-not-exist.md`
- `docs/decisions/2026-10-07-attachment-sync-is-staged-behind-a-server-blocker.md`