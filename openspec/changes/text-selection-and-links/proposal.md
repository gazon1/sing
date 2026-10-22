# Text that a person reads is selectable, and the links in it are the app's to open

Status: **accepted**
Tags: **ui text links compose**

Related: ADR `2026-10-21-text-selection-and-link-dispatch`

## What

Two things were missing from the app, and they looked like one thing.

**Text could not be copied.** An error message from the calendar sync — the one a
person reads out to support — could not be selected. A backup id, a model id, a
project name, the title of a note that links back to a task: none of them could be
taken anywhere.

**Links could not be tapped.** The assistant's answer is prose, and prose is where
links turn up. A documentation link in an answer was a line of text. When a note's
body carried a `note://` link, the tap was intercepted by a handler written inside
the note preview's own file — one of four places that knew what a link was.

The second problem had a cause worth naming: the app had **four grammars** for the
same idea of an internal link, and the two that were supposed to agree — the two
extractors — differed in the type they returned and in what they recognised.

## Why it matters

The four grammars were written where they were needed, and none was written with the
others in view. The copy that differs in behaviour is the one nobody compares,
because comparing it is not a step in any workflow. And the one grammar that
*acts* — the tap handler — had no test at all, which is how a handler that
swallows the tap survives.

## The change

- **One owner for the vocabulary.** `core/links/InternalLinks.kt` owns the schemes
  the app owns and the shape of an identifier. Everything else reads them from
  there. The two identical link-reference types are gone.
- **Two widgets, named for what they do.** `SelectableText` for text a person can
  copy; `LinkifiedText` for text whose links can be tapped. A selection *group* for
  a block of text, and an exclusion for the interactive child inside it.
- **One handler for every address.** Internal links stay in the app, everything else
  goes out to the platform. The app-wide handler comes from a composition local, so
  a screen that shows a link does not have to know how to navigate.
- **A registry, and a test that reads it.** The inventory table in the skill
  `singularity-todo-selectable-text` says, per file, what its texts have. A test
  holds that table against the code, and a second, advisory gate finds the prose
  that no row covers and prints it.

## What is not in this change

- **The deep-link vocabulary.** A deep link addresses a *screen*; an internal link
  addresses an *entity*. They are two vocabularies and this change does not merge
  them — see ADR `2026-10-21-deep-link-registry`.
- **Text fields.** A field's text belongs to the field; the text-field rules apply
  unchanged.
- **The SQL that finds backlinks.** It stays a query over the stored column, but the
  identifier is still interpolated into a match pattern unescaped. That is recorded
  as its own task (`one-grammar-for-internal-links`), because the honest
  fix is a separate column and that is a change to the storage format.
