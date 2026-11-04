# Change: the attributes come before the sections that add to the task

**Issue:** #1754 · **Spec:** `tasks`

---

## What

The task editor SHALL draw its attribute rows — priority, estimate, due date, start
date, project, tags, recurrence, pin, the dependencies card and the workflow state —
under the description and under the content that asks the person for something, and
SHALL draw the sections that add to the task — the checklist, the subtasks, the
attachments, the logbook, the backlinks — after the rows.

Today the sections come first. On a 360 dp phone a task opened with an empty
checklist, no subtasks and no attachments showed, in this order: title,
description, "Checklist (0/0)", "Subtasks (0/0)", "Attachments (0)", the timer, the
logbook, the state, "No priority", the estimate, the due date, "No project", "Add
tags". The due date was below the fold and the priority was four cards down: the
fields a person fills in were the last things on the screen.

## Why

An empty section is not empty on the screen. The checklist of a task with no
checklist is still a card with a heading and an add-row, and three of them are
taller than the description and the title together. They are drawn before the rows
because the editor drew the caller's slot where the description ended — a
position nobody chose for the sake of the rows.

The order is also the argument for it. The rows are what the task *is*: when it is
due, how important it is, which project it belongs to, which tags name it. The
sections are what may be attached to it, and they grow with use. A thing that
grows belongs below the thing that is always there.

## What changes

- The editor draws the rows before the caller's sections, and the caller's
  sections last.
- The dependencies card moves out of the head of the column and into the
  attribute group, after the pin row (#2010). It keeps its behaviour — it appears
  when there is something to pick from, shows its relations, and opens the
  dependency picker — but it no longer pushes the priority and the due date down
  by a row on a task that has relations.
- The workflow-state row moves below the checklist and attachments rows, so on the
  task detail, which draws no such rows, it is the last of the group.
- The rows keep their pickers and their callbacks; beyond the two moves above, the
  order of the column is what changes.
- The create editor is unaffected in practice: it gives the editor no sections, so
  the column is the rows alone.

## Out of scope

- Collapsing the empty child cards into one row. It is the other way to fix the
  same symptom, and the issue names the reorder as the fix; a card that shows one
  row instead of three hides the checklist and the attachments behind a summary.
- The sections' own content, their headers and their add-rows.
- Any change to what a row does when it is tapped.
