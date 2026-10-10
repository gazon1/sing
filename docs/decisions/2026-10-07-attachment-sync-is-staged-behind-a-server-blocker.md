---
title: Attachment sync is staged behind a server blocker, and the stage-1 setting is stored, shown, and locked
date: 2026-10-07
status: accepted
tags: [sync, attachments, settings, scope]
---

# Attachment sync is staged behind a server blocker, and the stage-1 setting is stored, shown, and locked

## Context

The plan for the attachment work (PR-6) asks for a per-scope "sync attachments" setting,
observed by `SyncRunner`, so that toggling it takes effect without a restart or a profile
switch.

Two things were not what the plan assumed.

**There is no sync settings screen.** `SyncViewModel` is bound in the Koin graph and never
injected. `SyncButton` has no call site at all. `SettingsTab` has no Sync entry. So "add a
`SettingsSwitchRow` next to the existing sync settings" had no existing settings to sit
next to — the section did not exist, and the two existing artefacts of it were unreachable.
This is the same defect class the whole change set is about, one level up: implemented,
bound, and reachable from nowhere.

**There is no transport.** The word `attachment` does not occur anywhere in `core/sync`.
`DocType` carries `Task`, `Note`, `Project`, `Tag`, `TagGroup`, `TimeEntry`, and its KDoc
pins that set to the Flutter `sync_core` contract, so the list is fixed by a backend this
repository does not own. `AttachmentUploadService` is a stub whose `upload()` returns a
*local* path as though it were a remote one. Document metadata and file bytes are different
transports: JSON goes through `sync_apply_ops`, bytes need storage that does not exist.

So the requested observer had nothing to observe into. `SyncRunner` collects
`autoSyncEnabled` and `scheduledInterval` because scheduling genuinely depends on them;
a collector for `attachmentsSyncEnabled` would have had no consumer at all — which is
precisely the inert-surface shape `find-unwired-surfaces.py` exists to find.

## Idea

Three options were weighed.

1. **Skip the setting entirely until the transport exists.** Rejected: the per-scope
   decision is the part the client owns and can get right now. Doing it later means
   retrofitting scoping onto whatever exists then, and the scoping is the hard part.
2. **Store it and add the `SyncRunner` observer now, as written.** Rejected: the observer
   would read a value and pass it to nothing. That is the defect, not a fix for it.
3. **Store it per scope, show it locked, and defer the observer to the stage that has a
   consumer.** Chosen.

## Decision

Stage 1 is: state, scoping, and an honest locked control.

- `sync_state.attachments_sync_enabled`, defaulting to `0`, reached by
  `AutoMigration(40 to 41)`. Not a `SyncPrefs` key: those setters are deprecated precisely
  because that is the one global slot every profile shares, and a third global key would
  let one profile's answer describe another's files.
- `SyncStateRepository.setAttachmentsSyncEnabled(scope, enabled)`, scoped like every
  sibling, so an incomplete owner/profile pair is rejected by `SyncScope` itself.
- The sync settings screen is **built**, because its absence is the defect: it makes
  `SyncViewModel` and `SyncButton` reachable, and it hosts the attachment row.
- The row renders **disabled** with the reason in its subtitle, reading
  `attachmentsSyncTransportAvailable` — a `const val = false` whose readers are the
  settings row and the KDoc. It is a constant rather than a comment so the gate that
  looks for unconsumed state has something to find.
- **No `SyncIntent` for it.** The control cannot be dispatched, so an intent that writes
  the field would be a handler with no caller — the same shape one layer down. The write
  path is the repository method, which is scoped and tested; the intent arrives with
  stage 2, when something real can dispatch it.
- The stored value is still observed and displayed, so each scope already holds its own
  answer on the day the transport lands.

## Rationale

The rule this change set follows is that a control must not look operable when it is not.
That applies with more force to a control whose whole purpose is to start something. A
switch that accepts a tap and stores a preference that no component reads is a lie with a
checkmark in it — and unlike the inert controls this work removed elsewhere, it would be
*new* code rather than inherited, which makes it worse, not better.

The `const val` earns its place by being the one thing that would change: flipping it is
the whole of "stage 1 goes live", and the surrounding code was written to survive that
flip. Making `SettingsSwitchRow.onCheckedChange` nullable in the same pass is what makes
the flip safe — with it, `enabled = true` alongside a null handler still renders an
inert switch, so the constant alone cannot produce a live-looking dead control.

The screen is not scope creep. PR-6's UI requirement could not be met without it, and
building it fixes two unreachable artefacts that the unwired-surface gate does not check
(because neither is a Composable with the usual shape).

## Consequences

- Room is at `SCHEMA_VERSION = 41`. Existing installs take an `ALTER TABLE ADD COLUMN`
  with a `0` default, so no scope claims a preference it never expressed.
- `SettingsSwitchRow.onCheckedChange` is now nullable. Every existing call site passes a
  lambda and is unaffected; the switch is additionally gated on the handler being present.
- `SettingsValueRow` and `SettingsActionRow` gained a `testTag` parameter with a shared
  default, matching `SettingsSwitchRow`.
- A settings rail now holds twelve rows rather than eleven. It was already taller than a
  phone viewport and already scrolled; the comment saying so was updated rather than left
  describing a count that no longer held.
- **Stage 2 remains blocked on work outside this repository**: `DocType.Attachment` on the
  server, a branch in `sync_apply_ops`, a `sync_shadow` write, a writable-field allowlist,
  and binary storage. Until the server accepts the type, adding it client-side would mean
  sending a document type it does not know and being rejected on every attachment.
- `StubAttachmentUploadService` still reports a local path as a remote one. It is
  scheduled for stage 0 and was not touched here: closing it means implementing the
  transport, not relabelling the stub.

## Links

- Plan: `attachment-sync-setting` (OpenSpec change).
- Related: the record of "two of three background jobs were not buildable yet", for the
  parallel case of a piece of machinery that existed before anything could reach it.
