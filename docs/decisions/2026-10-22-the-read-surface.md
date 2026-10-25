---
title: "The read surface speaks the app's language"
date: 2026-10-22
status: accepted
tags: [ai, tools, tasks, contract]
---

# The read surface speaks the app's language

## Context

The chat can call tools. That was the first half. The second half is that the tools it
can call cannot answer the questions people actually ask.

`list_tasks` took a project id and a limit, and returned every task it found —
completed, unfinished, overdue, next month, all of it in one list with nothing in the
list saying which was which. `get_task` returned four fields: a title, a description, a
boolean, a project id. Not a due date, not a priority, not what the task was waiting
on, not how far its checklist had got.

Meanwhile the app knew all of it. There is an Inbox tab, a Today section, an overdue
list, a health classification with buckets for overdue, blocked and stagnant, and a
statistics screen that counts them. The screens had the answers. The tools had a
different, smaller set of questions.

The gap is not cosmetic. A reader who asks "which of my tasks are overdue?" through
the tool today gets a list of tasks with no overdue in it — and no way to tell that the
answer was a list of everything rather than a list of what was asked for. The tool
cannot lie, because it never claimed to filter; it just cannot answer.

## Idea

**Name the sets the app already has, and resolve them with the app's own rules.**

The vocabulary is not new. `inbox`, `today`, `upcoming`, `overdue`, `blocked`,
`stagnant`, `pinned`, `someday`, `active`, `completed`, `all` are the app's words, and
they travel to the model lower-cased, because the model reads English and a value it
has to guess at is a value it will get wrong. The schema offers exactly the set the
tool accepts, so the model is choosing from a list rather than inventing one.

**The rule that decides membership is the app's rule.** This is the part that decides
whether the change is worth anything. A tool that worked out "overdue" its own way
would be a second answer to a question the app has already answered, and the two would
drift — and the reader would be left choosing. So the selection is built out of
`TaskDomain.matchesFilter` and `Selector.matches`, the same predicates the agenda and
the statistics screen use.

**But the read starts from everything, not from a narrower query.** This is the
surprise, and it is a consequence of looking at the repository rather than at the
model. The repository and the domain do not always agree: the repository answers
`Inbox` with every live task, while the domain's answer to `Inbox` leaves out the
deferred ones, and the repository answers `ByStatuses` with nothing at all. A tool that
pushed the narrower filter down would therefore report a different set from the screen.
So the tool reads the live tasks and applies the domain's rule to them. The read is one
query over the user's own rows either way; the answer is the app's answer.

**The cursor says what it is about.** A cursor is a promise about a list, and a cursor
used against a different list hands the reader a page of something else that looks
exactly like a page. So the cursor carries a fingerprint of the selection and its
narrowing, and a mismatch is said out loud rather than quietly obeyed. The cursor that
already existed in the server module was never wired to anything; it moves into the
shared module and gets used.

**How much a task weighs is two measurements, not one number.** The reader needs to
know more than whether a task is overdue: a task that asks for four hours of thinking
and matters enormously is not the same as a task that asks for four hours of thinking
and matters not at all. So the load (the task's own size and depth) and the value (the
priority the user gave it) are reported separately, and the verdict — a burden, or
merely a big task — is a rule on top of them. The rule protects against the noise an
unprioritised task would otherwise create: "no priority" means "not stated", not
"matters not at all", so such a task is never called a burden. Every threshold is an
argument, because a threshold is a judgement about people, and a judgement belongs to
the caller that knows the user.

**The staleness threshold is the user's, not the tool's.** The user has a setting for
how long a task may sit before it counts as stale. A tool that applies a threshold of
its own tells the reader a task is fine when the user's own screen calls it stale.

**The planning tools may be given a set, not just a list.** `weekly_plan` and
`project_review` took a list of strings the model had made up. A reader who says "plan
my inbox" should get the app's inbox, resolved by the app's rules — so both tools take
the same named selection the read tools take.

**A failed call is an answer, not a crash.** The first half of this work already
settled that for the chat: a tool that fails is an answer the model can read. The
server's version of the same rule was wrong in a way worth naming: arguments that did
not fit the tool fell through to the internal tier and came back as a JSON-RPC
protocol error. A typo in an argument was reported as a broken server — the one thing
the caller cannot act on. Arguments that do not fit are the caller's mistake, and the
caller can correct one.

## Decision

**The read tools speak the app's vocabulary.** `list_tasks` takes a named selection,
may be narrowed by project, tags and priorities, may ask for summaries or full cards,
and comes back one page at a time with a cursor that names its selection. `get_task`
returns the same full card the list returns for one task.

**The selection is resolved by the app's own predicates**, and the read starts from the
live tasks rather than from a repository filter, because the repository's filters and
the domain's rules do not always agree.

**The answer carries the health of the selection** — how many are overdue, blocked and
stagnant — because "here are your 40 tasks" and "here are your 40 tasks, 9 of them
overdue" are different answers.

**Arguments that do not fit the tool are a validation error**, in the chat and in the
server alike.

**A page holds ten tasks**, and the cursor says how much is left. Ten is what a reader
reads in one go; the cursor is how the rest arrives.

**The answer carries the load and the verdict, not a single score.** A task's load, the
stress it creates, the value the user gave it and whether the two are out of proportion
are reported as measurements, because a single number would hide the distinction the
reader is asking about.

**The staleness threshold comes from the user's settings**, so the tool and the screen
never disagree about what has gone stale.

## Rationale

The alternative — leaving the tools as they are and hoping the model asks for what the
tools offer — is what the tools' current shape already assumes, and it does not work.
A model told "list tasks, optionally filtered by project" will ask for a project when
the user asked for their overdue work, and will report the answer as though it were the
answer to the question.

The alternative to the app's rules — a tool-specific implementation of "overdue" —
looks cheaper and is the wrong trade. The whole point of the read surface is that the
reader and the app are looking at the same data. A second implementation of the same
question is a second thing to keep right, and it is the kind of second thing that
disagrees quietly, in a direction nobody notices until a user does.

Reading the live tasks and filtering in memory looks like a performance compromise and
is not one: the read is a single query over the user's own rows, and the alternative
buys a narrower query at the price of a narrower *answer*. The cost of being right here
is a filter over a list the user already has in memory.

The fingerprint in the cursor is the smallest of the decisions and one of the most
important. Every other part of the read is right, and a cursor that quietly pages
through the wrong list undoes all of it in a way no reader could detect.

## Consequences

- "Which of my tasks are overdue?" gets an answer, and the answer is the app's answer.
- The vocabulary is offered in the schema, so the model chooses from it rather than
  guessing.
- A reader can narrow a set, and the narrowing reads the way people narrow.
- A long list is paged, and a page says how much is left.
- The read tools are slower than a pushed-down filter would be, and the difference is
  one filter over a list the app has already loaded.
- The selection is now a domain concept, shared by every reader of the user's tasks.
  Adding a named set means adding it to the app's rules, not to the tool.
- The server's tools and the app's tools now answer "which tasks are overdue" the same
  way, because they resolve it from the same rules.
- The arguments-don't-fit path is a validation error in both places, so a model that
  guesses wrong is told what to fix rather than being handed a protocol error.
- The read says how much each task asks for, and whether it weighs on the user more
  than it matters to them.
- The staleness threshold follows the user's setting, so the tool and the screen agree.
- `weekly_plan` and `project_review` can be pointed at a named set, and work on the
  app's answer rather than on a list the model made up.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskSelection.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/ListTasksTool.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/GetTaskTool.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/TaskToolDtos.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ai/TaskPageCursor.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskCognitiveLoad.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/TaskToolSupport.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskCognitiveLoadTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/tools/TaskReadSurfaceTest.kt`
- `mcp-server/src/main/kotlin/com/singularity/todo/mcp/ToolRegistrar.kt`
- `openspec/changes/archive/2026-10-22-the-read-surface/`
