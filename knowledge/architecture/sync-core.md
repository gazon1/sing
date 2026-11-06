---
type: Architecture
title: Sync Core
description: Sync work scheduler.

canonical_for:
- K-synccore-001

sources:
- id: sync-scheduler
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/core/sync/work/SyncWorkScheduler.kt
generated:
- by: agent
  source_state:
    sync-scheduler: c92ed8d1198ab3a3c53d29b2c9b549020cd4ddfd
  body_state: 7edfd6976c7dd81bc07b16953c1588c0d2f6f89945fd47a4832b9f2f92932d4e
verified:
- by: human:maintainer
  source_state:
    sync-scheduler: c92ed8d1198ab3a3c53d29b2c9b549020cd4ddfd
  body_state: 7edfd6976c7dd81bc07b16953c1588c0d2f6f89945fd47a4832b9f2f92932d4e
---

# Sync Core

SyncWorkScheduler schedules sync work.
