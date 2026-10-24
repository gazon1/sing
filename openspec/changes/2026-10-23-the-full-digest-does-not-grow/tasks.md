# Tasks — the-full-digest-does-not-grow

## 1. Fold the index

- [x] 1.1 Render every decision once, as a record — slug, title, tags — packed into
      paragraphs of a fixed width, instead of a line per decision and a section per
      tag.
- [x] 1.2 Drop the per-tag sections that carried the consequence bullets: that text
      is the ADR body, and the index says how to grep for it instead of holding a
      second copy.
- [x] 1.3 Keep the absolute rules findable — a readable head of the newest ones, the
      rest packed — and drop the four trimming knobs that answered "what do we drop
      when the corpus grows?" by dropping content.
- [x] 1.4 Take the tags of a decision from its frontmatter, so a decision is
      findable by every tag it carries.

## 2. Budget the index by a rate

- [x] 2.1 Measure the fixed part of the file (the digest rendered from an empty
      corpus) and the rate a decision costs it, and write the measurements down.
- [x] 2.2 Make the budget the fixed part plus the rate times the corpus: monotone in
      the corpus, with no floor under it and no fixed ceiling over it.
- [x] 2.3 Pin the measured fixed part in a test, so a change to the parts that do
      not scale cannot pass unnoticed.
- [x] 2.4 Say in the ADR that the gate reads the budget out of the line the
      generator prints — the budget has one owner.

## 3. Make every decision findable

- [x] 3.1 Fall back to the first heading of the body when the frontmatter carries no
      title, so the decisions that have no `title:` are found by their title too.
- [x] 3.2 Mark a decision that has neither a title nor a heading, rather than
      leaving a blank in the index.

## 4. Keep the orientation layer bounded by kind

- [x] 4.1 Name the most-used tags in the layer, and say where the complete
      vocabulary is.
- [x] 4.2 Let the renderer take the decisions it draws from as an argument, so the
      property "the layer does not grow with the corpus" can be measured instead of
      assumed.

## 5. Check the property, not the code

- [x] 5.1 Read the expectations out of the ADR files in the tests, not out of the
      generator: every slug, every title, every tag and every absolute rule.
- [x] 5.2 Check that the measured digest fits the budget it declares, and that the
      budget grows with the corpus.
- [x] 5.3 Mutate the generator — a tighter rate, a smaller fixed part, a floor under
      the budget, a missing slice — and see the tests go red.

## 6. Keep the measurement honest

- [x] 6.1 Name the two rates of the pre-fold file separately — 2.75 lines per decision,
      and 2.71 beyond the 30 lines the empty file costs — in the generator, in the
      proposal and in the decision record.
- [x] 6.2 Measure the prefix series at every prefix of it, not at every tenth: the
      sample missed the tightest point of the history (N=129, a covering of 80.3) and
      let an intercept of 80 pass.
- [x] 6.3 Keep the Status groups exclusive and inside the corpus: a decision somebody
      replaced is superseded whatever its own frontmatter says, and a decision that is
      not in the corpus is not named.
- [x] 6.4 Read the corpus from the texts that are handed over, and parse each decision
      once, so that measuring 815 prefixes costs seconds rather than minutes.
- [x] 6.5 Read the reverse edge of the supersede relation — a decision's own
      `status: superseded` and its own `superseded-by:` — so that a decision which
      was replaced is not listed as one that still stands; keep the priority (the
      replacement wins) and the groups exclusive, and record the rule in the ADR.
- [x] 6.6 Measure the rates the decision record states, and keep the record in
      step with the measurement: the worst window of 50 in the whole file (0.980, at
      N=80) is named beside the one in the index section (0.240).
