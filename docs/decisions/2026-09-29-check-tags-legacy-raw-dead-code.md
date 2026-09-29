---
title: "check-tags.sh LEGACY_RAW and ALLOW_PATTERNS are documentation-only"
date: 2026-09-29
status: accepted
tags: [maestro, testing, tech-debt]
---

## Context

The `check-tags.sh` script defines two arrays that appear to configure tag validation:

```bash
ALLOW_PATTERNS=(
    'task_item_[a-z0-9_]+'
    ...
)
LEGACY_RAW=(
    'projects_notification_host'
    ...
)
```

Both arrays are **defined but never used** in the validation loop (lines 110–227). The loop only checks the skip-list (lines 116–221), which duplicates some of the same values as raw strings.

## Decision

Keep the arrays as **documentation only** rather than deleting or wiring them in.

The arrays serve a purpose even without programmatic enforcement:

- **ALLOW_PATTERNS** documents the regex patterns that correspond to dynamic `TestTags` functions (`task_item_<slug>`, `nav_tab_<slug>`, etc.). A reader can see at a glance which ID patterns are intentionally dynamic.
- **LEGACY_RAW** documents the raw-string testTags that existed before the `TestTags.kt` contract was introduced. It makes the "legacy tolerance" policy explicit.

The skip-list is the authoritative validation mechanism. Duplicating those values into `LEGACY_RAW` and then checking the array would be pure machinery with no benefit.

## Consequences

- The arrays must be manually kept in sync with the skip-list if a new legacy ID is added. This is low risk: both are trivially grep-able.
- A future refactor could wire `LEGACY_RAW` into the loop to eliminate the duplication, but the ROI is near zero.
- The script's output message ("add to TestTags.kt or LEGACY_RAW") is slightly misleading — LEGACY_RAW is checked only by human review, not by the code. The message should be updated to say "add to TestTags.kt or the skip-list" if the arrays are kept as documentation-only and not wired in.

## Links

- `Maestro/scripts/check-tags.sh` — source of truth for this decision
- Related: `2026-09-29-no-direct-clock-system-detekt-rule.md`
