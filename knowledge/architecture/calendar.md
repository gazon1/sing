---
type: Architecture
title: Calendar
description: Google client config and consent for calendar sync.

canonical_for:
- K-calendar-001

sources:
- id: client-config
  resource: 
    repo:/shared/src/androidMain/kotlin/com/singularity/todo/feature/calendarSync/auth/GoogleClientIdConfig.android.kt
generated:
- by: agent
  source_state:
    client-config: ff9318aa73a43951076ba4bf2a0f18331c39684e
  body_state: a9627c569b1d44f3caa86a609783f43ccf06dacecc76f74012ca9f552e50440b
verified:
- by: human:maintainer
  source_state:
    client-config: ff9318aa73a43951076ba4bf2a0f18331c39684e
  body_state: a9627c569b1d44f3caa86a609783f43ccf06dacecc76f74012ca9f552e50440b
---

# Calendar

GoogleClientIdConfig provides OAuth client id for calendar sync.
