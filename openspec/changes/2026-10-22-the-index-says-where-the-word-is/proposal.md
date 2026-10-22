# The index says where the word is, and the search draws it

Issue: [#895](https://github.com/gazon1/sing/issues/895) · Related: `docs/decisions/2026-10-22-the-index-says-where-the-word-is.md`

## What

Show *where* the search matched, not only *that* it matched.

A person searching for "architecture" gets a list of tasks, and the task whose
title is "Architecture" looks exactly like the task whose description mentions
it. The index knows the difference — it holds the tokenizer, and it knows which
column of the record matched — and FTS4 can say it: the `offsets()` function
answers with the position of every matched token.

Concretely:

1. The rows the index returns carry the position of the match beside the row.
2. The port carries the position with the record, so the answer that says
   "which records" and the answer that says "where" cannot come apart.
3. The search results carry the position to the screen, and the screen draws
   the matched word in the title it was matched in.

## What is not in this change

- The query language does not change. The same queries mean the same things,
  and the same records are found.
- The ranking does not change. The records that carry the word in their title
  come first, as they did.
- The searchable text of a kind does not change. A task is still searched by
  its title and its description, a note by its title, a project and a tag by
  their name.
- The rule in Kotlin does not learn to find the word in the text. The position
  is the index's answer, and a second answer to the same question is what the
  last two changes removed.

## The change in behaviour

A search result shows the matched word, emphasised, in the text it was matched
in. A record that matched only in its description shows no emphasis in its
title — the title does not carry the word, and emphasising it would be a lie
about where the match is.

The emphasis is the index's position, in the characters of the text. A title
with an accent is where the two answers part company: SQLite counts bytes and
a Kotlin string counts characters, and the conversion is the one step of this
that can be got wrong. It is invisible while the texts are ASCII, and a test
with an accented title is not optional.

## Why now

It is the one functional hole the search has left: the index knows where the
match is, and the answer was thrown away on the way to the screen.
