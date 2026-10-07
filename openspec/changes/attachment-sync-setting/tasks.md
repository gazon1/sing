# Tasks — attachment-sync-setting

**status:** proposed

---

## Phase 1 — OpenSpec artifacts

- [x] `proposal.md`
- [x] `specs/core-sync-state/spec.md`
- [x] `tasks.md`

## Phase 2 — State and scoping

- [x] `sync_state.attachments_sync_enabled`, default `0`
- [x] `Migration40To41` + `AutoMigration(from = 40, to = 41)`; `SCHEMA_VERSION` 40 → 41
- [x] `SyncStateDao.setAttachmentsSyncEnabled`
- [x] `SyncStateRepository.setAttachmentsSyncEnabled(scope, enabled)`
- [x] `SyncState.attachmentsSyncEnabled` + `SyncState.from(entity)`
- [x] `FakeSyncStateDao` and `FakeSyncStateRepository` updated

## Phase 3 — The screen that did not exist

- [x] `SyncSettingsContent` — status, sync now, auto-sync, interval, connection test
- [x] `SettingsTab.Sync` wired into `SettingsContent`, with an icon
- [x] Nav-rail comment corrected from eleven rows to twelve
- [x] `SettingsValueRow` / `SettingsActionRow` gained `testTag`

## Phase 4 — Honest presentation

- [x] `attachmentsSyncTransportAvailable` and the reason constant
- [x] Attachment row rendered locked, reading the constant, with the reason as its subtitle
- [x] `SettingsSwitchRow.onCheckedChange` made nullable; the switch is enabled only when
      the row is enabled *and* has a handler

## Phase 5 — Tests

- [x] `Migration40To41Test` — cursor survives, new column reads off, stays per profile
- [x] `SyncViewModelTest` — the preference is read from the current scope and swapped on a
      profile switch; it defaults to off

## Stage 2 — blocked on the server

- [ ] `DocType.Attachment` — only after the server accepts the type
- [ ] `SyncRunner` observes `attachmentsSyncEnabled` — only when there is a consumer
- [ ] `SyncIntent.SetAttachmentsSync` — arrives with the intent above
- [ ] Close `StubAttachmentUploadService` with a real binary transport
