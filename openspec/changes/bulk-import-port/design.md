# bulk-import-port — Design

Required: new architectural pattern, cross-cutting (touches the importer and the
write layer's rules), and rollback-risk (routing restore through repositories
could introduce per-row guard failures into a path that must not fail).

## Do not route restore through the ordinary write path blindly

The naive implementation — call the repositories, one row at a time — introduces
two new failure modes into a path that currently cannot produce them:

1. **Guard rejections.** An ordinary write checks ownership before storing. A
   restore legitimately writes rows for a target owner that is not the currently
   active one. Every such row is now a guard failure unless the repositories
   gain a bulk variant, which is the real work.
2. **Partial state.** A repository sequence is per-row atomic. A restore that
   fails halfway leaves the database in a state neither the old nor the new
   behavior could produce.

**Rollback risk: high, and worse than the status quo in a specific way.** A
restore that fails halfway is worse than a restore that never started, because
the user has a backup and now also a corrupted install. Whatever this change
does, it must not make partial restore reachable.

**Recommendation:** the bulk port takes an explicit target owner, uses
owner-scoped repository variants that already exist for the import path, and
runs inside a single transaction at the outermost layer. Per-row atomicity is
replaced by all-or-nothing, which is strictly stronger for this use.

## What the port guarantees, and what it does not

The contract to state in the spec:

- **Guarantees:** a bulk import writes only for the owner it was given; it does
  not consult or alter the active profile; it is all-or-nothing.
- **Does not guarantee:** row-level validation of the imported content. A backup
  containing a row that violates a domain invariant is restored as-is. That is
  deliberate — the backup is the user's data, and rejecting it is worse than
  carrying it.
- **Does not guarantee:** that the imported data is *current*. A restore does not
  merge with local state; the scope question is deferred, not answered here.

## The check, and why it is a registry

The measured finding stands: a syntactic rule cannot distinguish a legitimate
owner-scoped write from an illegitimate cross-owner write, because both take an
owner and both call the same storage method. Seven of eight matches were
legitimate.

So this is a registry, and it says so. The value is the same as the existing
one: making the sanctioned path greppable, and failing when the *set of callers*
changes without someone having decided to change it. It does not catch a new
cross-owner write, and the spec must not claim it does.

**The one thing it does catch is the thing that matters here:** a new bulk path
appearing without a declaration. That is a purely syntactic question — does this
code call the import boundary — and it is answerable.

## Reference

The write-layer rationale is in `docs/decisions/2026-09-27-write-layer-soundness.md`
and `docs/decisions/2026-10-03-assert-canwrite-adr.md`. Neither is restated
here. The measured rejection of the syntactic rule is recorded in
`docs/decisions/deferred-backlog.md#cross-user-write-rule-measured-and-rejected`.
