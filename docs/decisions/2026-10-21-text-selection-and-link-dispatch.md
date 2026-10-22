---
title: Text that a person reads is selectable, and the links in it are the app's to open
date: 2026-10-21
adr-number: 2026-10-21-text-selection-and-link-dispatch
status: accepted
tags: [ui, text, links, compose]
---

# Text that a person reads is selectable, and the links in it are the app's to open

## Context

Two things were missing from the app, and they looked like one thing.

**Text could not be copied.** An error message from the calendar sync — the one a
person reads out to support — could be selected only by the system's own gesture
on a bare `Text`, and on Desktop there was no gesture at all. A backup id, a
model id, a project name, the title of a note that links back to a task: none of
them could be taken anywhere.

**Links could not be tapped.** The assistant's answer is prose, and prose is
where links turn up. A documentation link in an answer was a line of text. When
a note's body carried a `note://` link, the tap was intercepted by a handler
written inside the note preview's own file — one of four places that knew what a
link was.

The second problem had a cause worth naming: the app had **four grammars** for
the same idea of an internal link.

| Grammar | What it read | What it returned |
|---|---|---|
| `feature/notes/OutgoingLinksExtractor.kt` | the editor's HTML anchors | a link reference |
| `feature/notes/domain/logic/OutgoingLinksExtractor.kt` | the same HTML anchors | a *different* link reference |
| `feature/tasks/data/TaskOutgoingLinks.kt` | free text | a list of URLs |
| the SQL in `Daos.kt` and `Migration25To26Runner.kt` | the stored column | rows |

The two extractors were near-identical copies that differed in the type they
returned. The SQL spelled the grammar a fourth way — as a substring of a JSON
blob, with the identifier interpolated straight into a `LIKE` pattern, and with
the scheme's length written into the migration as the number `9`. And the one
place that *acted* on a link — the tap handler — had no test at all.

## Idea

- One owner for the vocabulary: the schemes the app owns and the shape of an
  identifier, in `core/links`, read by everything that touches a link.
- Two widgets, named for what they do: `SelectableText` for text a person can
  copy, `LinkifiedText` for text whose links can be tapped.
- One handler for every address: internal links stay in the app, everything else
  goes out to the platform.
- The navigation through a composition local, so a screen that shows a link does
  not have to know how to navigate.

## Decision

**One owner for the internal link vocabulary.** `core/links/InternalLinks.kt` owns
the schemes (`note`, `task`), the identifier charset, and how a string is split
into the two. `InternalLinks.parse` reads a link; `InternalLinks.findIn` finds
the links in a piece of text; `InternalLinks.urlFor` builds one. The two
`LinkRef` types are gone; `InternalLinkTarget` is the app's single link
reference. The notes' `LinkSchemes.kt` — which used to own the vocabulary — is
deleted rather than turned into a re-export: a second name for the same fact is
a second copy of it, and a copy nobody reads is the copy that rots.

**Two widgets, named for the behaviour.** `SelectableText` is one text in a
selection container. `LinkifiedText` is one text whose links are tappable, in a
selection container too — a person who wants to copy a sentence that contains a
link should not have to give up the link to do it. `SelectableTextGroup` is a
block of text selectable as one; `UnselectableContent` takes an interactive
child out of a block's selection, so a long press meant for a button does not
start a selection instead.

**One handler for every address.** `rememberAppUriHandler` wraps the platform's
`UriHandler`: an address the app owns is handed to the app's own handler, and
everything else is handed to the platform. The app-wide handler comes from
`LocalInternalLinkHandler`, which the app's root provides — so a screen that
shows a link does not have to know how to navigate. The note preview does *not*
use it: a note links to another note, and moving between the two inside the
notes graph is that screen's business, so it passes its own callback.

**A registry, and a test that reads it.** The inventory table in the skill
`singularity-todo-selectable-text` says, per file, what its texts have:
`selectable`, `linkified`, or `excluded` with a reason. `SelectableTextConventionTest`
reads that table and holds it against the code — a file that uses a widget and
is not in the table fails the build, and a row that does not match the code
fails too. A second, advisory gate finds the prose that no row covers and prints
it.

**What is excluded, and why.** A row whose long press already opens a menu
cannot also start a text selection: the gesture is spoken for. The task card,
the note card, the agenda rows and the archive rows are excluded, each with the
reason written down — not because selection would be wrong there, but because
the gesture is already taken.

## Rationale

**Why one owner for the vocabulary.** The four grammars were written where they
were needed, and none was written with the others in view. The two copies of
the extractor differ in behaviour, not only in the type they return: the
markup reader sees only links the editor turned into anchors, while the free
text reader also sees a URL typed as plain text. So a person who pasted a link
into a task got an outgoing link, and the same person who selected the text in
the rich editor and made it a link got a different answer — and the note and
the task, which store the same kind of column, disagreed about whether they
agreed.

The SQL could not simply be deleted: backlinks are a query, "which notes link
to this task", over a JSON array of URL strings. The point is not to remove that
grammar but to make it *derived* from the one grammar instead of a second
spelling of it — and to stop the identifier from being interpolated into a
pattern unescaped. It still is, and that is recorded as its own task
(`one-grammar-for-internal-links`), because the honest fix is a
separate column and that is a change to the storage format.

**Why the widgets are named for behaviour.** `SelectableText` does not know how
to make links and does not try; `LinkifiedText` does not exist to be selected
but happens to be. A name that says what the widget does is a name a caller
does not have to look up.

**Why a selection *group* exists at all.** A selection container owns a
selection state. One around every row of a list gives every row its own
selection manager, and the selection does not survive the row scrolling out of
view. One around the block gives the block one manager, which is what a person
means by "copy this paragraph".

**Why navigation goes through a composition local.** The alternative is a
parameter — every screen that shows a link takes a callback and threads it
down. The local removes the threading; the cost is that the link leaves the
screen through an ambient value rather than through an argument. The default
value is a no-op, so a preview, a test, or a screen that was never given a
handler cannot crash.

**Why the registry is written down rather than derived.** It could be computed
by scanning the sources — that is what the test's second check does. It is
written out anyway because *the act of writing it down* is the point: a
classification nobody wrote down is a classification nobody made. A derived
table would make every text in the app "classified" the moment it was written,
which is the state the exclusion rule is about.

**Why the advisory gate is advisory and not blocking.** It has to guess what
"prose" means from the text of a call, and a guess is wrong in more ways than a
comparison is: a literal that reads like a sentence may be a label, a format
string, a placeholder. It reports and passes. The blocking half is the registry
test, which compares two exact facts.

## Consequences

- `core/links/InternalLinks.kt` is the only place that knows the app's internal
  schemes. A new scheme is a new constant there and a new matcher beside it.
- The note preview's body is selectable, and its links behave exactly as before:
  the interception moved into `rememberAppUriHandler` with the screen's own
  callback, so a note still opens a note inside the notes graph.
- The assistant's answer and a generated screen's text are linkified. A `note://`
  or `task://` link in an answer opens the note or the task **inside the app**,
  and a web address leaves it.
- The inventory table is a document that can go stale, and the test that reads it
  is what says so. A new text surface is a new row.
- The advisory gate's list is not empty today: it names 37 surfaces that render
  prose and are not classified. Some of them are labels that do not need to be
  selectable; some of them are the next surfaces to convert. The list is the
  work list.
- The strict reading of a link is now stricter than it was: an identifier that
  contains anything outside the shared charset is not an identifier. A stored
  link with a space in it is no longer read as a link.

## Links

- ADR `2026-10-21-deep-link-registry` — the deep-link vocabulary, which this one
  does not touch: a deep link addresses a *screen*, an internal link addresses an
  *entity*.
- ADR `2026-10-02-note-entity-dual-task-linkage` — why a note records both a
  structural task link and a `task://` token.
- OpenSpec change `openspec/changes/text-selection-and-links/`.
- Skill `.agents/skills/singularity-todo-selectable-text/SKILL.md` — the
  inventory and the rules.
- `docs/decisions/2026-10-21-nav3-1-2-0-upgrade.md` — the navigation version the
  link dispatch sits on.
