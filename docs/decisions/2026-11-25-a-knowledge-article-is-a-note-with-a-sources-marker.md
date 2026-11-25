---
title: "A Knowledge article is a note with a Sources marker, and the statement classifies it"
date: 2026-11-25
status: accepted
tags: [notes, ai, mcp, knowledge, database]
---

# A Knowledge article is a note with a Sources marker, and the statement classifies it

## Context

The Knowledge Wiki epic ([#3182](https://github.com/gazon1/sing/issues/3182)) asks for
one owner to own **many** Knowledge articles instead of one brief
([#3184](https://github.com/gazon1/sing/issues/3184)). The model is already in place:
a brief is a note attached to exactly one project or task, and what makes it a brief
is the `## Sources` section in its body
([2026-11-22-a-knowledge-brief-is-a-note-attached-to-its-owner.md](2026-11-22-a-knowledge-brief-is-a-note-attached-to-its-owner.md)).

What is missing is a read that answers "which articles does this owner have". The read
that exists answers "what is the newest brief of this owner", and it finds that brief
by filtering a bounded page of attached notes **in Kotlin**, after the statement had
already cut the page. The reason it needed a page of 200 is the reason the new read
cannot use one: the classification is a property of the body, no index answers it, and
an owner whose twenty newest attached notes are ordinary notes hides the article it
still owns.

The acceptance criteria for this issue are explicit about the two things that matter:

- **the classification happens before the pagination** — an older article cannot
  disappear behind newer non-article notes;
- **legacy briefs stay discoverable and readable**, without a destructive migration and
  without rewriting a single user note.

## Idea

1. **A new kind — `NoteKind.Knowledge`** — for articles, alongside the marker. The
   kind is a column, so the statement can filter on it, and the read becomes
   `WHERE kind = 'Knowledge'`. Legacy briefs would have to keep the marker, so the
   statement would have to carry the marker anyway — for the legacy half of the
   library, which is the half that must not be lost.
2. **A stored flag** — a column that says "this note is an article", written by the
   app when the body is written, backfilled for the notes that already exist. The
   statement filters on the flag, and the flag answers the question the marker
   answers.
3. **The marker, classified in the statement.** The rule stays what it is — a note
   whose body carries the `## Sources` section — and it moves from Kotlin into the
   `WHERE` clause, so the page is cut from the articles and not from the attached
   notes.

## Decision

Option 3.

**The marker stays the marker.** No new kind, no new column, no new table, no
migration, no change to the sync payload, and no change to the backup format. An
article is a note that carries the `## Sources` section, exactly as a brief was, and
a brief written before this change is an article without anybody touching it. The
write path does not change either: `create_note` with a `projectId` or a `taskId`
already produces an attached note, and it is an article when its body carries the
section — which is what the tool's description has been telling the model all along.

**The classification moved into the statement.** `watchKnowledgeArticlesForProject`
and `watchKnowledgeArticlesForTask` filter on the marker inside the `WHERE` clause,
so `LIMIT` cuts the page of *articles*. The older article behind sixty newer ordinary
notes is still on the page, because the page was never a page of ordinary notes.

**The rule is written twice, and a test holds the two together.** SQLite has no
regular expressions, so the statement cannot ask a Kotlin object whether a body
carries the heading. The rule therefore exists in two forms —
`KnowledgeArticle.SQL_MARKER` for the statement and `KnowledgeArticle.carriesSources`
for the code that holds a note in memory — and a test runs both over the same bodies
and fails when they disagree. Two forms of one rule is a risk; two *answers* to one
question is a defect, and the test is what makes the difference a fact rather than a
hope.

The rule is a **section**, not a mention: the body has to carry a heading whose text is
exactly `Sources`. The HTML form is `>Sources</h` (an element whose text is exactly
`Sources`, closed by a heading tag). The Markdown form is a **line** that is the
heading — `## Sources` at any level, or the bold form `**Sources**` — with the spaces
and tabs taken out, so `##Sources` and `## Sources` are the same heading. A note whose
prose merely mentions sources carries none of them and stays an ordinary note.

Line-anchoring is what SQLite can evaluate. It has no regular expressions, so the
statement cannot ask "does this line consist of hashes and then the word Sources"; it
strips the whitespace, collapses a run of hashes into one, and looks for the marker
between two line feeds. The same normalisation happens in Kotlin, and the test that
runs both over the same bodies is what keeps them the same rule.

The rule is **not** the old `KnowledgeBriefShape` regex moved verbatim — that one
matched a heading by regular expression and fell back to the Markdown projection of the
HTML body. It is the same *section*, expressed as a rule the statement can run:

- the old regex accepted a bold *paragraph* — `<p><strong>Sources</strong></p>`, which it
  projected into Markdown and then read as `**Sources**`; the new rule does not, because a
  bold paragraph is not a section — and the old rule's own note said as much ("the
  section has to be a heading, not a mention"). A Markdown line that is exactly
  `**Sources**` is still an article under both rules;
- the old regex accepted a heading whose text sat on the next line (`##` then `Sources`);
  the new rule does not, because no body is written that way.

Everything the app writes — `<h2>Sources</h2>` in HTML, `## Sources` in Markdown — is
accepted by both, so no brief changes meaning.

**The classification is a property of the body, so it follows the body.** A user who
edits the section out of an article has removed the article — there is no flag to go
stale, and no write path that has to remember to maintain one.

## Rationale

The kind was the obvious candidate and it does not pay for itself. A `Knowledge` kind
would make the *new* articles cheap to enumerate, and leave the *legacy* briefs — the
ones the issue is most concerned about — to the marker. So the statement would carry
both, and the new kind would add a second way for a note to be an article: the kind
for the new ones, the marker for the old ones, and a rule about which wins. Worse,
every creation path would have to be able to write the kind — the note editor, the
proposal pipeline, the seed writer, the sync pull — and a note written by a path that
forgot would be an ordinary note forever. The kind would also travel: it is a stored
enum value, so it appears in the sync payload, and a device running a build that does
not know the value cannot read a note that carries it.

The stored flag has the same problem from the other side. A flag is derived state, and
derived state has to be written by every path that writes the body — the editor's
autosave, the proposal apply, the sync pull, the seed writer — and each of them can
be wrong. Worse, it can be *stale*: the flag says "article" while the body says
otherwise, and the two answers the same question differently. A migration that
backfills the flag has to answer the same marker question in SQL, which is the
question the marker was asked to avoid.

Keeping the marker and moving it into the statement answers the acceptance criteria
directly, changes nothing that another device or another version has to understand,
and leaves the existing single-brief read working: `get_knowledge_brief` still finds
the newest brief, and now there may be more than one.

## Consequences

- One owner may have many articles, and each is addressed by its note id. The
  articles of a project and the articles of a task are separate reads: an article
  attached to a task is not an article of the project the task belongs to.
- The reads are scoped to the active profile and to the exact owner, exclude archived
  and deleted notes, and are bounded by a limit the caller states. The order is
  `created_at DESC, id ASC` — the id breaks the tie, so two reads cannot swap two
  articles written in the same second.
- The legacy briefs are articles: same rows, same bodies, no migration, no rewrite.
- Nothing changed in the database schema, the sync payload or the backup format, so
  there is no compatibility story to tell a second device — the body is what makes a
  note an article, and the body is what was always synced and backed up.
- The rule is written twice. A test holds the two forms together; the failure it
  guards is a statement that answers a different question than the code.
- The read is a *repository* read only. The tool surface that lists and reads several
  articles is [#3186](https://github.com/gazon1/sing/issues/3186); the Project
  Knowledge library is [#3185](https://github.com/gazon1/sing/issues/3185). This
  change is the persistence and query contract they both stand on.

## Links

- Issue [#3184](https://github.com/gazon1/sing/issues/3184) — parent epic
  [#3182](https://github.com/gazon1/sing/issues/3182)
- [2026-11-22-a-knowledge-brief-is-a-note-attached-to-its-owner.md](2026-11-22-a-knowledge-brief-is-a-note-attached-to-its-owner.md)
  — the brief contract this one extends
- `core-domain/src/commonMain/kotlin/com/singularity/todo/feature/notes/KnowledgeArticle.kt`
  — the rule, in both of its forms
- `core-database/src/commonMain/kotlin/com/singularity/todo/core/database/Daos.kt` —
  the two statements that classify before they paginate
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/notes/KnowledgeArticleRepositoryTest.kt`
  — the contract, against real SQLite
- `docs/architecture/knowledge-briefs.md` — the contract as the app and its agents see it
