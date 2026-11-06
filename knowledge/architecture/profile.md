---
type: Architecture
title: Profile
description: Profile repository port.

canonical_for:
- K-profile-001

sources:
- id: profile-repo
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/feature/profile/domain/port/ProfileRepository.kt
generated:
- by: agent
  source_state:
    profile-repo: f740674f8167269d482a35bb344f9627c9d6b2d3
  body_state: 09db8cd85ef66982b837f9556c1b526ccad9a2d1494f72bd5e2d11008612254c
verified:
- by: human:maintainer
  source_state:
    profile-repo: f740674f8167269d482a35bb344f9627c9d6b2d3
  body_state: 09db8cd85ef66982b837f9556c1b526ccad9a2d1494f72bd5e2d11008612254c
---

# Profile

ProfileRepository port defines profile access.
