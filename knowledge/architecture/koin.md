---
type: Architecture
title: Koin DI Modules
description: Koin module aggregation and per-domain modules.

canonical_for:
- K-koin-001

sources:
- id: modules
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/core/di/Modules.kt
- id: core-di
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/core/di/CoreDiModule.kt
generated:
- by: agent
  source_state:
    modules: c1250cca78e263efda45aef5c8f30aa861b9ed88
    core-di: 26fcf1b0259689c717aad083c8900fc56c6e20d1
  body_state: b8967aff7843f903628f88dfbc87055e75eb281c0f81a24ef915cd50b07f4261
verified:
- by: human:maintainer
  source_state:
    modules: c1250cca78e263efda45aef5c8f30aa861b9ed88
    core-di: 26fcf1b0259689c717aad083c8900fc56c6e20d1
  body_state: b8967aff7843f903628f88dfbc87055e75eb281c0f81a24ef915cd50b07f4261
---

# Koin

Modules.kt aggregates per-domain modules; CoreDiModule binds core services.
