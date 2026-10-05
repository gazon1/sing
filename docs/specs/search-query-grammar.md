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
| `tag:<name>` | `HasTag(name)` | repeated → `HasAllTags` |
| `project:<name>` | `InProject(name)` | |
| `pinned` | `IsPinned` | |
| `archived` | `IsArchived` | |
| `has:description` | `HasDescription` | |

**C1 — `tag:` accumulates.** `tag:work tag:urgent` is one `HasAllTags` condition, not an
implicit `AND` of two `HasTag`s. The distinction is visible in the AST and matters to
saved searches.

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

## 6. Conformance

An implementation conforms if it satisfies §1–§5 and passes the behavioural suite in
`shared/src/commonTest/kotlin/com/singularity/todo/feature/search/query/` — 134 tests
across seven classes, each carrying `@Tag`.

The suite is organised by the sections above rather than by implementation unit, so a
failure names a rule and not a class. Where a rule is easy to get subtly wrong — R1, R3,
G1, G2, G3, C1, D1 — it has its own named test.

## 7. Deliberate non-goals

- **No implicit `AND` after a closing parenthesis is not supported** in the sense that
  `(a) b` parses as `AND(a, b)` — which is G2, and is intentional.
- **No quoted prefix syntax.** `"due:today"` is free text, not a due condition (§3 G6).
  The rule is uniform rather than clever, and a user who wants to search for the literal
  string gets exactly that.
- **No user-defined functions or nesting beyond parentheses.** A grammar that grows
  nesting grows the space of queries the resolver cannot push down to SQL.
