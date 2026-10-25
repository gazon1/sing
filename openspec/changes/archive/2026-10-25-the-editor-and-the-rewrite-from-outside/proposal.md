# the-editor-and-the-rewrite-from-outside

Issue: — · Related: `docs/decisions/2026-10-20-the-editor-is-told-when-the-content-was-replaced.md`

Status: **accepted**
Tags: **notes editor bridge contract**

## What

Make the note editor answer a question it was never asked: **who is rewriting
this field right now?**

The editor holds a bridged text field — a Compose `TextFieldState` fed by a
`MutableStateFlow` in a ViewModel. The bridge has one rule: *the flow may
replace the field's content, but only when the content really changed.*
Everything else about the field follows from that rule.

The rule was incomplete, and the incompleteness had a shape: the editor could
not tell the difference between

- the user typing, and
- somebody else rewriting the note underneath them.

Both arrive as "the flow emitted a new value". So when a note was rewritten
from outside — a sync landing, an undo, a second device — the editor either
ignored it (the field kept the stale text, and the user saved the stale text
back) or took it wholesale (the user's unsaved edits were replaced by
somebody else's text, and the user never saw it happen).

The fix is a **revision counter on the draft**:

1. `EditorState.Editing` carries `revision: Long`. It counts *replacements*,
   not edits.
2. The ViewModel watches the note. When the note's content changed **and the
   draft is clean** (the user has no unsaved edits), the editor re-seeds the
   title and the body and bumps the revision.
3. The title bridge passes the revision to the bridged field state, so the
   field knows the new value is a replacement and not a keystroke.
4. The body session moves its baseline with `replaceContent`, so the next save
   compares against what is on screen.

**The user's unsaved edits win.** A dirty draft is never re-seeded. The
editor's own autosave echo is not a rewrite either — the watcher compares the
content, so a round trip through the repository does not bump the revision
and does not disturb the caret.

The second half of the change is the **rewrite contract**: a registry of every
bridged field site in the app, and the answer to "who may rewrite this field?"
for each one. Thirteen sites, three answers:

- **none** — the value is a one-way projection; nothing rewrites it.
- **the value itself** — a rewrite arrives as a new value, and the field
  replaces its content. This is the common case, and it is also the case that
  cannot express "this is a replacement, not a keystroke".
- **the value and a revision token** — the site carries a counter alongside
  the value, so the bridge can tell a replacement from a keystroke.

The notes editor is the only site that answers the third way. The sites that
answer the second way carry the rarer half of the gap, and the registry says
so in the open rather than leaving it to be discovered.

Two smaller things fall out of the same reading:

- The **dead link map** in `EditorSession` (`insertedLinks`, `recordLink`,
  `findLinkAt`) had no callers, and its own KDoc says the map is the wrong
  mechanism — a link is found by walking `RichTextState.children`. The map is
  removed rather than wired: wiring it would have given a feature the wrong
  shape, and the missing feature is a separate question.
- The **notes Maestro flows** now carry the `smoke` tag. The e2e job only runs
  on pull requests labelled `run-maestro`, and the notes flows were outside
  the smoke tag — which is exactly why a body-edit bug could sit in the tree
  with every other gate green.

## What not to do

- **Do not re-seed over unsaved edits.** The tempting fix — always take the
  incoming value — destroys work. The dirty draft is the user's; the
  replacement waits.
- **Do not count an edit as a revision.** A revision that moves on every
  keystroke is not a revision, it is a second copy of the value. The counter
  moves when the *content* is replaced.
- **Do not treat the autosave echo as a rewrite.** The editor's own save
  comes back through the same watcher. Comparing the content is what keeps
  the caret still.
- **Do not wire the link map to make the warning go away.** The map's own
  documentation says the map is wrong. The feature that is missing is "tap a
  link in the body", and it belongs to that question.

## Consequences

- A note rewritten from outside now reaches the editor, and the user sees the
  new content instead of saving the old one back.
- A user who is mid-edit keeps their text, and the replacement does not land
  under their fingers.
- The rewrite contract is a **registry, not a convention**: the shape registry
  and the rewrite registry live next to each other, and the convention test
  that guards the shape guards the rewrite answer too.
- The sites that answer "the value itself" are recorded as an open question,
  not as a silent gap.
