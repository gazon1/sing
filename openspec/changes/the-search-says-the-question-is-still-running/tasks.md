# Tasks — the search says the question is still running

- [x] The screen draws a pending search as itself, and does not report an empty
      answer for a query that has not run.
- [x] The screen shows that the search is running while the flag is raised.
- [x] A blank query shows the idle prompt even while a search is in flight.
- [x] A query that cannot be parsed leaves no spinner behind.
- [x] The field empties its own query with a clear control, and the idle prompt
      returns.
- [x] The field asks for the focus when the screen appears.
- [x] A record found by reading the rows carries the range of the match in the
      surface it was searched by, and a card does not draw a range measured in
      another surface.
- [x] The scenario `SEARCH-11` is written, and a test carries the id as the
      first token of its display name.
- [x] The Maestro flow clears the query with the field's own control.
- [x] The range the contains rule computes is asserted against the range the
      index reports, over a corpus with a diacritic, an accented character, a
      term of several words and a term in the middle of a word.
