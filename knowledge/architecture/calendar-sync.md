---
type: Architecture
title: Calendar Sync
description: Google calendar sync consent and client config.

canonical_for:
- K-calsync-001

sources:
- id: consent
  resource: 
    repo:/shared/src/androidMain/kotlin/com/singularity/todo/feature/calendarSync/auth/GoogleConsent.android.kt
generated:
- by: agent
  source_state:
    consent: 78f1df11f357a6ca8c7545deb8ee11b6a5766638
  body_state: 47dead65ee4883fbf1bf0effb7096f263be443004403fecedb8a13a623677b19
verified:
- by: human:maintainer
  source_state:
    consent: 78f1df11f357a6ca8c7545deb8ee11b6a5766638
  body_state: 47dead65ee4883fbf1bf0effb7096f263be443004403fecedb8a13a623677b19
---

# Calendar Sync

GoogleConsent handles OAuth consent for calendar sync.
