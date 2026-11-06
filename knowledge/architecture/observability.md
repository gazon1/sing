---
type: Architecture
title: Observability
description: Background crash reporting.

canonical_for:
- K-observability-001

sources:
- id: crash-reporting
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/core/observability/BackgroundCrashReporting.kt
generated:
- by: agent
  source_state:
    crash-reporting: 267c54d9c03574c64104c0cc47e5a2ea0af82689
  body_state: 0a18a9a9d458ddc02c4f2eaf027a0d754efaf29bcd066ea79ff6915c7cdb3d76
verified:
- by: human:maintainer
  source_state:
    crash-reporting: 267c54d9c03574c64104c0cc47e5a2ea0af82689
  body_state: 0a18a9a9d458ddc02c4f2eaf027a0d754efaf29bcd066ea79ff6915c7cdb3d76
---

# Observability

BackgroundCrashReporting captures crashes.
