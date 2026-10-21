---
title: "The registry says only what it checks"
date: 2026-10-21
status: accepted
tags: [architecture, text-fields, testing]
---

# The registry says only what it checks

## Context

The rewrite table in `.agents/skills/singularity-todo-text-field-states/SKILL.md` is the
one published copy of "who may rewrite this field?". `TextFieldRewriteContractTest`
reads it, and the table has seven columns:

| File | Field | Upstream | Source | Owner | Counter | Advances |

Six of them are checked against the code. Two are not:

- **Field** — which field of that file the contract is about. The scan is *per file*:
  it proves that a file declares the shapes it declares, but not that the field the
  table names is the field the file has.
- **Upstream** — who may write the value, in prose. The answer it gave now lives in
  the *kind* of source, which is a type: a `StaticText` source says the upstream
  replaces the value only with one the field never produced, an `OwnedText` says it may
  replace it with anything and carries the count of the replacements.

An unchecked column is a claim that drifts silently. Someone adds a field to a file and
does not update the table, and the table keeps naming a field the file has not had for
months — while the build is green, because the build checks the other six columns.

## Idea

A column of a registry is either checked or gone. And the shape of a field is not a
taste: it follows from who owns the text, and the owner is visible in the code.

## Decision

**The registry keeps only what it checks, and the shape has a procedure.**

- **The Field column is checked.** Every content word of the cell has to be a word of
  the site's own vocabulary: the identifiers of its declarations, split into their
  camel-case parts, plus the path's words. `nameSource` says *name* and *source*,
  `baseUrl` says *base* and *url*, and a file named `NoteEditorScreen.kt` says *note*,
  *editor* and *screen*. A row that names a field the file does not have fails the
  build.
- **The Upstream column is gone.** The question it answered is answered twice over, by
  things that cannot drift: the **Source** column says what the upstream may do, and the
  **Owner** column names the file that holds the value — and a file that holds the
  value is the party that writes it.
- **The shape has a procedure, in the skill.** One question: does anything outside the
  field read or write the text? Nothing does → **local**. Something does, and the field
  only shows what it is given → **anchored**. Something does, and the field is editable
  → **bridged**. The order matters: an anchored field somebody also wants to write is
  bridged.

## Rationale

- **The check is a word check, not a proof, and it says so.** "end" is a word of
  "start, end, quote, note", and a file with an `endDate` would satisfy it. That is the
  price of a textual check — the same price the counter checks already pay — and it
  catches the failure the column actually had: a row that names a field the file does
  not have. The failure message names the site and the missing words.
- **The prose went rather than the column.** The Upstream column was not *wrong*; it
  was *unverifiable*, and unverifiable prose in a registry is a promise nobody keeps.
  The information it carried is still there — in the Source column and in the Owner
  column — and it is there in a form the build reads.
- **The procedure is the thing that was missing.** The shape was chosen by the person
  writing the screen, and nothing told them which one was right or when they got it
  wrong. The consequence was the quiet one the contract work was about: the field that
  was supposed to be bridged stays local, the value is dropped on the way in, and the
  screen silently stops saving.
- **The procedure has a mechanical trace.** A file that calls the bridged helper has to
  import it, so the import list is the compiler's own record of the shape. The
  procedure's outcome is therefore checked: a row in the table means somebody outside
  the field holds the value, and that means the field is bridged — which the shape scan
  already proves.

## Consequences

- The registry has six columns, and every one of them is read by the build.
- The Field column's words are checked against the file's identifiers. A row that names
  a field the file has not got fails, with the site and the missing words in the
  message.
- The skill now says *how* to pick the shape, not only what the three shapes are.
- The Upstream prose is gone, and with it the last part of the registry that could
  drift without anybody noticing.

## Links

- Issue #816 — "the static kind can still lie, and the registry describes what it
  cannot check".
- `2026-10-21-the-genui-count-travels-with-the-value` — the count travels with the
  value, and the source's kind says what the upstream may do.
- `.agents/skills/singularity-todo-text-field-states/SKILL.md` — the procedure and the
  registry.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldRewriteContractTest.kt`
  — the reader of the table.
