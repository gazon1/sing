# bulk-import-port

## What

Give the backup importer's write path a name and a boundary, so that the one
place in the application that deliberately bypasses the ordinary write guards is
a declared port rather than a property of a single class.

## Why

The write layer has been made auditable over several rounds: scoped writes
carry an owner, cross-owner writes are rejected before storage, and the
repositories own the write sequence. The importer bypasses all of that, by
design — a restore is a bulk load, and per-row guards would be both slow and,
on a partial backup, wrong.

The problem is not that the bypass exists. It is that the bypass has no *shape*.
It is expressed as "this class may", which means anything that later wants the
same thing — a first-run import, a profile clone, a one-shot migration — will
copy the pattern. The pattern is right there, and nothing points at the
sanctioned version.

The evidence that this is not hypothetical: a syntactic cross-owner write rule
was written and measured, and found eight matches of which seven were
legitimate. Distinguishing them requires knowing what the underlying query does
with the value, not what the call site looks like. A registry naming the one
sanctioned bypass was shipped instead, and it is honest about being a registry
rather than a gate. This change extends the same idea from a list of sites to a
list of *kinds* of path.

## Scope

### In scope

- A declared boundary for bulk import, taking an explicit target owner and
  routing its writes through the ordinary repositories.
- A check that pins which code paths use it, so a new bulk path either uses it or
  is named explicitly.

### Out of scope

- Routing ordinary writes through it. The per-row guarantees the repositories
  provide are the opposite of what a bulk load wants.
- Conflict resolution when imported rows already exist locally.
- Sync behaviour during import. Whether a large restore should enqueue a
  corresponding number of sync operations is a real open question and is not
  answered here.
- Detecting a cross-owner write syntactically. That was measured and rejected;
  see the references.

## Why this gets a spec

There is no observable behavior change. The spec is here to state the *contract*
of the new boundary — what it guarantees and what it explicitly does not — so
that the next person adding a bulk path knows which door to walk through. A
change with no spec and no behavior would look like an unexplained architectural
preference in a year.

## References

- `docs/decisions/2026-09-27-write-layer-soundness.md` (ledger #18: the
  syntactic rule, deferred as disproportionate)
- `docs/decisions/deferred-backlog.md#bulk-import-port`
- `docs/decisions/deferred-backlog.md#cross-user-write-rule-measured-and-rejected`
- `openspec/changes/baseline-write-pipeline` (backup/restore is explicitly
  out of scope there; this change adds the missing boundary)
- Issue #82

## Status

**Proposed.**
