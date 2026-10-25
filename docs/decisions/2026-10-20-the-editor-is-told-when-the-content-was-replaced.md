---
status: accepted
date: 2026-10-20
---

# The editor is told when the content was replaced, not only that it changed

## Context

The note editor holds a **bridged** text field: a Compose `TextFieldState` fed
by a `MutableStateFlow` in the ViewModel. The bridge has one rule — the flow
may replace the field's content, but only when the content really changed —
and everything else about the field follows from that rule.

The rule was incomplete, and the incompleteness had a shape. The editor could
not tell the difference between

- the user typing, and
- somebody else rewriting the note underneath them.

Both arrive as "the flow emitted a new value".

The consequence was a silent loss of work. When a note was rewritten from
outside — a proposal applied to it, a sync from another device — the editor
either ignored the rewrite (the field kept the stale text, and the user's next
keystroke dispatched the stale text back over the new one) or took it wholesale
(the user's unsaved edits were replaced, and nothing said so).

The echo guard is the reason. It exists to stop the field's own pushes from
coming back as replacements, and it does that by comparing the incoming value
with what the field last produced. A rewrite that lands on a value the field
*did* produce earlier is therefore indistinguishable from a lagging upstream —
so the guard drops it. The rewrite is lost.

## Idea

Let the upstream say *which kind of change* it is delivering.

## Decision

`EditorState.Editing` carries a **`revision: Long`**, and the bridge reads it.

- **The revision counts replacements, not edits.** It advances only when the
  editor re-seeds itself from the note, never when the user types. A revision
  that moved on every keystroke would be a second copy of the value.
- **The watcher decides.** The ViewModel watches the open note. When the note's
  content changed **and the draft is clean**, the editor re-seeds the title and
  the body and bumps the revision.
- **The user's unsaved edits win.** A dirty draft is never re-seeded. The
  external rewrite is then lost to the next save, which is the honest outcome:
  the user has something to lose, the other writer does not.
- **The editor's own save is not a rewrite.** The watcher compares the content,
  so the echo of an autosave changes nothing and moves no revision — which is
  also what keeps the caret still.
- **The body moves its baseline.** `EditorSession.replaceContent(html)` sets
  the content and moves `lastDispatchedHtml` with it, so the next save compares
  against what is on screen.

The revision is the *mechanism*; the question it answers — "who may rewrite
this field?" — is a separate contract, published per field site in
`.agents/skills/singularity-todo-text-field-states/SKILL.md` and held honest by
`TextFieldRewriteContractTest`. The three answers are *none*, *the value
itself*, and *the value and a revision token*.

## Rationale

The alternative was to drop the echo guard, or to compare more than the value.
Both were rejected:

- **Dropping the guard** makes the field's own push replace its own text, which
  moves the caret to the end on every keystroke. That is a worse bug than the
  one being fixed.
- **Comparing timestamps or contents more cleverly** cannot work: the upstream
  that rewrote the note may have rewritten it *to* the value the field already
  produced. No amount of comparison distinguishes that case from an echo. The
  upstream has to say so.

The revision token is the smallest thing that carries that information, and it
costs one `Long` on the draft.

The "the user's edits win" rule is a decision, not an oversight. The other
writer has no stake in the text; the user does.

## Consequences

- A note rewritten from outside now reaches the editor, and the user sees the
  new content instead of the next keystroke overwriting it.
- A user mid-edit keeps their text; the replacement does not land under their
  fingers.
- A draft now carries a field whose only meaning is "the content was replaced
  N times". It is meaningless outside the editor, which is why it lives on the
  draft and not on the domain model.
- The field sites that carry only the value answer the rarer half of the
  question — "can a rewrite land on a value the field produced?" — and the
  registry says so rather than leaving it to be discovered.

## Links

- `openspec/changes/archive/2026-10-25-the-editor-and-the-rewrite-from-outside/`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/Ids.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/viewmodel/NoteRewriteWatcher.kt`
- `.agents/skills/singularity-todo-text-field-states/SKILL.md` — section 9
