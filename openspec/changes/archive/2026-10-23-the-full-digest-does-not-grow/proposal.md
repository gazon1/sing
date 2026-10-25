# The full digest does not grow with the corpus

Status: **open** · Issues: — · ADRs: `docs/decisions/2026-10-23-the-full-digest-does-not-grow.md`

## What

The complete decision index stops growing with the corpus.

`docs/decisions/DIGEST-full.md` was the one artifact of the decision log that was
still shaped by the corpus. Every decision had a line of its own, every tag had a
section of its own, and every consequence bullet was copied into each section the
decision carried. Measured on the corpus of 814 decisions, the generator produced
2235 lines — 2.75 lines per decision, or 2.71 beyond the 30 lines the empty file
already costs — against a budget of 3000, so the budget was about two weeks of
corpus away at the measured rate of 18 decisions a day.

The index is folded: an entry becomes a record — slug, title, tags — packed into
paragraphs of a fixed width. The consequence text stays where it was written, in the
ADR bodies, and the index says how to grep for it. The budget becomes a line above
the whole history of the file — the fixed part plus what one decision is allowed to
cost it — and it has to fit the file as it stood at every earlier date, not only the
file of today.

The always-loaded orientation layer keeps its own job and gets its own rule: it names
the most-used tags rather than an accidental subset of them, and it says where the
complete vocabulary is.

## Why

An index of N decisions has N entries. No fixed number of lines is honest for it, so
a fixed ceiling is reached by the corpus alone — and the two answers left are the two
the file has already been through: trim content, or raise the number again. The
generator's own comment records four rounds of trimming, and the ADR that split the
digest in two says it plainly: *a budget the growth always reaches is a wall, not a
ratchet.*

The orientation layer stopped being that wall by being bounded by kind — a fixed
number of recent decisions, one generated tree, one wrapped line of tags. Nothing in
it is one-per-decision, so a new decision cannot push it over its ceiling. The
on-demand index kept the old shape, and the split had moved the treadmill rather than
removed it.

A reader who opens the index on purpose wants to find a decision, and the cheapest
way to be found is a record that carries the three things a reader searches by: the
slug, the title and the tags. Twenty-eight decisions of the corpus carry no title in
their frontmatter, and the index promised a way to find them by title that did not
exist.

## How it is checked

- `scripts/refresh-decisions-digest.py` renders the index from the corpus and prints
  the budget it measured against; `sgate decisions_digest_within_budget` reads that
  number out of the printed line, so the budget has one owner.
- `scripts/tests/test_refresh_decisions_digest.py` checks the property rather than
  the code: the expectations are read out of the ADR files, not out of the generator,
  so a decision that stopped being findable fails the test instead of confirming it.
  The rate and the fixed part are measured, and the budget is checked against the
  measured file.

## Out of scope

- The text of the consequences is not carried by the index any more. It was a second
  copy of the ADR bodies, and the copy is what made the file grow.
- The budget of the always-loaded orientation layer is unchanged: that layer is
  bounded by kind, and a fixed ceiling is honest for it.
