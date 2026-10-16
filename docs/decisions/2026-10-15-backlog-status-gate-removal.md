---
title: "The backlog filesystem is gone; the backlog gate now reads GitHub issues"
date: 2026-10-15
status: accepted
deciders: max
tags: ["backlog", "gates", "adr-corpus"]
---

## Context

PR #645 deleted `docs/decisions/deferred/` after migrating 129 entries to GH issues.
The `backlog_status` gate was removed at the same time because it read a filesystem
directory, not GH issues, and could not distinguish a stale entry from a resolved one.

A small cleanup wave remained: the one-time migration script, the now-dead vocabulary
fields in `adr-corpus.json`, and the four file↔issue invariants in
`check-backlog-issue-refs.py`.

This file previously kept all of that inside a `context:` / `decision:` / `rationale:`
frontmatter block and never closed the fence, so `adr_corpus.frontmatter()` returned
`None` and `check_adr_status.py` reported "no parseable frontmatter block". It was the
only file of 597 that used that shape, which is how it shipped: a shape nothing else
demonstrates is a shape nothing checks for.

## Decision

1. `scripts/normalize-backlog-status.py` is deleted — the one-time migration is done.

2. `backlogVocabulary` is removed from `config/docs/adr-corpus.json`. The backlog
   vocabulary (`open`, `partial`, `deferred`, `closed`, `resolved`) was specific to
   the `deferred/` files and has no other consumer.

3. `deferredDir` is **kept** in `adr-corpus.json` and `adr_corpus.py` for Kotlin
   backwards compatibility (`AdrCorpus.kt` references it). `deferred_in()` returns
   `[]` since the directory is gone.

4. `backlog_statuses` and `backlog_open_states` fields are removed from
   `AdrCorpusConfig` in `adr_corpus.py`. The Python consumers
   (`check-backlog-status.py`) are gone; Kotlin consumers keep the dataclass fields
   they need.

5. `check-backlog-issue-refs.py` is rewritten to pure GH validation. The four original
   invariants (I1–I4) referenced local files:

   ```
   I1 — every `Tracked as:` in a file cites an existing GH issue   ← DEAD (no files)
   I2 — OPEN/PARTIAL files cite OPEN GH issues                    ← DEAD (no files)
   I3 — OPEN/PARTIAL files have a `Tracked as:` or reason          ← DEAD (no files)
   I4 — every OPEN GH issue has a `Backlog:` field                 ← KEEPS
   ```

   Only I4 remains. The script now reads `config/docs/issues-snapshot.json`,
   iterates OPEN issues, and fails if any lacks a `Backlog:` field in its body.

6. The `backlog_issue_refs` gate shim in `sgate.commands.backlog_issue_refs`
   is updated to parse the simplified output.

7. `backlog_issue_refs` is removed from `KNOWN_NON_GATES` in `manifest_verify.py`.

## Rationale

Keeping `deferredDir` costs nothing and avoids a Kotlin-only regression. The runtime
cost is an empty list.

Dropping I1–I3 is not a loss of coverage: those invariants tracked consistency
between a local file and a GH issue. With the file side deleted, they reduce to
"the file side must exist" — tautologically false. The remaining invariant (I4)
is the one that actually matters: every open backlog GH issue must say which
backlog entry it implements.

Keeping `backlogVocabulary` in the JSON while all consumers are gone would be
TechnicalDebt for no reason. Deleting it is free.

## Consequences

- `check-backlog-issue-refs.py` now exits with a non-zero code only when an OPEN
  GH issue lacks a `Backlog:` field. This was already the only meaningful signal
  the gate produced; the file-based checks were silently passing because there
  were no files to check.
- `normalize-backlog-status.py` is no longer available for any future migration
  (it has already run).
- Kotlin `AdrCorpus.kt` continues to receive `deferredDir` from the config; the
  field is unused since the directory is gone.

## Links

- PR #645: Migrate deferred/ to GH issues, delete backlog_status gate
- PR #646: Record Phase 5 eval PASS
- #516 — original backlog vocabulary decision
- #636..#643 — new backlog GH issues created
