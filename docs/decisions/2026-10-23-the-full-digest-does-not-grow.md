---
title: "The full digest does not grow with the corpus"
date: 2026-10-23
status: accepted
tags: [docs, tooling, process, corpus]
---

# The full digest does not grow with the corpus

## Context

`docs/decisions/DIGEST.md` was split in two on 2026-10-15
(`2026-10-15-digest-orientation-layer`): the always-loaded orientation layer became
bounded by *kind* — a fixed number of recent decisions, one generated tree of `docs/`,
one wrapped line of tags — while `DIGEST-full.md` kept the old body as the complete
index, with the caps relaxed because the reader opens it deliberately.

The split bounded the file every session pays for. It did not bound the file that is
opened on demand. The index still had the shape the corpus gave it. Measured on the
corpus of 814 decisions, the pre-fold generator produced **2235 lines**, and the
sections were:

- *Active entries* — one line per decision (603 lines),
- *Per-tag* — one section per tag, each holding consequence bullets copied out of the
  ADR bodies (1479 lines over 102 tag sections),
- *Critical* — one line per absolute rule (105 lines).

That is **2.75 lines per decision** — 2.71 beyond the 30 lines the empty file already
costs. The budget was 3000, and the corpus took 222 decisions in the twelve days from
2026-10-12 to 2026-10-23, about 18 a day: the 765 lines left before the budget was
reached were **283 decisions, about two weeks of corpus**, away.

That number is not a ceiling on anything the generator does. It is a countdown to the
next ADR. Every decision costs the index two or three lines whether or not anything
about the decision deserves two or three lines.

A fixed line ceiling on an exhaustive index is a wall by construction. An index of N
things has N entries; no fixed number of lines is honest for it. The only two answers
available are the ones the file already went through — trim content, or raise the
number again — and the script's own comment records four rounds of the first:

    MAX_BULLETS_PER_ADR 6->2, MAX_ITEMS_PER_TAG 10->9, a ~385-line duplicate index
    dropped, MAX_ACTIVE_ENTRIES capped.

`2026-10-15-digest-orientation-layer` put the same sentence in the ADR that the
generator quotes: *a budget the growth always reaches is a wall, not a ratchet.* The
orientation layer stopped being that wall by being bounded by kind. The on-demand index
was left as it was.

## Idea

- **A.** Raise the budget again and keep trimming.
- **B.** Trim harder: lower the caps, drop more content.
- **C.** Fold: an entry becomes a *record*, not a row. The file grows in characters
  instead of lines, and the budget becomes a statement about what one decision may
  cost rather than a statement about how many decisions may exist.
- **D.** Drop the exhaustive index and point at the corpus directory.

## Decision

**C.**

The index is folded, the same way the orientation layer was folded:

- **Every decision appears exactly once**, in a packed index, as
  `` `slug` — Title #tag1 #tag2 ``. Nothing is capped and nothing is dropped: of the
  814 decisions this was measured on, 788 are active, 16 open, 11 deferred and 26
  superseded — all are named.
- **The per-tag consequence sections are gone.** Consequences are body text; the file
  carried a second copy of them, once per tag the decision carried. The index now says
  how to reach the text instead of holding it: `grep -rl 'phrase' docs/decisions/`.
- **The absolute rules keep a readable head of 60**; the rest are packed into a
  paragraph. The rules are still in the file and `grep` still finds them — the readable
  list is what cannot grow with the corpus.
- **The Critical section takes its rules only from decisions in force.** The corpus's
  own rule (`2026-09-26-adr-supersede-process`) says the rules of a replaced decision
  are not duplicated there, and the filter that enforces it now reads the same
  three-way notion of "replaced" that the Status section reads — not only the
  `supersedes:` of another decision. A decision that says of itself that it was
  replaced (`status: superseded`, `superseded-by:`) no longer gets its rules back into
  the section through the other door.
- **The four trimming knobs are gone** — `MAX_ITEMS_PER_TAG` (20),
  `MAX_BULLETS_PER_ADR` (6), `MAX_OPEN_DEFERRED` (25) and `MAX_ACTIVE_ENTRIES` (600).
  Each answered "what do we drop when the corpus grows?" by dropping content. A cap is
  a wall; the fold is the alternative.

`D` was rejected on what the index is for. The corpus directory answers "which files
exist" — a reader has to open 814 frontmatters to learn which decisions carry a tag.
The index answers "which decision covers this" in one grep, and that is worth keeping
in a form that is cheap to read.

### The title of a decision is the frontmatter's, or the body's heading

28 of the 814 decisions carry no `title:` in their frontmatter. The index promised
three ways to find a decision — by slug, by title, by tag — and for those 28 the second
one was empty: the ADR had a title in its own body heading and nowhere else. The
generator now falls back to the first heading of the body, which is where a reader
would look for it. A decision with neither is marked `_(no title)_` rather than left
blank.

### A decision that was replaced is not a decision that still stands

The corpus says in three ways that a decision was replaced, and the digest believes
all three:

| who says it | where |
|---|---|
| the replaced decision | its own `status: superseded` |
| the replaced decision | its own `superseded-by:` (or `superseded_by:`) |
| the replacing decision | its `supersedes:` |

`2026-09-26-adr-supersede-process` is the corpus's own rule: the replaced decision
carries `status: superseded` and `superseded-by:`, and the replacing one carries
`supersedes:`. Reading only the third edge means reading one side of a rule that has
two sides. 19 of the 814 decisions carry the reverse edge, and 8 of them are named
by no `supersedes:` at all — those 8 were shown in the index as if they were still in
force, and were in no status group at all.

**The priority is the replacement.** A decision that any of the three declarations
names as replaced is superseded, whatever its own status says: a decision whose
frontmatter still says `deferred` is not pending any more, and one that says
`accepted` is not in force either. The groups are exclusive — a decision is in one of
them, never in two — and the superseded group wins over open and deferred.

The counts on that corpus are 16 open, 11 deferred and 26 superseded, and the 26 are
the 18 the `supersedes:` edges named, plus 8 the decisions said of themselves. The
superseded decisions are not listed in the index of the decisions in force; the index
carries 788 of the 814, and the Status section names the other 26.

## Rationale

The cost of a decision in the index is now a record rather than a row. A record costs
~135 characters (measured on the corpus of 814 decisions); a row cost a line. The index
section grew from 72 lines at 400 decisions to 153 at 814 — **0.196 lines per
decision**. The pre-fold file cost **2.75** lines per decision; the folded file costs
0.289 over the whole corpus and 0.20 over the last 50 decisions.

### The budget is a line above the whole history, not above one point

A budget fitted to the corpus of today is a wall that happens to be far away: the file
of six months ago is *smaller* than today's, so a budget that only fits today fits a
history it was never measured against. The corpus is a series — the file as it stood at
every earlier date is the corpus with the newest decisions left out — and the budget
has to lie above the whole series.

The measurement (2026-10-24, the prefix series rendered and measured again — the
corpus moved, and a moved corpus is re-measured, not re-read):

| what | measured |
|---|---|
| the last 50 decisions | 0.180 lines each |
| the last 100 decisions | 0.180 lines each |
| the last 200 decisions | 0.220 lines each |
| the whole corpus | 0.287 lines each |
| the index section alone, the worst window of 50 in the history | 0.240 lines each |
| the whole file, the worst window of 50 in the history, at N=80 | 0.980 lines each |

The last two rows are two different windows, and the difference is the point. The
worst window of 50 in the **index section** is 0.240 lines each — under the ceiling.
The worst window of 50 in the **whole file** is 0.980 lines each: the file grew
faster than the ceiling in that part of its history, while the capped sections were
still filling up, and the intercept is what covers that ramp. That is why the ceiling
alone would not have been a budget.

and the two constants are read together:

| Constant | Value | What it states |
|---|---|---|
| `DIGEST_FULL_LINES_PER_DECISION` | 0.30 | The ceiling on what one decision may cost the whole file — above every measured rate above. |
| `DIGEST_FULL_FIXED_LINES` | 81 | What the file may spend on everything that does not scale with the corpus: the covering of the whole prefix series, `max over every prefix N of (file(N) − 0.30·N) = 80.0` at N=130, and the constant is the smallest integer strictly above it — so the file keeps a line of margin and never sits on the budget. |

The effective budget is therefore:

    budget(N) = 81 + ceil(0.30 * N)

The intercept is not the size of the empty file (30 lines). The file grew faster than
the ceiling while the capped sections were still filling up — up to 0.98 lines per
decision over a window of 50 decisions, at N≈80, where the readable head of the
absolute rules was filling — and the intercept is what covers that ramp. The tightest
point of the whole series is N=130, where the file comes within one line of the line;
the margin is 20 lines at N=100, 29 at N=400, 49 at N=700 and 61 at N=814.

The series is measured at every prefix of it, not at every tenth. A sample taken every
ten decisions missed the tightest point — N=129 is not a multiple of ten — and reported
a covering of 80.0 instead of 80.3, which is a constant of 80 rather than 81: a whole
line too low, and a test that would have passed it. (The covering is 80.0 today, at
N=130, because the file changed: the decisions that were replaced left the index and
moved to the Status section, and the file is one line shorter for it.)

The budget is monotone in N and grows with it: there is no floor under it and no
ceiling over it that the corpus alone can reach. The corpus's own growth is slower than
the rate at which the budget grows — the index grows by 0.196 lines per decision while
the budget grows by 0.30 — so the margin widens instead of closing. What the file can
run into is the rate: growing faster than the fold allows is a finding about the
generator, not about the corpus.

The budget has **one owner**: the generator. `sgate decisions_digest_within_budget`
reads it out of the line the generator prints (`DIGEST-full.md is N lines (budget: M)`)
and holds no second copy of it — deliberately, since a second copy is how the two
drifted apart before. The printed budget and the applied budget come from the same
function, so the number the gate reads and the number the generator enforces cannot
drift apart; a test holds them together.

## What was given up

- **The per-tag consequence text is no longer in the digest.** A reader who wanted "what
  did we decide about testing" read a tag section; now they grep the corpus. Accepted:
  the text was a copy of the ADR bodies, and the copy was what made the file grow. The
  digest maps; the corpus holds the text.
- **Older absolute rules are packed rather than listed.** Accepted for the same reason:
  the rule is in the file and `grep` finds it, but only the newest 60 stay a list.
- **The index has long lines** — up to 800 characters, about five entries each. Markdown
  in this repository is not manually wrapped (`.editorconfig` sets `max_line_length` for
  `kt`/`kts` only), and the alternative to a wide line is a line per decision.
- **The orientation layer's tag line changed.** It named 101 tags before and names 120
  now: **31 of the tags it used to name are no longer in it, and 50 it did not name are
  now in it.** The 101 were an accident of the six-bullets-per-decision cap — the cap
  kept the alphabetically first bullets of each decision, so the tags that survived were
  the ones that happened to sort early — and the 120 are the most-used ones. The layer
  reads in every session, so it names the topics of the corpus rather than the tags of
  one decision; the complete vocabulary is in the index, and the layer says so.
  `DIGEST.md` went from 92 to 95 lines, and nothing else in it changed.

## Consequences

- `DIGEST-full.md` is 265 lines instead of 2235, and the budget is a line above the
  history rather than a countdown.
- The orientation layer is 95 lines against its own constant ceiling of 120, and its
  size does not depend on the corpus: the renderer takes the decisions it draws from as
  an argument, so the property can be measured on an artificial corpus instead of
  assumed.
- `scripts/tests/test_refresh_decisions_digest.py` grew the checks this decision is
  made of. They read the expectation out of the ADR files rather than out of the
  generator, so they check the property and not the code: every decision is findable by
  slug, by title and by tag; every absolute rule is in the file; every prefix of the
  corpus fits the budget; the marginal cost of a decision stays under the ceiling; the
  printed budget is the applied budget; repeated text is folded rather than repeated;
  the file does not grow by a line per decision; and a file over budget fails loudly
  instead of quietly. The Status section is checked as well: every decision it names is
  a decision of the corpus, every decision is named in exactly one of its groups, and
  every decision that says of itself that it was replaced is listed as superseded —
  whatever its own frontmatter says, and whatever the replacing file says.
- A review of this decision found four blockers — a budget fitted to one point, a wall
  under the budget, decisions whose title was nowhere, and a renderer that ignored the
  corpus it was given. They are fixed in the same change, and the verdict file records
  the round. Three further rounds followed: the second found the budget fitted to one
  point again and a constant that was a line too low, the third found stale numbers and
  a Status section that named a decision outside the corpus and named another one
  twice, the fourth found the reverse edge of the supersede relation unread, so that
  decisions that were replaced were shown as if they still stood. All of them are fixed
  in the same change, and the verdict file records those rounds too.
- The index is generated, not authored. A decision that wants to be found by a phrase
  that is in neither its title nor its tags is found by grepping the corpus, which the
  file says in its first section.

## Links

- `2026-10-15-digest-orientation-layer` — the split that bounded the layer every
  session reads, and the sentence this decision quotes
- `scripts/refresh-decisions-digest.py` — the generator, its constants and the fold
- `scripts/tests/test_refresh_decisions_digest.py` — the checks
- `docs/doc-maintenance.md` — how documents are written and sized
