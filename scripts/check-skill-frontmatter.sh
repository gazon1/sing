#!/usr/bin/env bash
# check-skill-frontmatter.sh
# Validates that every SKILL.md in .agents/skills/ has a YAML frontmatter block
# with required keys: name, description.
# Files without frontmatter are skipped.
# Usage: ./scripts/check-skill-frontmatter.sh
set -euo pipefail

SKILLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.agents/skills"
REQUIRED_KEYS=("name" "description")
ERRORS=0
SKIPPED=0
CHECKED=0

while IFS= read -r -d '' file; do
    # Skip files without frontmatter start
    if ! head -1 "$file" | grep -qP '^---$'; then
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    ((CHECKED++)) || true
    local_errors=0

    # Extract frontmatter block (between first --- and next ---)
    frontmatter=$(awk '/^---$/ && !first { first=1; next } first && /^---$/ { exit } first' "$file")
    if [[ -z "$frontmatter" ]]; then
        echo "ERROR: $file has empty or missing frontmatter block"
        ERRORS=$((ERRORS + 1))
        local_errors=$((local_errors + 1))
    else
        for key in "${REQUIRED_KEYS[@]}"; do
            if ! echo "$frontmatter" | grep -qP "^${key}:"; then
                echo "ERROR: $file missing required frontmatter key '$key'"
                ERRORS=$((ERRORS + 1))
                local_errors=$((local_errors + 1))
            fi
        done
    fi

done < <(find "$SKILLS_DIR" -name "SKILL.md" -print0 2>/dev/null)

total=$(find "$SKILLS_DIR" -name "SKILL.md" 2>/dev/null | wc -l)

if ((ERRORS > 0)); then
    echo ""
    echo "check-skill-frontmatter.sh: $ERRORS error(s) found ($CHECKED checked, $SKIPPED skipped, $total total)"
    exit 1
else
    echo "check-skill-frontmatter.sh: All $CHECKED skill files have valid frontmatter ($SKIPPED skipped, $total total)"
    exit 0
fi
