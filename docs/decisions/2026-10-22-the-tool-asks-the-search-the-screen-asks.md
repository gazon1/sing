---
title: "The tool asks the search the screen asks"
date: 2026-10-22
status: accepted
tags: [ai, tools, search, contract]
---

# The tool asks the search the screen asks

## Context

The model could ask the app "which task is about the airport?", and the answer
came from a tool that read every task of the profile and kept the ones whose
*title* contained the word.

That is a filter, not a search. The app has a search: it finds a task by its
description, by its tag, by its project; it understands `tag:`, `project:`,
`due:`, `pinned`, and a leading minus that negates a term. The person at the
keyboard asks the search screen and gets the task they meant. The model asked
the tool and got nothing, because the word was in the description.

So the same question had two answers, and which one the person got depended on
who was asking. Worse, the tool's answer was not the app's answer: a task the
search screen would find was invisible to the model, and the model — unable to
tell the difference between "no such task" and "my filter is too narrow" — would
say the task does not exist.

The precondition for fixing this was met: the search lives in a use case, the
use case is in the dependency graph, and the change that made the search ask the
index has been merged.

## Idea

The tool asks the same question the screen asks, through the same machinery.

## Decision

**`search_tasks` calls the application's search.** The tool takes the query
string, parses it with the app's query parser, and answers it with
`SearchUseCase` — the same path the search screen takes. The tool's description
now names the terms the search understands, because a model that does not know
them will only ever send free text, and free text is the part of the query
language that finds the least.

**The tool stays about tasks.** The search behind it can find notes, projects and
tags; this tool does not. A tool that answers more than its name says is a tool
the model cannot reason about, and a second search tool for the other kinds is
a separate decision.

**A query the parser cannot read is reported, not swallowed.** The tool answers
with the error rather than with an empty list, because "your query is malformed"
and "there is no such task" are different facts and the model needs to be able
to tell them apart.

**The limit the caller asks for wins, unless the query carries its own.** The
query is the more specific instruction, and overriding it silently would leave
the model with a number it cannot explain.

## Rationale

The tool was written as a filter because a filter is easy to write and the
question looked easy. It was not easy: the question "which task is about the
airport" has an answer in the app already, and the tool's answer was a strictly
worse version of it.

One implementation of the search is the point. The alternative — a second
implementation of "what does this word match?" for the agent — is how the two
answers drifted in the first place, and it would drift again the next time the
query language grows a term.

The error case matters more than it looks. A tool that answers "no results" to a
malformed query teaches the model that the thing does not exist, and the model
will say so to the person. A tool that says "that query is malformed" teaches
the model to try again.

## Consequences

- **The tool's contract changes.** `search_tasks` now understands the query
  language, and its answer can carry an error. A client that sent a plain title
  keeps working — plain text is a valid query.
- **The tool depends on the search use case.** It is heavier than a repository
  read, and the tests build the whole search to test it, which is the point: a
  test of a search the application does not have proves nothing.
- **The tool and the screen cannot disagree.** One implementation, one answer —
  whether the person asking is at the keyboard or in the chat.

## Links

- `docs/decisions/2026-10-21-the-search-asks-the-index.md` — the search the tool
  now asks.
- `docs/decisions/2026-09-23-search-query-language.md` — the query language the
  tool's description now names.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/SearchTasksTool.kt`
  — the tool.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/SearchUseCase.kt`
  — the search both ask.
