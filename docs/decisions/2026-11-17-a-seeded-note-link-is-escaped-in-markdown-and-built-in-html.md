---
title: "A seeded note's link is escaped in the markdown and built in the html"
date: 2026-11-17
status: accepted
tags: [seed, notes, markdown, android]
---

# A seeded note's link is escaped in the markdown and built in the html

## Context

`SeedWriter.writeNoteLinkingToTask` writes a note whose body links to the
task the link named. The note's body is markdown; the editor renders it as
html. The link's label is a task title — arbitrary text, written by whoever
typed the title, not by the seeder.

The title was used verbatim: `"[$title]($url)"`. A title is not markdown, so
it can end the link early and leave something else in its place.

Measured on the richeditor's own markdown parser (2026-11-17):

| title | markdown body | the editor's html |
|---|---|---|
| `Buy milk` | `[Buy milk](task://abc)` | one link, text `Buy milk` |
| `Buy milk] (2)` | `[Buy milk] (2)](task://abc)` | **no link at all** — the body is plain text |
| `Buy milk\]` | `[Buy milk\]](task://abc)` | one link, text `Buy milk\` — the backslash is visible |

Only `]` breaks the link: parens, `*`, `` ` ``, `~`, `!`, `#`, `_`, `+`, `"`,
`<`, `>` and `&` do not, and an unbalanced `[` truncates the label but leaves
the link. Inline html is not an alternative — the parser drops an `<a href>`
written in markdown entirely.

The parser takes the link's label as **raw source**, so a CommonMark-correct
escape shows the escape to the reader: `\]` is rendered as `Buy milk\]`.

Before the note's body became markdown, the link was written as html with an
escaped title, and the note showed the title as it was typed.

## Idea

Two representations, each correct for what it is for: the markdown escapes
the label so the *structure* of the link is the seeder's and not the title's,
and the html carries the clean title as the link's *text*, so the reader sees
the title and not its escaping.

Neither of them is a markdown parser: the escaping is a fixed set of three
characters, and the html is one anchor.

## Decision

**The markdown label escapes `\`, `[` and `]`** — the characters that can end
the label or start one. Nothing else is escaped: the rest does not change
the link's structure.

**The html is built by hand**, in the shape the editor itself writes:
`<p><a href="URL" target="_blank">TITLE</a></p>`, with the title html-escaped
(`&`, `<`, `>`, `"`).

**The note's html is the html of its body plus the link**, and the link's
html is the one above. When the note has no body, the html is the link alone.

## Rationale

The escaping was chosen by measurement, not by reading the CommonMark
grammar: `]` is the only character that destroys the link, and `[` is the
only one that changes the label's extent. Escaping the rest would have made
titles like `Report (Q3)` unreadable for no gain.

The hand-built anchor is what the editor writes when a person types a link in
the note (`RichTextStateHtmlParser` reads `target="_blank"`), so a seeded
note is one the editor could have produced — the same argument the seed
documentation already makes for the `task://` href.

The clean title in the html is what the reader sees, and it is what the note
showed before the body became markdown.

## Consequences

- A title with markdown punctuation cannot change the generated link: the
  link's structure is fixed by the seeder, and the title is text.
- The note's html is no longer always `toHtml(bodyMarkdown)`. For the seeded
  link it is the html of the body plus a hand-built anchor. A test holds that
  shape — one link, the expected href, the title as the link's text.
- The outgoing-link machinery is unaffected: it reads the html's `href`, and
  the anchor carries the same `task://` href the markdown had.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedWriter.kt` —
  the link's markdown and html
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/domain/NoteContentMapper.kt` —
  the note's markdown and html
- `shared/src/commonTest/kotlin/com/singularity/todo/core/seed/SeedWriterTest.kt` —
  the title that cannot break the link
