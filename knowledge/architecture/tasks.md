---
type: Architecture
title: Task Health
description: Task health and cognitive load computation.

canonical_for:
- K-tasks-001

sources:
- id: task-health
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskHealth.kt
- id: task-cognitive-load
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskCognitiveLoad.kt
generated:
- by: agent
  source_state:
    task-health: 344159e84756ba3b8e3b1adaef199b12c4081476
    task-cognitive-load: fe25257899b32d9e2a5641dbba4303573a562d93
  body_state: 9dda591c16ea3fda4c72fec7270d4b494b97617735567e19b9d8c92386093cfd
verified:
- by: human:maintainer
  source_state:
    task-health: 344159e84756ba3b8e3b1adaef199b12c4081476
    task-cognitive-load: fe25257899b32d9e2a5641dbba4303573a562d93
  body_state: 9dda591c16ea3fda4c72fec7270d4b494b97617735567e19b9d8c92386093cfd
---

# Tasks

TaskHealth and TaskCognitiveLoad compute task metrics.
