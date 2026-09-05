#!/usr/bin/env bash
# refresh-decisions-digest.sh — rebuild docs/decisions/DIGEST.md from the
# dated entries in docs/decisions/*.md.
#
# Idempotent. Safe to run any time — exits 0 even when nothing changed.
# Run before starting any non-trivial agent task; run after adding new entries.

set -euo pipefail

# Resolve project root from this script's location, regardless of cwd.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DECISIONS_DIR="$PROJECT_ROOT/docs/decisions"
DIGEST="$DECISIONS_DIR/DIGEST.md"

if [[ ! -d "$DECISIONS_DIR" ]]; then
    echo "error: $DECISIONS_DIR does not exist" >&2
    exit 1
fi

# Collect dated entries (anything matching YYYY-MM-DD-*.md, excluding the digest).
mapfile -t ENTRIES < <(
    find "$DECISIONS_DIR" -mindepth 1 -maxdepth 1 -type f \
        -regextype posix-extended -regex '.*/[0-9]{4}-[0-9]{2}-[0-9]{2}-[^/]+\.md' \
        | sort
)

if [[ ${#ENTRIES[@]} -eq 0 ]]; then
    echo "no dated entries in $DECISIONS_DIR — digest untouched"
    exit 0
fi

# Collect superseded slugs so we can skip their Consequences.
SUPERSEDED=()
for entry in "${ENTRIES[@]}"; do
    sup=$(awk '
        BEGIN { in_fm = 0 }
        /^---$/ { in_fm = !in_fm; next }
        in_fm && /^supersedes:[[:space:]]*/ { sub(/^supersedes:[[:space:]]*/, ""); print; exit }
    ' "$entry" || true)
    if [[ -n "$sup" ]]; then
        SUPERSEDED+=("$sup")
    fi
done

# Render each entry's Consequences section as a bullet list, grouped by tag.
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

for entry in "${ENTRIES[@]}"; do
    slug="$(basename "$entry" .md)"
    # Skip if this entry is itself marked as superseded.
    for sup in "${SUPERSEDED[@]:-}"; do
        # Look up the slug of the entry that does the superseding.
        superseder_slug="$(basename "$sup" .md)"
        if [[ "$slug" == "$superseder_slug" ]]; then
            # $slug is superseded — skip its Consequences from the digest.
            continue 2
        fi
    done

    # Pull title from frontmatter.
    title=$(awk '
        BEGIN { in_fm = 0 }
        /^---$/ { in_fm = !in_fm; next }
        in_fm && /^title:[[:space:]]*/ { sub(/^title:[[:space:]]*/, ""); print; exit }
    ' "$entry")
    [[ -z "$title" ]] && title="$slug"

    # Pull the Consequences section (between `## Consequences` and the next
    # `## ` or EOF). Take only lines that begin with `-` or `*` after
    # optional whitespace — this skips any fenced-code-block contents
    # naturally because code-block lines don't start with a bullet.
    awk '
        /^## Consequences/ { in_c = 1; next }
        in_c && /^## / { in_c = 0; next }
        in_c && /^[[:space:]]*[-*][[:space:]]+/ {
            sub(/^[[:space:]]*[-*][[:space:]]+/, "")
            sub(/^[[:space:]]*\[[ xX]\][[:space:]]+/, "")
            if (NF > 0) print
        }
    ' "$entry" | sort -u | {
        # Print heading + bullets for this entry.
        printf "\n### %s\n" "$slug"
        cat
    } >> "$TMP"
done

# Render digest.
{
    echo "# Decision Log Digest"
    echo
    echo "Auto-generated consolidated rules from \`docs/decisions/\`. The agent"
    echo "reads this at session start. Per-decision entries (\`docs/decisions/YYYY-MM-DD-*.md\`)"
    echo "are the human-facing reasoning. Refresh with:"
    echo
    echo '```bash'
    echo "./scripts/refresh-decisions-digest.sh"
    echo '```'
    echo
    echo "Each bullet below is a rule the agent must honour. Entries that have"
    echo "been superseded (see frontmatter \`supersedes:\`) are excluded."
    echo
    echo "## Rules"
    echo
    if [[ -s "$TMP" ]]; then
        cat "$TMP"
    else
        echo "_No active consequences — every decision's `supersedes:` covers it._"
    fi
    echo
    echo "## Active entries"
    echo
    for entry in "${ENTRIES[@]}"; do
        slug="$(basename "$entry" .md)"
        title=$(awk '
            BEGIN { in_fm = 0 }
            /^---$/ { in_fm = !in_fm; next }
            in_fm && /^title:[[:space:]]*/ { sub(/^title:[[:space:]]*/, ""); print; exit }
        ' "$entry")
        [[ -z "$title" ]] && title="$slug"
        printf -- "- \`%s\` — %s\n" "$slug" "$title"
    done
} > "$DIGEST"

echo "refreshed $DIGEST ($(wc -l < "$DIGEST") lines, ${#ENTRIES[@]} entries)"