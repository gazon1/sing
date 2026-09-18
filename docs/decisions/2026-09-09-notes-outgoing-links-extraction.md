---
title: "Notes — wikilink extraction via HTML parsing + setOutgoingLinks wired to persist()"
date: 2026-09-09
tags: [notes, wikilinks, rich-editor, room]
status: accepted
---

## Context

`NoteDao.setOutgoingLinks` existed since v7, and the `outgoing_links` column was in the schema, but the column was always empty in production — no code path ever called `setOutgoingLinks`. This meant backlinks (`getBacklinkNotes`) were permanently dead.

The root cause was architectural: extracting links from the editor's internal state was not straightforward.

## Problem

**Goal:** populate `outgoing_links` on every save so `getBacklinkNotes` returns real results.

The richeditor library (`com.mohamedrejeb.richeditor:richeditor-compose:1.2.0`) stores links as `RichSpanStyle.Link(url)` objects in an internal paragraph tree (`RichParagraph.children: MutableList<RichSpan>`). The class `RichParagraph` and its children are marked `internal`, making it impossible to walk the tree from outside the library module.

The HTML output of `RichTextState.toHtml()` is deterministic: links serialize as `<a href="note://...">` or `<a href="task://...">`. The Markdown output does not encode links losslessly.

## Decision

**Step 1 — HTML regex extraction (this PR).**

Parse `RichTextState.toHtml()` output with a simple regex to extract `note://` and `task://` URLs:

```kotlin
internal fun extractOutgoingLinks(html: String): List<LinkRef> {
    val seen = mutableSetOf<LinkRef>()
    val regex = Regex("""<a\s[^>]*href="(note://[^"]+)""")
    for (match in regex.findAll(html)) {
        val url = match.groupValues[1]
        when {
            url.startsWith("note://") -> seen.add(LinkRef.Note(...))
            url.startsWith("task://") -> seen.add(LinkRef.Task(...))
        }
    }
    return seen.toList()
}
```

`extractOutgoingLinks` is placed in `feature/notes/OutgoingLinksExtractor.kt` alongside the `LinkRef` sealed interface.

**Step 2 — Wiring.**

`NoteEditor.persist()` now calls `repo.setOutgoingLinks(id, outgoingLinks)` immediately after `repo.updateContent(...)`. Both must succeed; links are not written if the content save fails.

```kotlin
private suspend fun persist(html: String, title: String, id: String, navigateBack: Boolean) {
    repo.updateContent(...).getOrThrow()
    val outgoingLinks = extractOutgoingLinks(html).map { ... }
    repo.setOutgoingLinks(NoteId.fromString(id), outgoingLinks).getOrThrow()
    // ...
}
```

**Step 3 — Repository interface.**

`NotesRepository` gets a new method:
```kotlin
suspend fun setOutgoingLinks(id: NoteId, links: List<String>): Result<Unit>
```

`RoomNotesRepository` delegates to `NoteDao.setOutgoingLinks`. `FakeNotesRepository` (test doubles) implements it in-memory. No new Room migration needed — the column already exists.

## Why not richer extraction?

A future PR may extract links directly from the paragraph tree if the library exposes a public API, or if a custom span style with a public `link` property is added. The regex approach is reliable given that the HTML format is controlled by the library and is simple enough to parse correctly.

## Consequences

### Positive
- `getBacklinkNotes` now returns real results — backlinks in `NotePreview` and `InternalLinkPickerSheet` will work
- The `outgoing_links` column is populated on every save, keeping backlinks current
- No schema migration needed
- No new dependencies

### Negative
- Regex over HTML is less elegant than walking the paragraph tree, but the paragraph tree is internal

## Links

- `OutgoingLinksExtractor.kt` — pure extractor + `LinkRef` sealed interface
- `NoteEditor.kt` — `persist()` now calls `extractOutgoingLinks` and `repo.setOutgoingLinks`
- `NotesRepository.kt` — new `setOutgoingLinks` method on interface
- `NoteDao` — `setOutgoingLinks` DAO already existed
