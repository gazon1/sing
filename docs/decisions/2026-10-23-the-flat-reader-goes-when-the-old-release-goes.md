---
title: "The flat reader goes when the old release goes"
date: 2026-10-23
status: accepted
tags: [proposals, codec, serialization, maintenance]
---

# The flat reader goes when the old release goes

## Context

The flat format — the one the builds before the sealed format wrote — is still
read, and it is read by hand: `decodeFlat` and the four helpers that unpack
what it packed (the list of checklist texts in one column, separated by Unit
Separator; the time entries in another, separated by Record Separator).

The reader is read-only now, and it stays until the oldest build in the user's
hands is one that writes the sealed format. That was decided; what was missing
is *what to delete when the day comes* — and a removal that is a hunt rather
than a checklist is a removal that does not happen.

## Idea

Write the removal down as a checklist, with the trigger and the order.

## Decision

**The trigger.** The flat reader goes when the release that ships the sealed
format has been out long enough for the previous release to fall out of
support. The sealed format shipped with the change "the proposal loses
nothing"; the trigger is the release *after* that one being the oldest
supported build.

**The checklist, in the order it is done.**

1. `decodeFlat` — the reader of the flat format.
2. The four helpers it packs and unpacks with: the list packers for the
   checklist texts, the subtask titles and the tag names, and the time-entry
   record packer.
3. `readPriority`, `readKind`, `readDate` and `readTime` in the codec. They
   duplicate the readers the model already has — `TaskValue.readPriority`,
   `readKind`, `readDate`, `readTime` — and they exist only to read the flat
   format. The model's readers are the ones that stay.
4. `ProposalPayloadSep` and the packing helpers that use it.
5. The `carriesStructure` check. Once there is no flat format, every blob is
   a sealed blob, and the reader is the sealed reader.
6. The test "the flat format of the builds before this one still reads" — last,
   with the reader it covers.

**What is not in the checklist.** The sealed writer, the sealed reader and the
`Unknown` kind stay. The `Unknown` kind is what a blob nobody can read becomes
— that is not a format question, it is the contract of the change itself.

**The check that the checklist is not out of date.** The steps above name
files and functions; a step that no longer exists is a step that has been done
or a checklist that has drifted. The flat-format test is the marker: when it
goes, the reader has gone with it.

## Rationale

A reader kept "for now" without a plan is a reader that is still there five
years later, still carrying the packing helpers, still reachable, still one
thing a change can be read as by mistake.

The plan is a checklist because the removal is mechanical: delete these
functions, in this order, and the test goes last. Nothing about it needs
judging, so nothing about it needs a discussion — it needs a list.

The order matters in one place only: the test goes with the reader, not
before it. And the helpers that duplicate the model's readers go before the
reader that uses them.

## Consequences

- **The removal is a checklist, not a hunt.** Whoever does it has the list.
- **The trigger is written down.** "The previous release falls out of support"
  is a fact someone can check.
- **The flat format stays until then.** A row the previous release wrote is a
  row the user still has.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/data/ProposalKindCodec.kt`
  — the functions the checklist names.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/proposals/data/ProposalMapperTest.kt`
  — the test that goes last.
- `docs/decisions/2026-10-23-the-stored-change-is-the-models-own-shape.md`
  — the decision that the flat format is read until then.
- `docs/decisions/2026-10-23-the-format-is-told-by-its-shape.md`
  — the reader this checklist shortens.
