---
type: Architecture
title: Search
description: Saved search and internal link repositories.

canonical_for:
- K-search-001

sources:
- id: saved-search-repo
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/feature/search/data/SavedSearchRepositoryImpl.kt
generated:
- by: agent
  source_state:
    saved-search-repo: 74e86621ee607592dc9f5fb0b048aba29963515d
  body_state: ec021da94cd00027e8754087f1bd9c9222f80f106121645a9df08c0d626c3639
verified:
- by: human:maintainer
  source_state:
    saved-search-repo: 74e86621ee607592dc9f5fb0b048aba29963515d
  body_state: ec021da94cd00027e8754087f1bd9c9222f80f106121645a9df08c0d626c3639
---

# Search

SavedSearchRepositoryImpl persists saved searches.
