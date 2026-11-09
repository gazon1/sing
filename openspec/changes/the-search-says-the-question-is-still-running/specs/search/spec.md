# search

## ADDED Requirements

### Requirement: REQ-SEARCH-017 A pending search is not an empty answer

A search surface **SHALL** tell the state "the query has been stated and the
search has not answered yet" apart from the state "the search ran and matched
nothing", and **MUST NOT** report that nothing was found for a query that has not
run.

> The search is debounced and then run, so there is always a window in which the
> query is non-blank and the results are empty. Reporting that window as an empty
> answer states a conclusion the search has not reached, and the person reads it
> as the truth for as long as it lasts.

While the search is pending the surface **SHALL** show that the search is
running. It **SHALL NOT** show the results of the previous query: those are the
answer to a question that is no longer being asked.

#### Scenario: The first keystroke is not an empty answer

- **Given** the search screen open and no query run yet
- **When** a person types a single letter and the search has not answered
- **Then** the screen shows that the search is running
- **And** it does not report that nothing was found

#### Scenario: The previous query's results do not stand in for the new answer

- **Given** a search that returned results
- **When** the query is changed and the new search has not answered
- **Then** the results of the previous query are not shown
- **And** the screen shows that the search is running

#### Scenario: A blank query is idle even while a search is pending

- **Given** a search in flight for a non-blank query
- **When** the query is emptied
- **Then** the screen invites the person to search
- **And** it does not show that a search is running

#### Scenario: A query that cannot be parsed leaves no spinner behind

- **Given** a search screen
- **When** a query that cannot be parsed is typed
- **Then** the screen reports that the query could not be read
- **And** it does not show that a search is still running

### Requirement: REQ-SEARCH-018 The query can be emptied from the field itself

The field a query is written in **SHALL** offer a control that empties it, and
using it **SHALL** return the surface to the state that invites the person to
search.

> Emptying a query with the platform's own text selection is three gestures on a
> phone — long press, select all, delete — and the field is the one place the
> person is already touching.

#### Scenario: One tap empties the query

- **Given** a query in the field and its results on screen
- **When** the field's clear control is used
- **Then** the field is empty
- **And** the screen invites the person to search
- **And** the previous results are gone

#### Scenario: There is nothing to clear on an empty field

- **Given** an empty query
- **Then** the field offers no clear control

### Requirement: REQ-SEARCH-019 The field asks for the focus when the screen opens

A surface opened in order to be typed into **SHALL** put the focus in its input
field when it appears, so that typing reaches the search without a tap first.

> A screen whose only purpose is the field, opened without the caret in that
> field, is a screen that has not finished opening. The person types, nothing
> happens, and the failure looks like a broken keyboard.

#### Scenario: Typing after opening reaches the search

- **Given** the search screen is not open
- **When** it is opened
- **Then** its query field holds the focus

### Requirement: REQ-SEARCH-020 A match carries the range of the surface it was found in

A record returned by a search **SHALL** carry the range of the match in the
surface the record was searched by, and a surface **SHALL NOT** draw a range
measured in another surface.

> The emphasis is drawn over characters, and a character offset means nothing
> outside the text it was measured in. A range from the description drawn over
> the title underlines an unrelated word — worse than no emphasis, because it
> names the wrong place.

#### Scenario: The match is drawn where the word is

- **Given** a record whose searchable text carries the term
- **When** the search returns it
- **Then** the surface draws the emphasis over the characters the match covers

#### Scenario: A match in another surface is not drawn in this one

- **Given** a record that matched in a surface the result card does not draw
- **Then** the card draws no emphasis over the text it does draw
