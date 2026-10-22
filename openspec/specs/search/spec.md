# search Specification

## Purpose

Find the records of the profile by what they say: tasks, notes, projects and
tags, by their title, their description or their name. The query is parsed into
structured conditions and free text, and the free text is answered by the
full-text index — the definition of what a word is lives in one place, and the
index is what applies it to the rows.

## Requirements

### Requirement: REQ-SEARCH-001

A text search **SHALL** return the records whose searchable text contains a word
beginning with the term, and **MUST NOT** return the records that do not.

> The search answers one question — "which records carry this word" — and it
> has to answer it the same way every time. A word that merely contains the
> term ("airport" for "port") is a different question, and answering it in one
> place but not the other is how a search starts to feel arbitrary.

#### Scenario: A term finds the word that begins with it
- A record's searchable text holds the word "architecture"
- A search for "arch" is made
- The record is returned

#### Scenario: A term does not find a word that contains it
- A record's searchable text holds the word "airport"
- A search for "port" is made
- The record is not returned

---

### Requirement: REQ-SEARCH-002

A text search **SHALL** return the records of the profile that is asking, and
**MUST NOT** return the records of another profile.

> A search that crosses profiles is a leak, and a leak that only appears for one
> word is a leak nobody reports.

#### Scenario: A search sees only the asking profile
- Two profiles hold a record with the same word
- A search for that word is made in one of them
- Only the record of that profile is returned

---

### Requirement: REQ-SEARCH-003

A record whose text changed **SHALL** be found under its new text and **MUST
NOT** be found under its old text.

> The index is a copy of the words of the records. A copy that is not in step
> is worse than no copy: the search would answer about a record that no longer
> exists in that form.

#### Scenario: A renamed record follows its name
- A record's text is changed
- A search for the old text is made
- The record is not returned
- A search for the new text is made
- The record is returned

---

### Requirement: REQ-SEARCH-004

A search **SHALL** return the records that existed before the index was
created, exactly as it returns the records written after it.

> An index that starts empty loses records silently. The search would return
> fewer results than the profile holds, and nothing would report it: the user
> would conclude that the record is gone.

#### Scenario: An old record is found
- A record was written before the index existed
- The index is created
- A search for the word of that record is made
- The record is returned

---

### Requirement: REQ-SEARCH-005

The stored text of a record **SHALL** be kept once. The database **MUST NOT**
hold a second copy of it.

> A second copy doubles the storage of every record and is a second thing to
> keep in step with the first. The index of words is enough to answer a search;
> the words of the record are not.

#### Scenario: The text is stored once
- A record's text is stored
- The database holds the text in the table of the record
- The index holds the words of the text and the rows they occur in, and not the
  text itself

---

### Requirement: REQ-SEARCH-006

The time a search takes **SHALL NOT** grow in proportion to the number of
records stored.

> A search runs while the user types. A search whose cost grows with the number
> of records is a search that becomes unusable exactly when the profile has
> something worth searching for.

#### Scenario: A search over many records stays as fast as over few
- A profile holds many records
- A search is made
- The search reads the records that carry the word, not every record

---

### Requirement: REQ-SEARCH-007

A search whose term holds no letters or digits **SHALL** return no records.

> A term that cannot be a word cannot match a word. Returning every record for
> it would be the opposite of an answer.

#### Scenario: A term of punctuation matches nothing
- A search for a term of punctuation is made
- No records are returned

---

### Requirement: REQ-SEARCH-008

A quoted phrase **SHALL** return the records whose searchable text holds the
words of the phrase next to each other, and **MUST NOT** return the records
that hold the same words apart.

> The phrase is one term, and the words of a term stand next to each other. An
> index that asks for the words as a conjunction answers a different question,
> and the answer then depends on whether the row happened to reach the index.

#### Scenario: A phrase finds the words next to each other
- A record's searchable text holds "Write the quarterly report"
- A search for "quarterly report" is made
- The record is returned

#### Scenario: A phrase does not find the same words apart
- A record's searchable text holds "quarterly annual report"
- A search for "quarterly report" is made
- The record is not returned

---

### Requirement: REQ-SEARCH-009

A search whose term is negated **SHALL NOT** return the records that carry
that term.

> A negated term asks which records do *not* carry a word. The index has no
> `NOT` operator, so the rule answers this half in memory — and it has to
> answer it, or the term would be ignored.

#### Scenario: A negated term rules the record out
- Two records hold the word "alpha", and one of them holds "beta" as well
- A search for "alpha NOT beta" is made
- Only the record that does not hold "beta" is returned

---

### Requirement: REQ-SEARCH-010

A search **SHALL** return the records whose word differs from the term only by
its diacritics.

> The index knows the tokenizer, and the tokenizer removes the diacritics of a
> word — "Café au lait" is indexed as "cafe au lait". The answer is the index's,
> and a search that found "café" for "cafe" in one place and not in another
> would be a rule with two answers.

#### Scenario: A term finds the word without its diacritics
- A record's searchable text holds "Café au lait"
- A search for "cafe" is made
- The record is returned

### Requirement: REQ-SEARCH-011

A search result **SHALL** show the matched word in the text the match was found
in, and **MUST NOT** show it in a text that does not carry the word.

> The index knows where the match is — it holds the tokenizer, and it knows
> which column of the record matched. A result that does not say where leaves
> that answer on the way to the screen, and the person cannot tell a title
> that carries the word from a description that mentions it.

#### Scenario: The matched word is shown in the title
- A record's title holds the word "architecture"
- A search for "arch" is made
- The word in the title is shown as the match

#### Scenario: A record that matched in its body does not show the word in its title
- A record's title holds "The plan", and its description holds "architecture"
- A search for "arch" is made
- The title is shown without the word, because the title does not carry it

---

### Requirement: REQ-SEARCH-012

The match **SHALL** be shown where the index says it is, in the characters of
the text and not in its bytes.

> SQLite counts the bytes of the text and a Kotlin string counts characters.
> For ASCII the two agree, and the difference is invisible until a title
> carries an accent — and then the emphasis lands on the wrong word. The
> position is the index's answer, and it is drawn where the index says.

#### Scenario: The match in a title with an accent covers the whole word
- A record's title holds "Café au lait"
- A search for "cafe" is made
- The emphasis covers "Café" — the whole word, and not the space after it
