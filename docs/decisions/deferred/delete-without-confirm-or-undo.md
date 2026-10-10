---
title: "Delete Without Confirm Or Undo"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "notification-routing-must-be-total"]
---

**Status: CLOSED** — policy implemented. The policy ( строчные deletes → `Notification.Undo`; каскадные/невозвратные → `ConfirmActionDialog`) was applied across all surfaces. See the entry body for the per-surface breakdown. The correction in issue #253 clarified the agenda delete never actually fired the mutation — the policy was applied on top of that fix.

**Tracked as:** #104
**OpenSpec change:** `openspec/changes/notification-routing-must-be-total/`

**Found in:** MR-0, свип delete flows по всем screens.

**Symptom:** `ConfirmActionDialog` используется только в 2 местах: `SavedAgendaScreen` delete-view и `ProfileSwitcherScreen` delete-profile. Остальные 10 delete flow'ов удаляют мгновенно и молча:

- ~~`AgendaContent.kt:314` → `AgendaViewModel.kt:98` (`TaskDeleteClicked`, no event)~~ — **поправка, #253:** не «мгновенно и молча», а обратное. `handleTaskDelete` вообще не вызывал `taskRepo.softDelete`: ставил маркер, эмитил событие, заводил таймер. Snackbar рапортовал об удалении, которого не было; `reminderScheduler`/`currentUser` в `AgendaDeps` были неиспользуемыми. Исправлено в #253.
- `SavedAgendaListScreen.kt:107` → `SavedAgendaListViewModel.kt:78`
- `ProjectDetailBody.kt:111` → `ProjectDetailViewModel.kt:349`
- `NotesListScreen.kt` × 5 мест → `NotesListViewModel.kt:250`
- `ProjectsScreen.kt:80` → `ProjectsViewModel.kt:104`
- `TagsScreen.kt:149` → `TagsViewModel.kt:102`
- `TagGroupsScreen.kt:103` → `TagGroupsViewModel.kt:78` (каскадное!)
- `SearchScreen.kt:143` → `SearchViewModel.kt:314`
- `SettingsScreen.kt:261` → `BackupViewModel.kt:193`
- `AttachmentTile.kt:76` → `AttachmentsViewModel.kt:60`

KDoc `Notification.kt:17-27` предписывает `Notification.Undo` для deletes. Паттерн существует в `TaskDetailViewScreen.kt` для task delete.

**Status: OPEN.** Политика: строчные deletes (tasks, notes, tags, views, searches, attachments) → `Notification.Undo`; каскадные/невозвратные (проект, группа тегов, backup-файл) → `ConfirmActionDialog`. Фиксируется в MR-1.

**Оговорка по #104 после #253.** Пункты #78, #80 и сам этот свип описывали agenda как работающий образец delete-with-undo. Он не работал — см. правку выше. Планы #78 и #80 выводились из поведения, которого не было; перечитать их нужно до начала работы, а не после.

---
