---
type: Architecture
title: Notifications
description: Notification permission requester.

canonical_for:
- K-notif-001

sources:
- id: permission-requester
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/core/notifications/NotificationPermissionRequester.kt
generated:
- by: agent
  source_state:
    permission-requester: 9d716515e8a5fdc7f7e139515532658649f96e84
  body_state: 26749194bd39b503f8c6668f5fb15b90994928c8d208b4568be0304f242e0364
verified:
- by: human:maintainer
  source_state:
    permission-requester: 9d716515e8a5fdc7f7e139515532658649f96e84
  body_state: 26749194bd39b503f8c6668f5fb15b90994928c8d208b4568be0304f242e0364
---

# Notifications

NotificationPermissionRequester requests notification permission.
