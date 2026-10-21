## 1. The index of the searchable kinds

- [x] Declare the full-text indexes of the four searchable kinds — tasks, notes,
      projects, tags — as external-content tables over the tables they belong to
- [x] The columns of an index are the searchable text of the kind, and nothing
      more (a note's body is not searched, so it is not indexed)
- [x] The tokenizer keeps the letters of every script, so a Russian title is
      searchable
- [x] The index reads the row from the table it belongs to; the text is not
      copied

## 2. The upgrade

- [x] Create the four indexes and fill them with the records that already exist
- [x] The fill happens in the upgrade, so the records that existed before it are
      found by the same search as the ones written after it
- [x] The triggers that keep the index in step with the records are the
      framework's, not ours

## 3. The search asks the index

- [x] A search reads the records that carry the word, through the index
- [x] The structured conditions of a query still narrow the records in the
      database
- [x] The rule still decides among the candidates — the index narrows, the rule
      decides
- [x] A rule the index cannot answer (only "not", or a disjunction with a
      "not") is answered the way it was before
- [x] The stored queries that are not searches keep the pattern they had

## 4. One rule, two places

- [x] A term matches a word by its beginning — in the index and in the rule
- [x] The matcher is one implementation, used by the rule and by the fakes
- [x] The search grammar states the rule (F2a) and points at the decision

## 5. The tests

- [x] The matcher: word-prefix, phrase, case, letters outside ascii
- [x] The expression that goes to the index: a term, a phrase, alternatives, and
      a term that tries to be syntax
- [x] Against a real database: a record is found by its title and by its
      description, another profile's record is not, a renamed record follows its
      name, and the same holds for notes, projects and tags
- [x] The upgrade: the indexes exist, the records that predate it are found, and
      the text is not copied
