# Search query language — grammar specification

**Status:** normative. This document defines the search query language. An implementation
that satisfies it is conformant.

**Why this document exists.** The tokenizer and parser in
`shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/` were ported from
Orgzly (GPL-3.0) and are being rewritten for an Apache-2.0 core. That rewrite is only
meaningful if it is driven by a specification rather than by the original source
(ADR `2026-10-05-provenance-audit`, option **A1**). This is that specification.

**The honesty clause.** Writing a spec does not make the rewrite a clean room, and this
document does not claim it is one. The author of the code being replaced read the Orgzly
source. A1 reduces the risk of verbatim correspondence; it does not terminate a
derivation. That is recorded in `docs/legal/PROVENANCE.md` and repeated here because a
reader of the *specification* is entitled to know it, and a spec is the document people
trust most.

**The syntax itself is not the problem.** The Orgzly and Tasks.org query DSLs are
published interfaces. Implementing a documented grammar is not a derivative work of
someone else's implementation of it, and compatibility with those tools is a deliberate
product decision, not an accident.

## Notation

```
X        a terminal
X*       zero or more X
X?       optional X
X | Y    alternation
( … )    grouping
```

`word` denotes a maximal run of characters that are neither whitespace nor `(` nor `)`.
Matching is case-insensitive for keywords, case-**sensitive** for text and names.

---

## 1. Lexical structure

```
query        := token*
token        := lparen | rparen | quoted | word
lparen       := '('
rparen       := ')'
quoted       := '"' char* '"'
word         := <maximal run of chars not in {whitespace, '(', ')'}>

char         := <any char except '"'>
              | '\' <any char>          -- escape; the escaped char is taken literally
```

Two lexical rules are load-bearing and are the ones implementations get wrong:

**R1 — an unterminated quote runs to end of input.** `"abc` yields the quoted token
`abc`. It is not an error, and it does not swallow the rest of the query as bare text.

**R2 — a quoted string is never reinterpreted.** The contents are literal text. A quoted
`AND` is the text `AND`, not the operator, and `"("` is the text `(`, not a parenthesis.

**R3 — a word equal to a keyword *in any case* is that keyword, not text.** `and`, `And`
and `AND` are all the conjunction operator. A user who needs the literal word writes it
quoted: `"and"`.

**R4 — escapes are resolved inside quotes only.** `a\"b` outside quotes is a single word
containing a backslash, because `\` has no special meaning outside a quoted string.

---

## 2. Directives

Three word forms are directives rather than conditions. They are recognised and removed
before the expression grammar is applied, wherever they appear:

| Directive | Effect |
|---|---|
| `sort:<order>` | Sets the result sort order. |
| `desc` / `asc` | Sets the sort direction. Absent means ascending. |
| `limit:<n>`, `offset:<n>` | Sets result limits. |

`<order>` is one of `due`, `title`, `created`, `updated`, `priority`.

A directive is not a condition. `sort:due due:today` filters on `due:today` and sorts by
due date; the sort word contributes nothing to the filter. This is the behaviour that
makes `sort:` usable mid-query rather than only as a prefix.

## 3. Condition syntax

After directives are removed, the remaining tokens form an expression:

```
expression  := disjunction
disjunction := conjunction ( OR conjunction )*
conjunction := atom ( AND? atom )*
atom        := NOT expression
             | '(' expression ')'
             | word
             | quoted
```

**G1 — precedence is `NOT` > `AND` > `OR`.** `a or b and c` is `OR(a, AND(b, c))`, not
`AND(OR(a, b), c)`.

**G2 — conjunction is implicit.** `a b` is `AND(a, b)`. Writing `AND` between two atoms is
legal and changes nothing. This is the single most commonly mis-implemented rule: an
implementation that requires an explicit operator will reject `due:today priority:high`,
which is the shape users actually type.

**G3 — `NOT` binds the whole expression that follows it.** `NOT a OR b` is
`NOT(OR(a, b))`, **not** `OR(NOT(a), b)`. This is deliberate. A user writing
`NOT tag:work or due:today` means "neither of these", and the other reading silently
returns a superset of what they asked for. The cost is that `NOT` cannot be given a
narrower scope than the rest of the query; a user who wants `NOT (a OR b)` must parenthesise
accordingly.

**G4 — parentheses override precedence.** `(a or b) and c` is `AND(OR(a, b), c)`.

**G5 — a word that matches no condition rule is free text.** The word becomes a
`HasText` condition. There is no error for an unrecognised `word:value` form; it is simply
searched for as text. A search box that rejects input is a search box users stop using.
§6 says what a `HasText` condition matches.

**G6 — a quoted string is always free text**, whatever it looks like (§1 R2).

**G7 — an empty conjunction is a no-op** and matches everything.

**G8 — nested same-operator nodes are flattened.** `AND(a, AND(b, c))` is
`AND(a, b, c)`. A single-element conjunction or disjunction collapses to that element.
This is an observable property of the AST, not just a tidy-up: it makes structural
equality usable in tests and in saved-search comparison.

**G9 — an unclosed `(` is tolerated.** The expression is parsed as far as it goes. It is
not an error. A user typing into a search box is mid-edit most of the time.

## 4. Conditions

A word is a condition when it matches one of the rules below; otherwise §3 G5 applies.

| Form | Condition | Notes |
|---|---|---|
| `due:<interval>` | `Due(interval, EQ)` | §5 |
| `scheduled:<interval>` | `Scheduled(interval, EQ)` | §5 |
| `priority:<p>` | `HasPriority(p)` | `highest`, `high`, `medium`, `low` |
| `<status>` | `HasStatus(status)` | `done`, `not done` / `notdone`, `pending` |
| `tag:<name>` | `HasTag(name)` | repeated → `HasAllTags` (C1) |
| `tags:<a,b>` | `HasAllTags(names)` | the explicit spelling of what C1 makes implicit |
| `project:<name>` | `InProject(name)` | |
| `pinned` | `IsPinned` | |
| `archived` | `IsArchived` | |
| `has:description` | `HasDescription` | |

**C1 — `tag:` accumulates.** `tag:work tag:urgent` is one `HasAllTags` condition, not an
implicit `AND` of two `HasTag`s. The distinction is visible in the AST and matters to
saved searches. The explicit form `tags:work,urgent` states the same condition in one
word.

**C2 — names and text are taken literally after the prefix.** `tag:work` looks up the tag
named `work`; `project:Plans` looks up the project named `Plans`. Resolution to an id
happens later, in the resolver, against the database.

**C3 — an unresolvable name is not a parse error.** It resolves to no rows. The query
parses; the filter matches nothing.

## 5. Date intervals

```
interval := "now" | "today" | "tod"      ->  0 days
          | "tomorrow" | "tom"           -> +1 day
          | "yesterday"                  -> -1 day
          | "none" | "no"                ->  unconstrained
          | signed-number unit           ->  see below
unit     := "d" | "w" | "m" | "y"
```

**D1 — units are days, weeks, months, years**, where a month is 30 days and a year is 365.
This is a fixed-length convention, not a calendar-aware one: `1m` is always 30 days, so
`due:1m` on 31 January lands in early March rather than on 28 February. Calendar-aware
arithmetic was rejected because it makes the parsed interval depend on the date it is
parsed on, and a saved search would then mean different things in different months.

**D2 — the sign is explicit or absent.** `3d` and `+3d` are the same.

**D3 — `none` is a distinct value, not zero.** It means "no date constraint", which is not
the same as "today". It is the one interval that cannot be expressed as a day count.

**D4 — an unparseable interval is not a parse error.** The word falls through to free text
(§3 G5), so `due:soon` searches for the text `due:soon`.

## 6. Free-text matching

§3 G5 turns a word that matches no condition rule into a `HasText` condition, and
§3 G6 does the same for a quoted string. This section states what a `HasText`
condition matches. It is the rule every kind of record — task, note, project, tag
— is measured against.

**F1 — the free text of a query is a list of terms.** One term is one `HasText`
condition. A quoted string is one term, however many words it holds: `"quarterly
report"` is a single term and has to occur as written.

**F2 — every term has to match.** A record matches the free text of a query iff
every term of that free text matches the record's searchable text (F3).
Matching is case-insensitive.

**F2a — a term matches a *word* of the searchable text by its beginning.** A
term is split into words — runs of letters and digits — and matches when the
record's words contain the term's words **next to each other**, where the last
word of the term may be unfinished and the ones before it have to be complete.
A one-word term is therefore a word-prefix match; a quoted phrase is a phrase
whose last word may be unfinished.

"arch" matches "architecture". "port" does **not** match "airport": the word
"airport" does not begin with "port". `"quarterly report"` does **not** match
"quarterly annual report": the words of the phrase stand next to each other.

This is the rule the full-text index implements — the index stores words, so it
can only find the beginning of one — and the in-memory rule is the same rule,
so that the two cannot answer differently. See
`docs/decisions/2026-10-21-the-search-asks-the-index.md` and
`docs/decisions/2026-10-22-the-index-answers-the-positive-terms.md`.

It is also the rule of the **word mode** — the mode the free text is matched
in (F6).

**F2b — the index answers the positive terms, the rule the negated ones.** A
term that is not negated is answered by the full-text index, which knows the
tokenizer; a term under `NOT` is answered by the in-memory rule, because FTS
has no `NOT` operator. The rule does not ask the index's question a second
time.

Both halves fold the diacritics the same way (F7), so they answer the same
question the same way: a search for "cafe" finds "Café au lait", and `NOT cafe`
rules out exactly the records "cafe" finds.

The asymmetry that used to be here — the index removing the diacritics and the
rule keeping them — is closed. It was written down as deliberate in
`docs/decisions/2026-10-22-the-index-answers-the-positive-terms.md`; what
closed it is
`docs/decisions/2026-10-23-the-rule-folds-the-diacritics-the-way-the-index-does.md`.

**F3 — the searchable text of a record is the kind's own.**

| Kind | Searchable text |
|---|---|
| Task | title or description |
| Note | title |
| Project | name |
| Tag | name |

The rule does not change what is searchable. Each kind keeps its own surface, so
the task list and the search screen cannot drift apart on what a match means.

**F4 — the terms are combined by the query's connective.** A query that contains
an `OR` makes its terms alternatives: any of them may match. A query without one
requires every term (F2). A term under `NOT` must *not* match.

**F5 — a query with no free text selects no note, project or tag.** The structured
terms of a query are filters over tasks; they do not select the other kinds, and
a query with no free text has no free-text part to match.

**F6 — the mode is stated by the surface, and it changes the rule for every term.**
The free text is matched in one of two modes. In the **word mode** a term
matches a word of the searchable text by its beginning (F2a). In the
**contains mode** a term matches when it occurs *anywhere* in the searchable
text, case-insensitively: "port" finds "airport".

The mode is stated by the surface that runs the search, and not by the query:
the query language has no marker for it. A marker would mean two things at
once — the `*` is the index's own prefix syntax, and a marker left in a saved
query is invisible to whoever reads the query later — and it would let one
query mix the two rules, one term by beginning and another by occurrence. The
mode changes the rule for **every** term of the query at once: a query asks
one question, and the mode says which question.

The contains mode is not answered by the full-text index. The index stores
words, not character positions, so it can find the beginning of a word and
nothing else; a search in the contains mode reads the rows and filters them
in memory. The surface says so, because a slower answer that is not announced
is a slower answer that is not believed.

The rule of the mode is one rule for every kind of record — a task by its
title and its description, a note by its title, a project and a tag by their
name (F3) — in both modes, so that a search cannot answer differently
depending on the kind of thing it is looking for. The mode is written down in
`docs/decisions/2026-10-22-the-substring-search-is-a-mode-the-surface-states.md`.

**F7 — the diacritics are folded the way the index folds them.** A character
with a diacritic is folded to its base letter before anything is compared, and
the fold is the one the full-text index does: "café" is folded to "cafe", so
the term "cafe" matches "Café au lait". The fold does not change the length of
the text, so a position in the folded text is the same position in the
original.

The rule holds for both modes (F6): the word mode and the contains mode answer
the same way about the same text, and a negated term rules out exactly the
records the positive term finds.

### 6.1 Why a conjunction, and not the alternatives

The two alternatives were a disjunction (any term may match) and the joined
phrase (the whole free text has to occur as one substring). Both contradict §3 G2.
Juxtaposition is a conjunction, so `hello world` is
`AND(HasText("hello"), HasText("world"))` in the AST; a disjunction would make
`hello world` mean something different from `hello AND world`, and G2 says the
explicit operator "changes nothing". The joined phrase is not a reading of the AST
at all — it is a different grammar wearing the same syntax, and `a b` would mean
something no operator can express.

A conjunction is also what the product wants. This is a task manager: the user
types into a search box to narrow a list they are looking at. A conjunction
narrows — two words that both occur are a strong signal, and a record that
carries one of them is a candidate to refine rather than an answer. A disjunction
widens the list on every keystroke, and a phrase search answers a question the
user did not ask.

### 6.2 A worked example

The query `quarterly report` states two terms, `quarterly` and `report`. Under F2
a record has to carry both.

| Record | Task | Note | Project | Tag |
|---|---|---|---|---|
| "Write the quarterly report" | match | match | — | — |
| "Quarterly review" | no — no `report` | no | — | — |
| "Report" | no — no `quarterly` | no | — | — |
| "Report" (a project named `Report`) | — | — | no — no `quarterly` | — |
| "Report" (a tag named `Report`) | — | — | — | no — no `quarterly` |

The second word alone is not enough, and the first word alone is not enough
either. That is F2, and it is the same answer for every kind.

A note is searched by its title (F3), so a note titled "Report" whose *body*
contains "quarterly" is not a match either — the other word has to be in the
searchable text, wherever that is for the kind in question.

The rule used to be per kind: tasks were matched per term, and notes, projects
and tags against the joined phrase `quarterly report`. A task titled "Quarterly"
was therefore found while a note titled "Report" whose body contained "quarterly"
was not — the answer depended on which kind of thing the user was looking for.
One rule, one owner: the resolver builds it, and every kind is matched by it.

## 7. Conformance

An implementation conforms if it satisfies §1–§6 and passes the behavioural suite in
`shared/src/commonTest/kotlin/com/singularity/todo/feature/search/query/` — 153 tests
across nine classes, each carrying `@Tag`. The rule of §6 is also asserted per kind —
task, note, project, tag — in
`shared/src/commonTest/kotlin/com/singularity/todo/feature/search/SearchFreeTextRuleTest.kt`,
because a rule that only holds for one kind of record is not a rule. The mode of F6 is
asserted in `SearchModeTest` — the rule of each mode and the plan of each mode — and in
`SearchContainsModeTest`, which asks the search the same query in both modes.

The suite is organised by the sections above rather than by implementation unit, so a
failure names a rule and not a class. Where a rule is easy to get subtly wrong — R1, R3,
G1, G2, G3, C1, D1, F2 — it has its own named test.

### 7.1 The grammar and the code are held in agreement

A specification and an implementation drift apart silently: a rule is added here and
not implemented, or a rule is implemented and never written down, and the search box
answers a question nobody asked it to answer.

Two things keep them together.

**The code cites the rule.** A rule the implementation carries is cited next to the
code that implements it, in the words used here: `G2 — conjunction is implicit.`
A rule that is not cited is a rule nobody can find, and a citation in other words is a
rule that has drifted from this document.

**A test reads this document.**
`shared/src/jvmTest/kotlin/com/singularity/todo/feature/search/query/SearchQueryGrammarAgreementTest.kt`
reads this file and the sources of the search feature, and fails the build when

- a rule declared here is not cited by the code, or is cited in words this document
  does not use;
- a rule the code mentions is not declared here;
- a rule this document refers to is not declared here.

The test carries no copy of the rules. It reads them here, and compares — a test with
its own list of the rules would be a second place to forget to update, which is the
failure it exists to catch.

## 8. Deliberate non-goals

- **No implicit `AND` after a closing parenthesis is not supported** in the sense that
  `(a) b` parses as `AND(a, b)` — which is G2, and is intentional.
- **No quoted prefix syntax.** `"due:today"` is free text, not a due condition (§3 G6).
  The rule is uniform rather than clever, and a user who wants to search for the literal
  string gets exactly that.
- **No phrase matching of the free text.** The free text of a query is a list of terms,
  and every term has to match (§6 F2). The joined phrase is not a second way to read
  the same query — a user who wants a phrase writes it as one quoted term.
- **No user-defined functions or nesting beyond parentheses.** A grammar that grows
  nesting grows the space of queries the resolver cannot push down to SQL.

## 9. The owners of the free text

A rule that lives in one place but is *asked* in three is a rule with three
owners. Designing the escape-hatch (2026-10-22) cost the reading of sixteen
files in three layers, and the question that answered it was always the same:
*who owns this half of the search?* So the answer is written down here, next to
the grammar the owners implement.

| What | Where | Who answers |
|---|---|---|
| The rule — terms, negation, conjunction | `core/search/FreeTextRule.kt` | `plan()` |
| The question the index is asked | `core/search/FreeTextRule.kt` → `indexQuery()` | the rule — nobody else builds it |
| The plan — can the index narrow it? | `core/search/TextMatchPlan.kt` | `Narrowed` / `BroadScan` |
| The form of the question | `core/search/TextMatch.kt` → `TextMatchQuery` | terms and the connective |
| The matcher — does this text match? | `core/search/TextMatch.kt` → `TextMatch` | word (F2a) / contains |
| The mode — Word or Contains | `feature/search/query/SearchMode.kt` | the surface that runs the search |
| The surface of a task | `feature/tasks/domain/TaskDomain.kt` → `searchableText` | the task's own title and description (F3) |
| The callers of the index | `feature/notes/data/NotesRepositoryImpl.kt`, `feature/search/data/InternalLinkRepositoryImpl.kt`, `feature/tasks/data/TaskRepositoryImpl.kt` | they ask the rule for the question |

Three consequences, each of them a rule rather than a habit:

- **A caller of the index does not write the question.** It asks the rule:
  `FreeTextRule.singleTerm(text).indexQuery()`. A hand-written question is a
  decision about what the index can answer, taken away from the rule that knows
  it — and a wrong one looks like an answer all the same. A Konsist rule
  (`ArchitectureTest`) fails the build when a `TextMatchQuery` is built outside
  `core/search`.
- **An empty question means "the index does not narrow this".** It finds
  nothing; it does not find everything. A caller that has to read the rows reads
  them itself.
- **The mode belongs to the surface, not to the query** (F6). The search screen
  owns the switch and the sentence that says what it costs; the notes list, the
  link autocompletion and the task list search by the word rule (F2a) and do not
  carry a mode of their own.

The rule moved to `core/search` for this reason: it sits next to the plan, the
question and the matcher it produces, and next to the index port it is
answered by. ADR: `docs/decisions/2026-10-23-the-rule-builds-the-question-for-the-index.md`.
