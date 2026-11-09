---
title: The link goes in at the caret
date: 2026-11-04
status: accepted
tags: [notes, editor, links, rich-text]
---

# The link goes in at the caret

## Context

The note editor has two ways to put a link into the body: the URL dialog, and the
`[[note]]` / `[[task]]` picker. Both of them ended in the same call —
`richTextState.addLinkToSelection(url)` — and the library refuses a collapsed
selection:

```kotlin
public fun addLinkToSelection(url: String) = recordHistory(CommitTrigger.Formatting) {
    if (selection.collapsed) return@recordHistory
    ...
}
```

The refusal is silent. The caller is told nothing, the dialog closes, and the note
stays exactly as it was.

That made the link a feature that worked only when the user had selected something
first, and the screen never said so. The common case — the user taps inside a word
and asks for a link — was the case that did nothing.

## Idea

A link is a piece of text with an address. When there is no text to carry it, the
link brings its own.

## Decision

- **The insertion lives in one place: `EditorSession.insertLink(url, text)`.** The
  session owns the editor's state, so the session is where the two cases are told
  apart — and the caller stops having to know that the library refuses a caret.
- **A selection keeps the library's behaviour.** The selected text becomes the
  link, and no text is written.
- **A caret gets the link text written at the caret, and the link is applied to the
  range that text occupies.** The text is written first (`addTextAfterSelection`),
  and the link is then applied to that range (`addLinkToTextRange`) — the library has
  no caret-insert of its own, and the range it does have needs text to sit on.
- **The URL dialog shows the address as the link text; the internal picker shows the
  title.** The address is what the user typed, and the title is what the sheet
  offered.

## Rationale

The alternative was to say no: check the selection, keep the dialog open, and tell
the user to select some text first. That is honest, and it is cheaper — but it
leaves the user with a dead end and a lost URL: the dialog is modal, so the text
cannot be selected while it is open, and the address they typed has to be typed
again. A caret is not a mistake the user made; it is the state the editor is in
whenever they have not selected anything.

The library's refusal is a statement about spans: a link style is a span, and a
span needs characters to be a span on. Writing the text first and linking the
range that follows is the same operation the selection case performs, with the
text supplied rather than selected.

## Consequences

- The link text is part of the note. An address inserted at a caret is visible text
  in the body, and it is saved with the body like any other text.
- The insertion is one call for both link kinds, so the picker and the dialog
  cannot drift apart in how they treat a caret.
- The library still refuses a collapsed selection; the session's insertion is the way
  this app writes a link, and it is the app's only caller of the library's own link
  insertion.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/EditorSession.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/screen/NoteEditorScreen.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/notes/EditorSessionLinkTest.kt`
- #1710
