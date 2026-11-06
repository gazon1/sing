---
type: Architecture
title: Gate
description: App version gate state.

canonical_for:
- K-gate-001

sources:
- id: version-gate
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/feature/gate/domain/state/AppVersionGateState.kt
generated:
- by: agent
  source_state:
    version-gate: 209a18400d3acf3685e188e2690f5320bdc04935
  body_state: b987964fd638e111d8d266b449e341c06bf6b4ca69c19ceb970909203456e338
verified:
- by: human:maintainer
  source_state:
    version-gate: 209a18400d3acf3685e188e2690f5320bdc04935
  body_state: b987964fd638e111d8d266b449e341c06bf6b4ca69c19ceb970909203456e338
---

# Gate

AppVersionGateState manages version gating.
