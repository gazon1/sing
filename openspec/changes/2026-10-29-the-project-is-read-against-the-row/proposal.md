# the-project-is-read-against-the-row

**Status:** proposed · **Issues:** [#1026](https://github.com/gazon1/sing/issues/1026) · **ADRs:** `2026-10-29-the-project-is-read-against-the-row`

## What

The project is read against the row that is already here, and the inheritance it
carries is a claim like every other claim a document makes.

## Why

The rule of #1026 was generalised to every synchronised type but the project, and
the reason was the inheritance: `inheritedTagGroupIds` has no column on
`ProjectEntity` — it lives in a cross-reference table — and the project is written
through `replaceInheritedTagGroups`, a write path of its own. A merge that spoke
about the inheritance would have had to change that write, so the write was left
alone and the type was left out of the rule.

So the project kept the two defects the other types had, and one more:

- a document that carried no `description` wrote the model's default over the row;
- a document that carried no `name` did not decode at all, and the pull dropped it;
- and the write always replaced the table of inherited groups with what the
  document carried. A document that carried no key carried an empty set — the
  field has a default — so the project's inherited groups went away on this
  device. The row was written and the pull reported success.

The third one is the reason the type was left out, and it is the reason the write
has to change before the rule can apply: the claim about the inheritance has to
reach the write, or the write cannot tell "the project inherits nothing" from
"the document says nothing about the inheritance".

## How

- The project is read against the row that is already here, exactly as a task, a
  note, a tag, a tag group and a time entry are.
- The inheritance is a claim, and the claim travels with the write: the write
  takes a document — the project and the claim about the inheritance — and
  `null` means the document says nothing about it.
- The table is replaced only when the document makes a claim. A claim of an empty
  set takes the groups away; no claim leaves them.
- The row makes no claim about the inheritance: the key is filtered out of the row
  that is read, because a row read through `get()` cannot see the table, and its
  empty set is a reading artefact rather than a claim.
- The profile boundary is the boundary of every other type: a document of another
  profile is written as it says, and this profile's row does not fill it.

The requirement of the change before this one is modified by this one: the
project is no longer excluded from the rule of every synchronised type, and the
requirement of the inheritance is added beside it. The two are archived in that
order — a requirement has to be in the spec before it can be modified.

## What this does not do

- It does not change what a device sends. The document is still written whole by
  the sender; this is about how the receiver reads what arrived.
- It does not change the protocol, and it does not change what a project pushes: a
  project is still enqueued whole, with the inheritance it has.
- It does not change the parts of the task document — the tags and the blocking
  edges are their own change.

## Links

- ADR `2026-10-29-the-project-is-read-against-the-row`
- ADR `2026-10-29-the-row-keeps-what-the-document-does-not-say-for-every-type` —
  the rule, and the type it left out
- ADR `2026-10-23-the-row-keeps-what-the-document-does-not-say` — the rule, and
  the task it was written for
- ADR `2026-10-23-a-document-says-only-what-it-carries` — the parts that live
  outside the row
- Issue [#1026](https://github.com/gazon1/sing/issues/1026)
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncDocumentWriter.kt`
  — `projectDocument`, where the project meets the row
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/domain/model/ProjectDocument.kt`
  — the document and the claim
