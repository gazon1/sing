---
title: Context
date: 2026-09-29
status: open
description: check-tags.sh ALLOW_PATTERNS array is dead code; migrate validation to iterate it
owner: singularity-dev
last_updated: 2026-09-29
labels: maestro, test-automation, technical-debt
---

# Context

`Maestro/scripts/check-tags.sh` validates every `id:` selector in YAML flows against a
hardcoded allow-list in the main validation loop (lines ~126-162). A `ALLOW_PATTERNS`
bash array is defined (lines ~81-110) containing the same dynamic patterns as regex strings
(`task_item_[a-z0-9_]+`, `calendar_day_[0-9_]+`, `genui_[a-z0-9_]+`, etc.) but it is
**never iterated** — the array is dead code.

When PR3 adds `calendar_day_*` and PR4 adds `genui_*` ids, these will be reported as
"unknown" because they are not in the hardcoded inline checks, even though they are
documented in `ALLOW_PATTERNS`.

# Idea

Migrate the validation loop to iterate `ALLOW_PATTERNS` using bash regex (`=~`) instead
of glob (`==`), so the single array is the source of truth for all dynamic patterns.

# Decision

Deferred to PR3. PR3's single mechanical pass should also refactor check-tags.sh to
iterate `ALLOW_PATTERNS`.

Migration approach (for PR3):

```bash
# Build a combined regex from ALLOW_PATTERNS
REGEX=$(printf '%s\n' "${ALLOW_PATTERNS[@]}" | paste -sd'|' | sed 's/+/\+/g')

# Validate: id must match at least one pattern OR be in LEGACY_RAW
matched=
for pattern in "${LEGACY_RAW[@]}"; do
    [[ "$id" == "$pattern" ]] && matched=1 && break
done
[[ "$id" =~ ^($REGEX)$ ]] && matched=1

if [[ -z "$matched" ]]; then
    UNKNOWN+=("$id")
fi
```

Alternatively, move ID validation entirely to Python (same interpreter as extraction)
to avoid bash regex quoting issues.

# Rationale

- `ALLOW_PATTERNS` already documents the correct dynamic patterns — the code should
  use it rather than maintain two parallel lists.
- Bash `=~` regex handles `+` (one-or-more) correctly; glob `==` does not.
- Python validation would share the same extraction parser, keeping the script
  internally consistent.

# Consequences

- PR3 must update check-tags.sh as part of the flow migration pass.
- genui ids added in PR4 will fail check-tags until PR3 fix lands.
  - Mitigation: `genui_*` ids can be added to LEGACY_RAW as a short-term allow-list
    before PR3 lands.
- `calendar_day_*` ids added in PR3 (calendar month-grid cell testTags) will also
  need LEGACY_RAW or the ALLOW_PATTERNS fix.

# Links

- Plan PR1: TestTags contract
- Plan PR3: Flow migration
- Plan PR4: New E2E flows
