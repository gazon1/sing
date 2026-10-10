---
title: "Notification Text Null Invisible"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "notification-routing-must-be-total"]
---

**Status: CLOSED**

**Tracked as:** #103
**OpenSpec change:** `openspec/changes/notification-routing-must-be-total/`

**Found in:** MR-0, свип `Notification.Text(x, null)` по production commonMain.

**Symptom:** `NotificationHost.kt:72-78` роутит `Notification.Text(title, text?)` в `ResultDialog`, у которого `if (text == null) return` — ничего не рендерится. Три живых сайта:

1. `TaskDetailContent.kt:58` и `TaskDetailViewScreen.kt:87`: `is TaskDetailUiEvent.Saved → Notification.Text(event.message, null)`.
2. `SavedAgendaListScreen.kt:72`: `is SavedAgendaListEvent.CopySuccess → Notification.Text("Copied to ${e.targetProfileName}", null)`.

Копирование view в профиль показывает пользователю **ничего**.

**Status: RESOLVED** (MR-1, 2026-10-03). All three sites were rewritten:

1. `TaskDetailViewScreen.kt:86` — `is TaskDetailUiEvent.Saved → Notification.None`.
   The screen already leaves the editor on save, so a message would be noise.
2. `SavedAgendaListScreen.kt:82` — `CopySuccess` now shows a snackbar through
   `snackbarHostState.showSnackbar("Copied to ${e.targetProfileName}")`, so the
   user sees the profile the view landed in.
3. `TaskDetailContent.kt` no longer exists; its event mapping moved into
   `TaskDetailViewScreen.kt`.

A sweep of production `commonMain` finds four remaining `Notification.Text`
sites (`ArchiveScreen`, `NoteEditorNotifications`, `ProjectsScreen` ×2) and
**all four pass a non-null `text`**, so none of them hits `ResultDialog`'s
`if (text == null) return`.

**Still latent (not a bug, a design hazard):** `NotificationHost.kt:72` still
routes `Notification.Text` to `ResultDialog`, and `ResultDialog` still returns
silently on a null text. Nothing produces that combination today, so there is
nothing to fix — but the next person who writes `Notification.Text(title, null)`
gets silence, not an error. A follow-up would make the routing total (route a
null text to the snackbar host, or make the parameter non-null).

---
