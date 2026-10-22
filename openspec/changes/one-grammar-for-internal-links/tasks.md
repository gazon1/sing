# Tasks — one grammar for internal links

**Status:** deferred · **Priority:** medium — a latent correctness problem today, and a
four-way duplication tomorrow.

## The decision first

- [ ] Decide where the grammar lives: one owner for markup and free text, or one owner
      per surface sharing a scheme table
- [ ] Decide whether the stored column keeps carrying the URL, or grows a separate
      column that can be matched exactly
- [ ] Record the decision as an ADR

## The copies

- [ ] The two rich-text extractors become one
- [ ] The two reference types become one
- [ ] The two copies of the URL parser become one

## The free-text grammar

- [ ] Free-text link detection is either used by notes as well, or explicitly scoped to
      tasks — and the scope is written down

## The SQL grammar

- [ ] The identifier is not interpolated into a match pattern unescaped
- [ ] The stored prefix is derived from the same scheme table the rest of the app uses
- [ ] The migration's hard-coded prefix length is derived, not written as a number

## The click path

- [ ] Tapping a link in a note preview opens the referenced note or task
- [ ] Tapping a link to something that was deleted degrades instead of failing
- [ ] The click path has a test — it is the only one of the four grammars that acts

## Explicitly not doing

- Changing what a link looks like to the user
- Changing the stored column's format
- Touching the deep-link registry or the deep-link URI grammar — a different vocabulary
