---
description: Internal links (Obsidian-style) + backlinks panel for Notes editor
---

# Notes Internal Links + Backlinks (Phase 2 extension)

## Context

The NoteEditor needs Obsidian-style `[[Note Title]]` and `[[Task Title]]` linking so users can create connections between notes. When a user taps a link, they should navigate to the target. Users also need to see which other notes link to the current one (backlinks).

## Decision

### Internal Link Picker

A new toolbar overflow action **Internal Link** opens a bottom sheet (`InternalLinkPickerSheet`) with two tabs: **Notes** and **Tasks**. The sheet has a debounced search field that queries by title. Selecting an item inserts a link with URL scheme `note://<id>` or `task://<id>` into the rich text editor via `RichTextState.addLinkToSelection()`.

The URL scheme is opaque — the link text shows as `[[Title]]` via the wikilink round-trip in `RichEditorMarkdownHtmlPort`.

### Schema v7

`NoteEntity` gains an `outgoing_links TEXT DEFAULT '[]'` column storing a JSON array of URL strings (`["note://uuid1", "task://uuid2"]`). This enables backlink queries without parsing HTML. AutoMigration from v6 → v7 via `Migration6To7 : AutoMigrationSpec`.

DAO methods added:
- `NoteDao.searchByTitle(userId, q)` — title-only search for the picker
- `NoteDao.setOutgoingLinks(id, json, updatedAt)` — persist parsed links on save
- `NoteDao.getBacklinkNotes(noteId)` — `SELECT * FROM notes WHERE outgoing_links LIKE '%note://' || :noteId || '%'`
- `TaskDao.searchByTitle(q)` — for the task picker tab

### Wiki-link Round-trip

`RichEditorMarkdownHtmlPort` updated:
- **toHtml**: `[[Title]]` → `<a href="note://URL-encoded-title">Title</a>`
- **toMarkdown**: `<a href="note://...">Title</a>` → `[[Title]]` (preserves display text)

### Link Tap Navigation

`RichTextEditor` has no public `onLinkClick` API (the library uses internal `getLinkByOffset`). Workaround: `EditorSession` tracks inserted links with their character ranges via `recordLink(url)`. When the user positions the cursor near a link and taps (via `pointerInput { detectTapGestures }`), the tap handler queries `session.findLinkAt(cursorOffset)` and routes `note://` / `task://` to navigation callbacks; other URLs to `LocalUriHandler.openUri()`.

### Backlinks Panel

A button (↩ undo icon) in the top app bar opens `BacklinksSheet` — a `ModalBottomSheet` showing notes that link to the current note (via `InternalLinkRepository.getBacklinkNotes`). Tapping a backlink navigates to that note.

### EditorAction Updates

- `Link` → `ExternalLink` (external http/https URLs, existing `LinkUrlDialog`)
- New `InternalLink` (opens `InternalLinkPickerSheet`)
- `Quote` kept as no-op (library has no blockquote support)

## Consequences

- Internal links survive HTML round-trip (stored as `note://` / `task://` href)
- Backlinks queryable via SQL without HTML parsing
- Link tap detection requires cursor placement (no visual link highlight tap) — acceptable tradeoff given library limitation
- Schema v7 requires `fallbackToDestructiveMigration` during development (dev strategy per skill)

## Links

- `singularity-todo-rich-editor` skill
- `singularity-todo-room-migration` skill (dev migration strategy)
