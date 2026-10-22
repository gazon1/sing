# One grammar for internal links

Status: **deferred**
Tags: **notes links internal-links**

Related: ADR `2026-10-21-deep-link-registry`

## What

The app has one notion of an internal link — a reference from one note or task to
another, written as `kind://id` — and it has four different grammars for it.

| Grammar | What it reads | Result type |
|---|---|---|
| the rich-text extractor | an editor's HTML anchor | a link reference |
| the note-body extractor | the same editor's HTML anchor | a link reference |
| the plain-text extractor | free text a user typed | a list of URLs |
| the SQL queries | the stored link column | rows |

The two rich-text extractors are near-identical copies that produce two different
reference types for the same input. The plain-text extractor matches a third way and
produces URLs instead of references. The SQL matches the grammar a fourth way — as a
substring of a JSON blob, with the identifier interpolated straight into a `LIKE`
pattern. The one place that *acts* on a link — the note preview, which intercepts taps
— parses the grammar by hand and has no test at all.

So the question "what does `task://abc` mean, and where does it point?" has four
answers, and the one that is actually executed on a tap is the one with no test.

## Why it matters

Two of these grammars disagree about something observable.

The rich-text extractor and the plain-text extractor disagree about **what counts as a
link**. The rich-text one only sees links the editor marked up as anchors. The
plain-text one sees the same URL written as bare text. A user who pastes a link into a
task description gets an outgoing link; the same user who selects the text in the rich
editor and makes it a link gets a different result — and the note and the task, which
store the same kind of column, disagree about whether they agree.

The SQL disagrees about **what an identifier is**. It matches the identifier as a
substring of the whole stored column, unescaped. Today the identifiers are UUIDs, so
the wildcards in the pattern happen not to bite. Nothing in the contract says
identifiers are UUIDs, and nothing escapes them.

## The fourth grammar is not optional

The SQL cannot simply be deleted. Backlinks are a query: "which notes link to this
task". The stored column is a JSON array of URL strings, and the query has to find
them. The point is not to remove that grammar but to make it *derived* from the one
grammar instead of a second spelling of it — and to stop the identifier from being
interpolated into a pattern unescaped.

## What is not in this change

- **No change to what a link looks like to the user.** `task://` and `note://` stay.
- **No change to the stored column's format.** It stays a JSON array of URL strings.
- **No change to the deep-link registry or to the deep-link URI grammar.** Those are a
  different vocabulary — they address a *screen*, not an entity — and are settled by
  ADR `2026-10-21-deep-link-registry`.
- **No change to the note editor's rendering.** The rich-text extractor stays where it
  is; it stops being a second copy.

## What has to be decided before implementing

- **One owner for the grammar, or one owner per surface?** A single owner that parses
  both markup and free text is the deepest module; two owners that share a scheme
  table is the shallower one. The choice depends on whether free-text link detection
  is wanted for notes too, or only for tasks.
- **Does the SQL keep matching the stored column, or does the stored column grow a
  separate indexed column?** The second is the honest fix for the escaping problem;
  the first is a smaller change with a narrower guarantee.
