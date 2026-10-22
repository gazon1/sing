---
title: "The regexp filter stays on LIKE, because it is a filter and not a search"
date: 2026-10-22
status: accepted
tags: [search, database, filter, product]
---

# The regexp filter stays on LIKE, because it is a filter and not a search

## Context

The search moved to the full-text index (ADR 2026-10-21). The index stores
*words*, so it finds the beginning of a word and nothing else: "arch" finds
"architecture", and "port" does not find "airport". The rule of the search
follows the index (F2a of the grammar), so that the index and the in-memory
rule cannot answer the same question differently.

One text scan was left behind. `TaskFilter.ByRegexp` still answers with
`lower(title) LIKE lower('%' || :pattern || '%')` — the title *contains* the
pattern, anywhere in it. It is used by the agenda's saved selectors, where a
person saves a filter of the shape "the title carries this text".

Issue #887 asks for the reason to be written down, so that the next reader
does not read the leftover `LIKE` as an unfinished job and "fix" it.

## Decision

**`TaskFilter.ByRegexp` stays on `LIKE`.** It is a filter over tasks, not the
search, and its semantics are the semantics of a filter: the pattern occurs
in the title, anywhere.

The index cannot answer that question. It stores words; a pattern that occurs
in the middle of a word is not a word lookup. Moving the filter onto the
index would mean changing what a saved selector matches — a selector saved as
"port" would stop finding "Airport transfer" — and that is a change of the
product's behaviour, not a refactoring of its implementation. It is a
decision somebody has to take on purpose, with the old answers in view.

The `LIKE` is therefore not a leftover of the migration. It is the one place
where the product asks the substring question on purpose.

## Consequences

- The saved agenda selectors keep matching the titles they matched before the
  index, and the word index is not asked a question it cannot answer.
- The one text scan that is left is a scan of the tasks the selector narrows
  to, and it is bounded by the profile the person is in.
- The contains mode of the search asks the same question — "does the text
  carry the term *somewhere*" — and it, too, is answered by reading the rows
  rather than by the index. The two are the same question asked of two
  different sets of records, and both are honest about it (ADR 2026-10-22,
  the substring mode).
- If the filter is ever migrated to the index, the change of semantics has to
  be a decision of its own, stated in the change that makes it — the same way
  the change of the search's semantics was stated (F2a).

## Links

- ADR 2026-10-21: the search asks the index — `docs/decisions/2026-10-21-the-search-asks-the-index.md`
- ADR 2026-10-22: the substring search is a mode the surface states — `docs/decisions/2026-10-22-the-substring-search-is-a-mode-the-surface-states.md`
- The filter — `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/Task.kt`
- The query — `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Daos.kt` (`watchByRegexp`)
- The grammar of the free text — `docs/specs/search-query-grammar.md`
- Issue — [#887](https://github.com/gazon1/sing/issues/887)
