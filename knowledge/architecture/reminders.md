---
type: Architecture
title: Reminders and Notifications
description: Reminder scheduling and notification delivery.

canonical_for:
- K-reminders-001

sources:
- id: reminders-dir
  resource: 
    repo:/shared/src/androidMain/kotlin/com/singularity/todo/feature/reminders/AlarmManagerReminderScheduler.kt
generated:
- by: agent
  source_state:
    reminders-dir: 5add99811b0193bbe84efc0c2338b869a80a314c
  body_state: a5ea126ef192397f0d0415e4239067525a90661c9b7135cb8c415c6527688ebd
verified:
- by: human:maintainer
  source_state:
    reminders-dir: 5add99811b0193bbe84efc0c2338b869a80a314c
  body_state: a5ea126ef192397f0d0415e4239067525a90661c9b7135cb8c415c6527688ebd
---

# Reminders

ReminderScheduler schedules and fires reminders.
