---
type: Architecture
title: Config
description: Remote config cache repository.

canonical_for:
- K-config-001

sources:
- id: remote-config
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/core/config/RemoteConfigCacheRepositoryImpl.kt
generated:
- by: agent
  source_state:
    remote-config: 695dd2c9c0fa508e8c52c3ff2f8c239590cc4b10
  body_state: bb2476e0a155eb6a0feb69b2298ae8aeb4a25b05d537be549525c0d59c3439ac
verified:
- by: human:maintainer
  source_state:
    remote-config: 695dd2c9c0fa508e8c52c3ff2f8c239590cc4b10
  body_state: bb2476e0a155eb6a0feb69b2298ae8aeb4a25b05d537be549525c0d59c3439ac
---

# Config

RemoteConfigCacheRepositoryImpl caches remote config.
