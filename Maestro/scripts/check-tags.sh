#!/usr/bin/env bash
#
# check-tags.sh — validates every `id:` selector in Maestro YAML flows against
# the TestTags.kt registry.
#
# Status: legacy. The canonical blocking check is
# `shared/src/jvmTest/.../arch/MaestroFlowTagsTest.kt` (JVM, runs as part of
# :shared:jvmTest). This shell script is retained for ad-hoc local use and
# pre-commit hook scenarios where gradle is unavailable.
#
# Every id used in a flow must either:
#   1. Equal a `const val` literal declared in TestTags.kt
#      (e.g. `settings_dark_theme_switch`)
#   2. Start with the literal prefix of a TestTags.kt dynamic function
#      (e.g. `nav_tab_<slug>` from `fun navTab(title) = "nav_tab_${slug(title)}"`)
#   3. Appear in LEGACY_RAW below, for tags predating the TestTags migration
#
# The valid set is DERIVED FROM TestTags.kt at run time, never hand-maintained
# here. See docs/decisions/2026-10-01-maestro-flow-tag-contract.md.
#
# Run from repo root:
#   bash Maestro/scripts/check-tags.sh
#
# Exit codes: 0 = all ids known, 1 = unknown id, 2 = registry unreadable
#             (a vacuous green is worse than a red — see MIN_IDS below).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
YAML_DIR="$REPO_ROOT/Maestro/flows"
HELPERS_DIR="$REPO_ROOT/Maestro/helpers"
TESTTAGS_FILE="$REPO_ROOT/shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt"

# If fewer ids are collected than this, the collector itself is broken and a
# zero-violation result would be meaningless. Mirrors the positive control in
# scripts/build-version-catalog-gate.py.
MIN_IDS=20

# Tags that exist in the UI but predate the TestTags.kt migration. Each one is
# debt: either wire it to a TestTags constant or delete the flow that needs it.
# Keep this list short — a growing list is the regression this script exists to
# prevent.
#
# Currently empty: every id in every flow is derivable from TestTags.kt. The
# three entries that used to live here (projects/chat/archive_notification_host)
# turned out to be plain `const val`s, and the fourth
# (pomodoro_play_pause_button) was a flow asserting a tag the UI never renders.
# Add an entry only when a tag genuinely predates the migration.
LEGACY_RAW=''

# ── 1. Collect all id: selectors from all YAML files ────────────────────────
#
# Python handles multiline YAML values correctly where line-oriented grep fails.
# A multiline `visible:\n    id: foo` produces one logical line from grep -o but
# Python's regex over the raw text gives the correct token.
IDS=$(
    python3 - "$YAML_DIR" "$HELPERS_DIR" <<'PYEOF'
import os
import re
import sys

_, yaml_dir, helpers_dir = sys.argv

pattern = re.compile(r'^\s+id:\s*"?([^"#\s]+)"?\s*$', re.MULTILINE)

ids = set()


def process_path(path):
    if not os.path.isdir(path):
        return
    for root, _, files in os.walk(path):
        for fname in files:
            if not fname.endswith(('.yaml', '.yml')):
                continue
            fpath = os.path.join(root, fname)
            try:
                with open(fpath, 'r', encoding='utf-8') as f:
                    content = f.read()
            except FileNotFoundError:
                continue
            for m in pattern.finditer(content):
                val = m.group(1)
                if val and not val.startswith('${'):
                    ids.add(val)


for path in (yaml_dir, helpers_dir):
    process_path(path)

for i in sorted(ids):
    print(i)
PYEOF
)

if [[ -z "$IDS" ]]; then
    echo "ERROR: no id: selectors found — the collector is broken, not the flows." >&2
    exit 2
fi

# ── 2. Derive the valid set from TestTags.kt ────────────────────────────────
#
# Emits two newline-separated lists: exact literals first, then dynamic
# prefixes. A dynamic function whose body is a bare "${PREFIX}..." (profileItem)
# yields an EMPTY literal prefix; accepting "" would make startswith() true for
# every id and the whole gate vacuous, so those are dropped and the prefix is
# instead recovered from the *_PREFIX constant it references.
readarray -t REGISTRY < <(
    python3 - "$TESTTAGS_FILE" <<'PYEOF'
import re
import sys

path = sys.argv[1]
try:
    with open(path, 'r', encoding='utf-8') as f:
        src = f.read()
except FileNotFoundError:
    sys.stderr.write(f"ERROR: registry not found at {path}\n")
    sys.exit(2)

literals = set(re.findall(r'const val \w+\s*(?::\s*String\s*)?=\s*"([^"]+)"', src))

# name -> value, so a function body that interpolates a *_PREFIX constant can
# be resolved back to its literal prefix.
const_by_name = dict(
    re.findall(r'const val (\w+)\s*(?::\s*String\s*)?=\s*"([^"]+)"', src)
)

prefixes = set()
for m in re.finditer(r'fun \w+\s*\([^)]*\)\s*(?::\s*String\s*)?=\s*"([^"]+)"', src):
    body = m.group(1)
    if '${' not in body:
        continue
    prefix = body.split('${')[0]
    if prefix:
        prefixes.add(prefix)
        continue
    # Body starts with an interpolation, e.g. "${PROFILE_ITEM_PREFIX}${slug(name)}".
    # The declaration lives elsewhere in the file, so resolve by referenced name.
    for name in re.findall(r'\$\{(\w+)\}', body):
        if name in const_by_name:
            prefixes.add(const_by_name[name])
        else:
            sys.stderr.write(
                f"WARNING: {path} references undeclared constant {name}\n"
            )

# Positive control: an empty prefix would accept every id.
assert '' not in prefixes, "empty prefix derived from TestTags.kt — gate would be vacuous"

if not literals and not prefixes:
    sys.stderr.write("ERROR: derived 0 literals and 0 prefixes from TestTags.kt\n")
    sys.exit(2)

for lit in sorted(literals):
    print(lit)
for pre in sorted(prefixes):
    print(f"{pre}*")
PYEOF
) || exit 2

if [[ ${#REGISTRY[@]} -eq 0 ]]; then
    echo "ERROR: derived an empty valid-set from $TESTTAGS_FILE — gate would pass everything." >&2
    exit 2
fi

# ── 3. Check each id ────────────────────────────────────────────────────────
UNKNOWN=()
ID_COUNT=0
for id in $IDS; do
    ID_COUNT=$((ID_COUNT + 1))

    if [[ "$id" == *'\${'* ]]; then
        continue
    fi

    KNOWN=false
    for entry in "${REGISTRY[@]}"; do
        if [[ "$entry" == *'*' ]]; then
            # Dynamic prefix: strip the trailing '*' marker.
            if [[ "$id" == "${entry%\*}"* ]]; then
                KNOWN=true
                break
            fi
        elif [[ "$id" == "$entry" ]]; then
            KNOWN=true
            break
        fi
    done

    if [[ "$KNOWN" == false ]]; then
        # Last resort: the explicit legacy list.
        while IFS= read -r legacy; do
            [[ -z "$legacy" ]] && continue
            if [[ "$id" == "$legacy" ]]; then
                KNOWN=true
                break
            fi
        done <<<"$LEGACY_RAW"
    fi

    if [[ "$KNOWN" == false ]]; then
        UNKNOWN+=("$id")
    fi
done

if [[ "$ID_COUNT" -lt "$MIN_IDS" ]]; then
    echo "ERROR: only $ID_COUNT id selectors collected (expected >= $MIN_IDS)." >&2
    echo "The collector likely broke; a clean run here would be vacuous." >&2
    exit 2
fi

# ── 4. Report ────────────────────────────────────────────────────────────────
if [[ ${#UNKNOWN[@]} -eq 0 ]]; then
    echo "All $ID_COUNT id selectors are known (${#REGISTRY[@]} entries derived from TestTags.kt)."
    exit 0
fi

echo "Unknown id: selectors (${#UNKNOWN[@]}/${ID_COUNT}) — not derivable from TestTags.kt:"
printf '  - %s\n' "${UNKNOWN[@]}"
echo ""
echo "If this is a real testTag, add it to TestTags.kt as a const val or a"
echo "dynamic function. If it predates the migration, add it to LEGACY_RAW in"
echo "this script — but that list is debt and should shrink, not grow."
exit 1
