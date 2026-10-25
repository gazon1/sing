## 1. The mode

- [x] The mode is a type of the search feature, and it holds the rule that
      answers the question — not the screen that states it
- [x] The mode has two states: the word mode (the default) and the contains
      mode
- [x] The mode changes the rule for every term of the query at once

## 2. The rule of the contains mode

- [x] The term occurs anywhere in the searchable text, case-insensitively
- [x] The rule is one rule for every kind of record — a task by its title and
      its description, a note by its title, a project and a tag by their name
- [x] The negated terms are answered by the same rule
- [x] The rule lives in one place, beside the word-prefix rule, and it is used
      where the index produces no candidates

## 3. What the index can do

- [x] The contains mode never narrows the rule: the index stores words, not
      character positions
- [x] The plan of the contains mode is the named broad scan, and not a second
      kind of plan

## 4. The surface

- [x] The screen shows the mode next to the field the query is written in
- [x] The contains mode says what it costs
- [x] The mode is part of the state of the screen, and changing it asks the
      search again

## 5. The grammar

- [x] F6 states the mode, and the two rules it carries
- [x] F2a says which mode it is the rule of
- [x] The code cites F6 in the words of the grammar

## 6. The tests

- [x] The mode: the rule of each mode, the plan of each mode, and the task
      matched by its title and its description
- [x] The search: the term found in the middle of a word, for every kind of
      record; the negated term; the word mode unchanged
- [x] The screen: the switch, and the sentence that says what the mode costs
- [x] The view model: the mode is in the state, and the search is asked again

## 7. The record

- [x] The decision is written down, with the three shapes that were offered
      and why two of them were not taken
- [x] The AI tool's own occurrence-matching is noted, and not changed here
