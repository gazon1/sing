---
title: "Ci Push And Pull Request Both Fire Causing Duplicate Runs"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** #475

**Found in:** 2026-10-07, background agent investigation of CI duplicate runs.

**Symptom:** CI runs twice for every PR commit. `ci.yml` triggers on both `push` (to main) and `pull_request` (to main). The concurrency group at lines 22-24:

```yaml
concurrency:
  group: ci-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}
```

When a PR branch pushes to main: the `push` event fires with `cancel-in-progress: false` (because `github.event_name == push`), so it is never cancelled. The `pull_request` event fires simultaneously but may not win the cancellation race.

**Root cause:** `cancel-in-progress` is conditional on event type, so the `push` run is always unconditional. There is also no `paths` filter.

**Fix:** Set `cancel-in-progress: true` unconditionally, or rethink the concurrency group key so both event types share a group where cancellation applies to whichever fires second.

---
