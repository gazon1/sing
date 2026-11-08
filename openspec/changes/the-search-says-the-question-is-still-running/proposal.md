# The search says the question is still running

Issue: #1711 · Related: #2117, #2118, #2119

## What

The search screen answers a question the person has asked and has not finished
answering. Three things were missing at the moment the query was stated, and a
fourth was missing at the moment the answer was drawn.

The screen has four states now: it invites the person to search, it says the
search is running, it reports that the search found nothing, and it shows the
results. Before this change the second state did not exist, so a query that had
not run yet was reported as a query that found nothing — a confident wrong
answer, replaced by the real one a moment later.

## The change in behaviour

- A **pending search** is drawn as itself. While the query is in flight the
  screen shows a progress indicator, and it does not report "no results" for a
  search that has not run. The results of the previous query are not shown under
  the new one: they are the answer to a question nobody is asking any more.
- The query can be **emptied from the field itself**. The field carries a clear
  button, and one tap returns the screen to the state it invites typing from.
- The field **asks for the focus** when the screen appears. A screen opened in
  order to be typed into puts the caret in its field.
- A match found by **reading the rows** carries the range of the match in the
  surface the record was searched by, so the card draws the emphasis on the
  characters the search found — and a range measured in another surface is not
  drawn in the one on screen.

## What is not in this change

- The searchable surfaces of a note. A note is searched by its title (F3), and
  that decision is not this change's to revisit.
- A snippet for a match in a surface the result card does not draw. A task
  matched by its description carries the range of that match; the card draws the
  title, and the title is not where the word is.
- The search overlay, which is a different screen.

## Why now

Because the screen's fourth state was published without the third, and the two
look identical only when the search is instant — which it is not: it is debounced
and then run. The defect report that produced this change is three keystrokes
long: type one letter and the screen says nothing was found.
