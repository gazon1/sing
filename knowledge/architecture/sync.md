---
type: Architecture
title: Synchronization Architecture
description: Runtime architecture of task synchronization.

canonical_for:
- K-sync-001

sources:
- id: sync-engine
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncEngine.kt
- id: sync-outbox-worker
  resource: 
    repo:/shared/src/androidMain/kotlin/com/singularity/todo/core/sync/work/SyncOutboxWorker.kt
generated:
- by: agent
  source_state:
    sync-engine: bce40895844541694eb5c5ba0786408d9c72cc09
    sync-outbox-worker: 22640a95939d93e858a70b728f814cf82cba93f5
  body_state: 167664fef025d947384bd62aa6f7036ad1fea065c3b39e88768d66f38df9dc24
verified:
- by: human:maintainer
  source_state:
    sync-engine: bce40895844541694eb5c5ba0786408d9c72cc09
    sync-outbox-worker: 22640a95939d93e858a70b728f814cf82cba93f5
  body_state: 167664fef025d947384bd62aa6f7036ad1fea065c3b39e88768d66f38df9dc24
---

# Synchronization

SyncEngine coordinates push/pull of tasks. SyncOutboxWorker drains the outbox on Android.
