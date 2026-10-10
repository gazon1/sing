---
title: "The Versioned Sync Schema Is Never Applied Or Verified"
date: 2000-01-01
status: RESOLVED
tags: ["deferred"]
---

**Status: RESOLVED 2026-10-07 — structurally.** `scripts/check-supabase-schema-integrity.py`
is now a blocking gate with a positive control and ten self-tests. It proves the header and
the body still describe the same set of functions, in both directions. It cannot verify an
md5: those are `pg_proc.prosrc` values in a live database and reading them needs
credentials, so the live half stays manual and is why this entry was not closed outright.

**Tracked as:** #221 (closed; the live half is recorded there as what remains)

**Found in:** 2026-10-07, while landing #184 part 2 — the first time the server's sync
schema was written into the repository at all.

`supabase/migrations/2026-10-07-sync_schema.sql` is 43 KB: ten tables, their indexes, ten
RLS policies, the grants, and twelve function bodies, captured by reading the live
project's catalog rather than retyping it. It is the only description of the server
schema that is reviewable, diffable, and available to an agent without credentials.

Nothing applies it and nothing checks it. `grep -rn "supabase" scripts/ .github/ .just/`
returns nothing; there is no applier, no verifier, and no task that takes `supabase/` as
an input.

The drift check that was performed — md5 of all twelve `pg_proc.prosrc` values, recorded
in the file header, all twelve matching byte for byte on 2026-10-07 — is a one-time
measurement written down as evidence. It is not a check. The first person to change
`sync_batch_apply` in the database makes those fingerprints false, inside a file that
otherwise reads as authoritative.

**Why not fixed here.** The live half needs credentials, so it cannot be a blocking gate
on CI. The structural half can. Shipping a gate that silently passes when it cannot
connect would be the exact defect class `2026-10-06-ci-single-gate-registry-and-leaf-split.md`
already records, so the honest state is the recorded one.

**Try first:** the structural half — parse the SQL and assert it is self-consistent (every
`CREATE FUNCTION` body has a header fingerprint, no object is declared twice, every table
a policy names exists). That needs no credentials and catches the dominant failure: edit
the file, forget the database.

---
