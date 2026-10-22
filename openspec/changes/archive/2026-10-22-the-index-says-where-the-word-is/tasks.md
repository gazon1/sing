## 1. The row carries the position

- [x] Every query of the index answers with the row and the position of the
      match, read from `offsets()`
- [x] The position travels as the raw answer of the index — a string of
      integers — and is read in one place
- [x] The queries did not grow: there is no second set of queries that answer
      with the position alone

## 2. The port carries the match

- [x] The port answers with the record and the ranges of the match
- [x] The callers that do not want the position unwrap the record — the filter
      by text, the link suggestions
- [x] A record that was not read through the index has no position, and says
      so with no ranges

## 3. The bytes become characters

- [x] The conversion is in one place, and it walks the text the way SQLite
      wrote it
- [x] A character outside the Basic Multilingual Plane is four bytes and two
      UTF-16 units, and the range counts it as two
- [x] The range is in the units of the string the screen draws, so it lands on
      the word and not next to it

## 4. The screen draws it

- [x] The matched word is emphasised in the title it was matched in
- [x] A record that matched in its description does not emphasise its title
- [x] A note, a project and a tag are drawn the same way
- [x] The emphasis is a tint and a heavier weight — the word stands out, and
      it stays readable

## 5. The tests

- [x] The conversion: ASCII, an accented character, an emoji, a range that
      does not fit the text
- [x] Against a real SQLite: the row carries the position, the position is in
      characters, and a match in the description is not a match in the title
- [x] The drawing: the span covers the matched characters, and not one word
      more

## 6. The record

- [x] The decision is written down, with the measurements it rests on —
      including that `snippet()` is broken in this build and `offsets()` is not
- [x] The fake answers with no positions: the position is the index's answer,
      and a second answer to that question is what the last two changes
      removed
