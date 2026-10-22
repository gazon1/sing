# Tasks — one grammar for internal links

**Status:** implemented · **Priority:** medium — a latent correctness problem today, and a
four-way duplication tomorrow.

## The decision first

- [x] Decide where the grammar lives: one owner for markup and free text, or one owner
      per surface sharing a scheme table — **one owner**, `core/links/InternalLinks.kt`.
      It names the schemes, the identifier rule, the parser and the builder; both
      extractors and every SQL that needs a URL read from it
- [x] Decide whether the stored column keeps carrying the URL, or grows a separate
      column that can be matched exactly — the column keeps the URL; the *match* became
      exact instead (`json_each` + `=`, not `LIKE`)
- [x] Record the decision as an ADR — `2026-09-25-task-backlinks-design.md` gets the
      addendum "the match is exact, and the URL comes from the scheme table"

## The copies

- [x] The two rich-text extractors become one — the notes extractor is the only one
      that reads markup; the task one reads free text and says so
- [x] The two reference types become one — `InternalLinkTarget` is the app's single
      link-reference type
- [x] The two copies of the URL parser become one — `InternalLinks.parse` is the only
      parser

## The free-text grammar

- [x] Free-text link detection is either used by notes as well, or explicitly scoped to
      tasks — and the scope is written down — it is scoped to tasks, and the extractor
      says so: a task description is plain text, a note body is markup

## The SQL grammar

- [x] The identifier is not interpolated into a match pattern unescaped — the pattern is
      gone; the comparison is `json_each(value) = :url`
- [x] The stored prefix is derived from the same scheme table the rest of the app uses —
      the URL is built by `InternalLinks.urlFor`
- [x] The migration's hard-coded prefix length is derived, not written as a number — the
      44→45 migration derives the prefix, its length and its character class from
      `InternalLinkSchemes`

## The click path

- [x] Tapping a link in a note preview opens the referenced note or task
- [x] Tapping a link to something that was deleted degrades instead of failing — the
      task screen shows "Not found"; a deleted saved view shows "View not found" and
      goes back
- [x] The click path has a test — it is the only one of the four grammars that acts —
      `Maestro/flows/notes/03-internal-link-navigation.yaml` taps a link in a note and
      asserts the task it names

## The data that was never written

- [x] A note that links to a task carries that link in `notes.task_id` — the foreign key
      arrived with the migration that added it, but nothing ever filled it: the
      backfill that was supposed to ran from a runner that was never wired into the
      migration chain. The 44→45 migration does the backfill, and it drops the links
      the grammar does not accept

## Explicitly not doing

- Changing what a link looks like to the user
- Changing the stored column's format
- Touching the deep-link registry or the deep-link URI grammar — a different vocabulary
