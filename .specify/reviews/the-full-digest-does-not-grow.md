---
change: the-full-digest-does-not-grow
verdict: pass
date: 2026-10-24
pr: https://github.com/gazon1/sing/pull/1052
reviewed: f20dddadce26572bfbb8451d046aad7190043633
documents: sha256:46a9477b5d46bb336de8cbcccdf95aa01b04580010ad7b28bf09669e8d2fb4bb
---

## What was reviewed

The fold of the complete decision index, on the branch
`chore/the-full-digest-does-not-grow`:

- `scripts/refresh-decisions-digest.py` — the generator: the folded index, the
  budget and its constants, the renderer of the orientation layer.
- `scripts/tests/test_refresh_decisions_digest.py` — the checks the fold rests on.
- `docs/decisions/2026-10-23-the-full-digest-does-not-grow.md` — the decision record.
- The change's own proposal and spec delta.

The review was a second agent's, given the diff and the decision record rather than
the session that wrote it. It came back three times.

## What the first review found

**The fixed part of the budget was a guess, and the guess did not hold.** The
generator carried `DIGEST_FULL_FIXED_LINES = 120`; the measured fixed part was 30
lines. With the honest number the rate ceiling of 0.25 no longer held.

**The test that was supposed to catch it could not.** The test projected the size of a
larger corpus with the same constants the budget is built from and compared the
projection with the budget — the two numbers come from the same place, so the test
confirmed itself whatever the constants said.

**The budget had a floor under it.** `max(400, ...)` held the budget still while the
corpus grew, so the allowance the fixed part had shrank with every decision until the
file ran into it. That is a wall, and the decision record called the budget a ratchet.

**Twenty-eight decisions could not be found by their title.** The index promised that
a decision is findable by slug, by title and by tag; 28 of the 814 decisions carry no
`title:` in their frontmatter, and the index rendered them without one.

**The renderer of the orientation layer ignored the corpus it was given.**
`render_orientation` overwrote its `live_decisions` argument with the module's own
corpus, so the test that claimed "the layer does not grow with the corpus" measured
nothing.

## What the second review found

**The budget was fitted to one point.** `30 + ceil(0.30·N)` was green at N=814 and
red on the whole history behind it: at N=100 the file was 90 lines against a budget of
60. The prefix series — the corpus as it stood at every earlier date — is the property
that was missing, and the decision record claimed a growing margin that the
measurements did not show.

**The ceiling did not cover the cost of a decision.** The rate of the whole corpus is
0.290 lines per decision, above the 0.30 the budget allowed once the intercept was
honest. A ceiling below the measured rate is a ceiling the corpus reaches.

**The numbers in the decision record did not match the code.** The pre-fold file was
2235 lines, not 2234; its per-tag sections were 1479 lines, not 1372; and the corpus
of 2026-10-23 was 794 decisions, with 20 dated later.

**The printed budget was not the applied one.** The gate reads the budget out of the
printed line, and a printed budget that differs from the applied one makes the report
of the gate a lie.

**The summary of the tags was unguarded.** Removing the whole section left the suite
green, because the coverage test looked for the tags in the index entries.

**`first_heading` was fragile.** A `---` in the body was read as the end of the
frontmatter, so the heading after it was not found — 372 decisions of the corpus.

**The docstrings were in the wrong language.** The file was written in English before
the fold and in Russian after it.

## What the third review found

**The numbers in the code and in the proposal were stale, and two rates were written
as one.** The pre-fold file was 2235 lines, not 2234 — 2.75 lines per decision — and
the 2.71 was the rate *beyond* the 30 lines the empty file already costs. Both numbers
were in the generator and in the proposal, and neither said which was which.

**The property test could not fail.** The prefix series was sampled every ten
decisions, and the sample missed the tightest point of the history: at N=129 the
covering is 80.3, while the sample reported 80.0. An intercept of 80 — a whole line
too low — passed the test that was supposed to catch exactly that.

**The Status section named a decision that is not in the corpus.** The target of a
`supersedes:` that lives in `archive/` was listed with an empty title: a row that
nothing can be found from.

**The Status groups overlapped.** `2026-10-05-ci-checks-run-in-parallel` was counted
in the deferred group and in the superseded group at once — its own frontmatter says
`deferred`, and another decision replaced it.

**A docstring quoted a number that was not the measured one.** The test's own
docstring said "about 0.18": the index section measures 0.198 lines per decision and
the folded file 0.290.

**The corpus was read from the disk again.** `read_corpus_texts` took the texts it was
given and used only their paths — 815 prefixes of 815 files, about 660 thousand reads
of 4.5 megabytes.

## What the fourth review found

The third review came back with a pass and no blockers. It found one thing it called
medium and two small things.

**The reverse edge of the supersede relation was not read.** A decision says of itself
that it was replaced in three ways: its own `status: superseded`, its own
`superseded-by:` (or `superseded_by:`), and the `supersedes:` of the decision that
replaced it. The generator read the third and only the third, so five decisions that
carry `status: superseded` — `2026-09-18-vm-scope-cancellation-oncleared`,
`2026-09-29-archive-has-no-restore-ui`,
`2026-10-06-a-transient-failure-is-not-a-permanent-one`,
`2026-10-09-detekt-auto-correct-inert-multi-module` and
`2026-10-14-attach-account-supabase-semantic` — were listed in the index as decisions
in force and were in no status group at all. `2026-09-26-adr-supersede-process` is the
corpus's own rule, and it asks for both halves of the edge: the replaced decision
carries `status: superseded` and `superseded-by:`, the replacing one carries
`supersedes:`.

**The test carried the size of the corpus.** `test_the_printed_budget_is_the_applied_budget`
held the corpus size, and the line counts, in literals — numbers that go stale with the
corpus, and a stale literal is a test that checks nothing.

**The decision record's worst window could be read as the file's.** The record states
0.240 lines per decision for the worst window of 50 in the history; that is the window
in the *index section*. The worst window of 50 in the whole file is 0.980.

## What the fifth review found

The fifth review came back with a pass and no blockers. It found two things it called
medium and three small ones.

**The decision record mixed three different sets of numbers in one sentence.** The
record said that "19 of the corpus carry the reverse edge without one", but 19 is the
number of decisions that carry the reverse edge at all; the number the sentence was
about — the decisions whose replacing file was never given a `supersedes:` — is 8.
Measured on the corpus of 814 decisions: 19 carry `superseded-by:` (or
`superseded_by:`), 18 are named by the `supersedes:` of another decision, and the 8
that are named by neither were the ones the old generator showed as if they still
stood. The record now says 19 and 8, and the two are not the same set.

**The Critical section still filtered on the narrow notion of "replaced".** The rules
that reach the Critical section were taken from every decision except the targets of
another decision's `supersedes:` — so a decision that says of itself that it was
replaced (`status: superseded`, `superseded-by:`) still put its rules there. The
corpus's own rule (`2026-09-26-adr-supersede-process`) says the rules of a replaced
decision are not duplicated there. On the corpus of today the difference is invisible
— no rule of a replaced decision is quoted — but the notion of "replaced" was one
thing in three places and another in the fourth.

**A comment counted the wrong titles.** The comment above the slug pattern said that
14 titles carry backticks; 14 is the number of `title:` fields in frontmatter, and 19
is the number of rendered titles that carry them — five more come from the body.

**A literal of the corpus's size was left in a test.** The budget test that claims to
hold no size carried 814 in its list of sample sizes. The property did not depend on
it — the test compares the printed budget with the applied one for any corpus — but a
literal of today's corpus in a test that claims to have none is the kind of thing the
third review found, and it was removed.

**The record of the review counted its rounds differently from the decision record.**
This file called the last round "the third"; the decision record calls it "the
fourth". The heading here is fixed.

## What was done about it

**The budget is a line above the whole history.** The two constants are read
together, and both are measured over the prefix series:

- `DIGEST_FULL_LINES_PER_DECISION = 0.30` — above every measured rate: the last 50
  decisions 0.200, the last 100 0.190, the last 200 0.225, the whole corpus 0.289,
  and the worst window of 50 in the index section 0.240.
- `DIGEST_FULL_FIXED_LINES = 81` — the covering of the whole measured path,
  `max over every prefix N of (file(N) − 0.30·N) = 80.0` at N=130, and the constant is
  the smallest integer strictly above it, so the file keeps a line of margin and never
  sits on the budget. The tightest point of the history is N=130.

The budget has no floor and no fixed ceiling, so it is monotone and grows with the
corpus: `81 + ceil(0.30·N)`.

**The two rates are named separately.** The pre-fold file was 2235 lines: 2.75 lines
per decision, or 2.71 beyond the 30 lines the empty file already costs. The generator,
the proposal and the decision record now say which of the two is which.

**The series is measured at every prefix of it.** Not at every tenth: the sample
missed N=129, the tightest point of the history, and reported a covering of 80.0
instead of 80.3 — which is an intercept of 80 rather than 81, a whole line too low.
The suite now measures all 815 prefixes, and an intercept of 80 turns it red.

**The Status groups are exclusive and stay inside the corpus.** A decision somebody
replaced is superseded, whatever its own frontmatter says, and a decision that is not
in the corpus is not named at all. The counts are 16 open, 11 deferred and 18
superseded, and they add up.

**The corpus is read once.** The texts handed over are the texts that are read, and
the decisions are parsed once instead of once per prefix: the series went from
twenty-two seconds to four.

**The three declarations of a replacement are read.** A decision is superseded when it
says so itself — in its own `status: superseded` or in its own `superseded-by:` — or
when the decision that replaced it says so in `supersedes:`. The priority is the
replacement: the superseded group wins over open and deferred, and the groups stay
exclusive. The counts are 16 open, 11 deferred and **26** superseded — the 18 the
`supersedes:` edges named plus 8 the decisions said of themselves — and the index of
the decisions in force carries 788 of the 814.

**The test of the printed budget has no size in it.** It compares the printed budget
with the applied one for any corpus, not for the corpus of today.

**The decision record's numbers are measured, and a test measures them.** The record
states the corpus it measured on, and the rates in its table are measured on that
corpus — the first N decisions of the series, which does not move when the corpus
grows. The record now states the worst window of 50 in the whole file (0.980, at
N=80) beside the one in the index section (0.240), so that the two cannot be read as
one number.

**The tests measure the property, not the code.** Every prefix of the corpus — the
file as it stood at every earlier date — is rendered and checked against the budget;
the marginal cost of a decision is measured over the last 50, 100 and 200 decisions
and over the whole corpus; the tightest point of the history is pinned to the
measured intercept; the printed budget is compared with the applied one; the summary
of the tags is checked for the whole vocabulary; the Status section is checked for
decisions outside the corpus, for a decision named twice, and for a decision that says
of itself that it was replaced and is still listed as one that stands; and the coverage
expectations are read out of the ADR files rather than out of the generator.

**Every decision is findable again.** A decision with no `title:` in its frontmatter
is carried under the first heading of its body, and a `---` in the body is read as a
rule rather than as the end of the frontmatter.

**The docstrings are English again**, as the file has always been, and a test holds
the language of the generator.

**The mutations were run.** A ceiling of 0.25, a ceiling of 0.10, an intercept of 30,
an intercept of 10, an intercept of 80, a floor under the budget, the title fallback
removed, the orientation slice removed, the renderer ignoring its corpus again, a
literal printed budget, a literal applied budget, the whole summary of the tags
removed, the fragile `first_heading`, a docstring in another language, the corpus read
from the disk again, the Status section naming a decision outside the corpus and the
Status groups overlapping, a decision's own word about being replaced ignored, the
reverse edge of the supersede relation unread, the replaced decisions back in the
index of the decisions in force, and the decision record stating a wrong rate or a
wrong point of the worst window — each of them turns the suite red, and the generator
and the record were restored after each.

**The Critical section takes its rules only from decisions in force.** The filter now
reads the same three-way notion of "replaced" that the Status section reads — not
only the `supersedes:` of another decision. A decision that says of itself that it
was replaced no longer gets its rules back into the section through the other door,
and a test on an artificial corpus holds that door shut: a decision in force keeps
its rule, and each of the three ways of saying "I was replaced" loses it.

**The decision record names the sets it counts.** It says 19 decisions carry the
reverse edge and that 8 of them are named by no `supersedes:` — and the counts are
measured the same way the reviewer measured them.

**The comment counts what it counts.** 19 rendered titles carry backticks, 14 of them
in a `title:` field and 5 in the body heading.

**No test carries the size of the corpus.** The sample sizes of the budget test are
1, 7, 100, 512 and 20 000 — none of them is the corpus of today.

## What this verdict says

It says the review found something, and that the findings are fixed in the same
change. The fourth review came back with a pass and no blockers; the fifth review
came back with a pass and no blockers as well, and the two medium findings and the
three small ones it left are fixed in the same change. The contract of the change
did not move — the same digest as before.

The verdict is `pass`: five rounds of review by separate agents, the last two
without blockers, every finding of every round fixed in the change it belongs to,
and every fix checked by the suite and by mutations that turn it red.
