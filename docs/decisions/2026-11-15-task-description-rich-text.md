---
title: "Task description is rich text — HTML canonical, plain projection for the index"
date: 2026-11-15
tags: [tasks, rich-text, database, search, sync]
status: accepted
---

# Task description is rich text — HTML canonical, plain projection for the index

## Context

The Task Description field was a `BasicTextField`. The product contract
(#2952) asks for the Compose Rich Editor the Notes feature already uses:
formatting, links and a rendered read-only surface, with the smallest possible
diff.

The task row has one description column, `tasks.description`. Two facts about
that column decide the design:

- `tasks_fts` is an **external-content FTS4 table with `content=tasks`**. Room
  installs triggers that copy `NEW.description` into the index verbatim, so
  whatever is stored in that column *is* what a search tokenizes. HTML stored
  there would make `strong`, `href` and `p` searchable words.
- Every plain-text consumer already reads `Task.description`: AI prompts, the
  Google Calendar body, the `has:description` filter, the typed AI tools and
  the backup. Those want the text a person wrote.

The issue allows keeping the existing field and DTO, and avoiding a migration
"if the existing string field can safely hold the chosen representation". It
can hold HTML, but it cannot hold HTML *and* stay the full-text content
column, so the correctness requirement is what makes the schema change
necessary.

## Decision

### 1. Two fields, one owner

- `Task.descriptionHtml` — canonical HTML, written by the editor.
- `Task.description` — the plain-text projection, unchanged in meaning, still
  the FTS content column and the field every existing consumer reads.

`TaskDescriptionHtml` (`core-domain`, pure string work) is the **only**
conversion boundary:

- `plainTextToHtml` escapes legacy text and turns line breaks into `<br>`, so
  a description written before the rich editor (`descriptionHtml == null`) is
  shown literally even when it contains `<b>` or `&`.
- `htmlToPlainText` strips tags, decodes entities and turns block tags into
  line breaks for the projection.
- `storedHtml`/`storedPlain` drop stale HTML when `description` no longer
  matches its projection. A writer that only knows about plain text (an AI
  tool, a calendar import, an older client) therefore cannot leave the editor
  showing content the task no longer holds.

`Task.descriptionRichHtml` is the read boundary: `canonical(descriptionHtml,
description)`. The editor, the read-only renderer and the series repair use it,
so no caller has to decide what a description means.

### 2. The editor reuses the Notes pieces, moved to `core`

`EditorAction`, `EditorToolbar`, `LinkUrlDialog`, `InternalLinkPickerSheet`,
`LinkResult`/`LinkKind` and the `EditorSession` class are feature-independent.
They moved to `core/ui/rich/`; the Notes factory `rememberEditorSession` stayed
in `feature/notes`. Tasks import the shared pieces, not the Notes feature.

The Task field is document style, like the title:

- **detail** — `RichText` renders the description and hands a link tap to the
  app URI handler (`rememberAppUriHandler`); a tap elsewhere opens the field.
- **create** — always editable.
- The formatting toolbar is drawn under the field while editing. Link removal
  is `EditorAction.RemoveLink` (`RichTextState.removeLink()`), shown only when
  the selection is on a link.

### 3. Persistence

- Room: `tasks.description_html` (nullable), `SCHEMA_VERSION` 53 → 54,
  `AutoMigration(53, 54)`; the export is `54.json`.
- Backup: `TaskDto.descriptionHtml`, additive and nullable.
- Sync: the field is on the wire automatically (derived from the model); the
  server accepts it through a new dated migration under
  `infra/supabase/migrations/`, added with this change.
- Series: the description pair is one shared value in `TaskSeriesInvariants`,
  so a repair copies the HTML with the plain text.

The plain projection stays the FTS surface. The new column is never indexed.

## Rationale

- **One representation, one projection.** Storing HTML in `description` and
  adding a plain column would force every consumer to switch to a new field
  and would change the FTS content entity. Keeping `description` plain leaves
  the index, the AI prompts, the calendar and the `has:description` filter
  untouched.
- **Legacy is deterministic.** `descriptionHtml == null` is the unambiguous
  legacy marker; escaping is the literal reading. No format sniffing on the
  text.
- **The diff is integration, not a second editor.** The Notes components moved
  rather than being copied, and the toolbar/dialog/state are the library's own.

## Consequences

- A writer that changes `description` without `descriptionHtml` drops the rich
  field on the write. That is intentional: the plain text is what the task now
  says, and keeping the old rendering would be a lie.
- `Task.description` is no longer "the description" but its projection. Code
  that means "what the user edited" should use `descriptionRichHtml`.
- Rich formatting is lost when a series repair sees a description pair that
  disagrees — the repair copies both fields from the source.
- The read-only renderer opens links; the editable surface does not. That is
  the library's behaviour (1.2.1) and the reason the detail is document style.

## Links

- Issue #2952 (parent #2872)
- `core-domain/.../core/text/TaskDescriptionHtml.kt`
- `shared/.../feature/tasks/presentation/components/detail/TaskDescriptionField.kt`
- ADR `2026-10-02-note-body-canonical-html.md` — the Notes precedent: HTML is
  canonical, projections are derived.
