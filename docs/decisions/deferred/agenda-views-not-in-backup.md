---
title: "Agenda Views Not In Backup"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Tracked as:** #302 · OpenSpec change `backup-include-remaining-tables` (proposed); **#478** (8 tables gap)
**Supersedes:** #77 (closed — 8 remaining tables captured in #302)

**Found in:** MR-0, свип BackupPayload vs Room tables.

**Symptom:** `agenda_views` таблица (Room) не входит в `BackupPayload`. При restore из backup все saved views теряются. Также отсутствуют: `task_reminders`, `project_reminders`, `checklist_items`, `tag_groups`, `project_tag_groups`, `saved_searches`, `time_entries`, `profiles`.

**Status: CLOSED — tracked GitHub issue is closed**** (MR-1, 2026-10-03). `agenda_views` is in the
backup: `BackupPayload.agendaViews` (`:17`), `BackupExporter` reads it
(`:37`, `:49`) and counts it in the manifest (`:65`), `BackupImporter` writes it
back (`:120`), and `BackupFormat.kt:5` records the version bump.

The other eight tables are untouched and remain a real gap: `task_reminders`,
`project_reminders`, `checklist_items`, `tag_groups`, `project_tag_groups`,
`saved_searches`, `time_entries`, `profiles`. Note the two profile tables are
a *different* problem from the other seven — cross-profile restore needs a
decision about which profile becomes active, not just a DTO.

---
