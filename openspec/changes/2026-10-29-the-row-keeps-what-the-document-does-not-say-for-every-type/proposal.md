# the-row-keeps-what-the-document-does-not-say-for-every-type

**Status:** proposed · **Issues:** [#1026](https://github.com/gazon1/sing/issues/1026) · **ADRs:** `2026-10-29-the-row-keeps-what-the-document-does-not-say-for-every-type`

## What

A document of any synchronised type that arrives from another device is applied
against the row that is already on this device — and the fields the document
does not carry are left alone.

The rule was written for the task (`the-row-keeps-what-the-document-does-not-say`),
and it was implemented for the task alone: the merge was a function of `Task`,
and the writer had one path that used it and five that did not. The other five
types decoded the document on its own and wrote it whole, so a document that
carried no key wrote the *default* over the row — a note lost its body, a tag
lost its group, a time entry lost its note — and the pull reported success.

## Why

The wire is the reason the reading matters, and it is the same reason it
mattered for the task. A document carries the fields the sending device knew
about: the allow-list says what a device sends, and it grew. A device that
predates a field sends no key for it.

Read as "no such field", that missing key is a deletion — and an invisible
one: the row is written, the pull reports success, and the field is simply not
there any more on this device.

The reason it was written for the task alone was not a decision. It was where
the defect was found, and nobody said the rule was about tasks: the decision
that stated it named the task's path, and the writer that implemented it had
one path for the task and five that decoded the document on its own.

## How

- The merge is one function, and it speaks JSON: the row is given as the JSON
  the model writes — the same names the document uses — and the two are
  compared key by key. The document wins wherever it speaks; where it says
  nothing, the row keeps saying what it says.
- Every type that is synced but the project is read against the row that is
  already here. The
  writer's path is per type only in what it reads and in how it decodes.
- The parts that live outside the row stay the caller's business: which keys
  are parts is per-type knowledge, and the merge does not know about them.
- The row that is read is this profile's row. A document of another profile is
  written as it says.
- A claim is still a claim: a document that carries `bodyMarkdown: null` says
  the note has no body. Only the absence of the key is an absence of a claim.

## What this does not do

- It does not change what a device sends. The document is still written whole
  by the sender; this is about how the receiver reads what arrived.
- It does not change the protocol, and it does not change the rule of the
  series: a document that changes a task of a series still brings the series
  to one thing about the work.
- It does not cover the project type. Its inheritance lives outside the row,
  in a cross-reference table, and the project is written through
  `replaceInheritedTagGroups` — a write path of its own, and a separate change.
- It does not change the parts that live outside the row. Their claims are
  their own change, and the merge leaves their keys alone so that both readings
  can hold.

## Links

- ADR `2026-10-29-the-row-keeps-what-the-document-does-not-say-for-every-type`
- ADR `2026-10-23-the-row-keeps-what-the-document-does-not-say` — the rule,
  and the task it was written for
- ADR `2026-10-23-a-document-says-only-what-it-carries` — the parts that live
  outside the row
- Issue [#1026](https://github.com/gazon1/sing/issues/1026)
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/DocumentMerge.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncDocumentWriter.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/timetracking/domain/port/TimeTrackingRepository.kt`
  — the read the time entry needed and did not have
