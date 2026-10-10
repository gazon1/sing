---
title: "A Testtag Built From A Localised Label Changes With Device Locale"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "selector-and-tag-identity"]
---

**Status: CLOSED**

**Tracked as:** #109
**OpenSpec change:** `openspec/changes/selector-and-tag-identity/`

**Found in:** 2026-10-04, the third `smoke` run — after the overflow rows were
finally tagged, `tasks/04-delete` still could not find
`id: task_action_delete`.

**Symptom:** the tag was applied, the row was on screen, the assertion still
failed. The captured hierarchy explains it: the emulator runs in **Russian**
(`accessibilityText=Меню` on the overflow button, "Архивировать / Удалить" in
the menu), and the tag was built as `TestTags.taskAction(item.label)`. With
`label = "Удалить"` that produces `task_action_удалить`. The flow asked for
`task_action_delete`.

**Why nothing caught it:** every prior check reasons about the tag *string* —
`TestTagsWiringTest` checks the constant is applied, `MaestroFlowTagsTest` checks
the id is declared, and the desktop Compose tests read the semantics tree, where
the value is whatever it is and nothing compares it to an expectation. The
localised value is perfectly valid; it is simply not the one the flow wants.
Only a run on a device with a non-English locale surfaces it — and this repo's
device defaults to English, so the bug would have shipped.

**Fix:** `TaskEditorMenuItem` gained a stable `action: String` ("archive",
"delete", "restore"), and the tag is built from that. `label` stays localised
and stays for the user. Every construction site passes both.

**The generalisable rule, which is the reason this is a backlog entry rather
than a one-line fix:** *a selector must never be derived from text a user can
translate.* The failure is silent, locale-dependent, and invisible to every
check that only asks whether a tag exists. The same applies to
`TestTags.taskAction` anywhere else it is fed a UI string — `TaskContextMenuSheet`
feeds it hardcoded English labels today, which works by luck of the current
locale, not by design.

**Do this first:** grep for `testTag(` calls built from a `.label`/`.text`/
`.title` field and convert them to a stable id. `find-unwired-surfaces.py` is the
natural home for a check here, alongside the window-owning-surface detector
proposed in `2026-10-04-testtag-visibility-helper.md`.

---
