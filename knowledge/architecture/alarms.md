---
type: Architecture
title: Alarms
description: Alarm receiver.

canonical_for:
- K-alarms-001

sources:
- id: alarm-receiver
  resource: 
    repo:/shared/src/androidMain/kotlin/com/singularity/todo/feature/alarms/AlarmReceiver.kt
generated:
- by: agent
  source_state:
    alarm-receiver: a0d41d1d951bb0783804537b00b611c7fbaa61a0
  body_state: 2318eabbcd91db9f4982b97e3a420f48e0785b0c17f442d83bdd9470b7958071
verified:
- by: human:maintainer
  source_state:
    alarm-receiver: a0d41d1d951bb0783804537b00b611c7fbaa61a0
  body_state: 2318eabbcd91db9f4982b97e3a420f48e0785b0c17f442d83bdd9470b7958071
---

# Alarms

AlarmReceiver handles alarm broadcasts.
