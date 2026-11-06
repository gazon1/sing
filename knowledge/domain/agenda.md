---
type: Domain
title: Agenda Evaluation
description: How agenda buckets are computed.

canonical_for:
- K-agenda-001

sources:
- id: agenda-evaluator
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaEvaluator.kt
generated:
- by: agent
  source_state:
    agenda-evaluator: 30a8c187d81deca2343446297d7260c6cd0ae1d4
  body_state: b3cf20eba44ec808fa2e89bdb396933cab8eeeede46834743dfe46acc78fe4af
verified:
- by: human:maintainer
  source_state:
    agenda-evaluator: 30a8c187d81deca2343446297d7260c6cd0ae1d4
  body_state: b3cf20eba44ec808fa2e89bdb396933cab8eeeede46834743dfe46acc78fe4af
---

# Agenda

AgendaEvaluator computes relative buckets for tasks.
