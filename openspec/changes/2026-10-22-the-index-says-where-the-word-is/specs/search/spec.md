# The index says where the word is, and the search draws it — Observable Behavior

**capability:** `search` | **status:** proposed

---

## ADDED Requirements

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
