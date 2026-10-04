#!/usr/bin/env bash
# check-detekt-registrations.sh — validate the custom detekt rule registry.
#
# Why this exists (2026-09-28): two agents wired NoFactoryViewModel in parallel,
# each in its own branch. Both registrations were the same single line added in
# different places, so the diff looked harmless and review passed. The build then
# failed with `found duplicate key no-factory-viewmodel` — a duplicated YAML key
# is a hard error, not a silent override.
#
# Three invariants, all cheap to check and expensive to discover in CI:
#   1. No duplicate provider class in the ServiceLoader file.
#   2. No duplicate rule-set block in detekt.yml.
#   3. Every provider listed in the service file exists in the source, and every
#      rule file that declares a provider is listed.
#   4. (2026-10-05) Every ruleSetId declared in source has a top-level block in
#      detekt.yml. Invariants 1-3 all passed while two rules — no-direct-dispatchers
#      and user-scoped-repository — had no config block and had therefore never run.
#      A rule that is implemented, packaged and registered but absent from the config
#      is invisible to every other check in this script, so it needs its own.
#
# Usage: ./scripts/check-detekt-registrations.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE="$ROOT/detekt-rules/src/main/resources/META-INF/services/dev.detekt.api.RuleSetProvider"
YML="$ROOT/config/detekt/detekt.yml"
SRC="$ROOT/detekt-rules/src/main/kotlin"

ERRORS=0
err() { echo "ERROR: $*"; ERRORS=$((ERRORS + 1)); }

for f in "$SERVICE" "$YML"; do
    if [[ ! -f "$f" ]]; then
        err "missing $(realpath --relative-to="$ROOT" "$f" 2>/dev/null || echo "$f")"
        exit 1
    fi
done

# 1. No duplicate provider registrations.
dups=$(grep -vE '^\s*(#|$)' "$SERVICE" | sort | uniq -d)
if [[ -n "$dups" ]]; then
    while IFS= read -r line; do
        [[ -n "$line" ]] && err "duplicate provider in RuleSetProvider: $line"
    done <<< "$dups"
fi

# 2. No duplicate rule-set blocks in detekt.yml (top-level `name:` keys only).
ydups=$(grep -oE '^[a-z][a-z0-9-]+:' "$YML" | sort | uniq -d)
if [[ -n "$ydups" ]]; then
    while IFS= read -r key; do
        [[ -n "$key" ]] && err "duplicate rule-set key in detekt.yml: $key"
    done <<< "$ydups"
fi

# 3a. Every listed provider class exists in the source.
while IFS= read -r fqcn; do
    [[ -z "$fqcn" ]] && continue
    simple="${fqcn##*.}"
    if ! grep -rqE "class[[:space:]]+$simple\b" "$SRC" --include="*.kt"; then
        err "RuleSetProvider lists $fqcn but no such class exists in detekt-rules/src"
    fi
done < <(grep -vE '^\s*(#|$)' "$SERVICE")

# 3b. Every provider class declared in the source is listed.
#     The provider class name is NOT the file name (NoRunBlockingRule.kt declares
#     NoRunBlockingProvider), so read the declaration rather than deriving it.
while IFS= read -r kt; do
    pkg=$(grep -m1 '^package ' "$kt" | sed 's/^package //' | tr -d ' ' || true)
    [[ -z "$pkg" ]] && continue
    while IFS= read -r provider; do
        [[ -z "$provider" ]] && continue
        fqcn="$pkg.$provider"
        if ! grep -qxF "$fqcn" "$SERVICE"; then
            err "provider not registered: $fqcn (add it to META-INF/services/dev.detekt.api.RuleSetProvider)"
        fi
    done < <(grep -oE 'class[[:space:]]+[A-Za-z0-9_]+[[:space:]]*:[[:space:]]*RuleSetProvider' "$kt" \
             | grep -oE '[A-Za-z0-9_]+[[:space:]]*:' | tr -d ' :')
done < <(find "$SRC" -name "*.kt")

total=$(grep -cvE '^\s*(#|$)' "$SERVICE" || true)

# 4. Every ruleSetId declared in source has a top-level block in detekt.yml.
#    Without this, a rule can be implemented, registered and packaged and still
#    never execute, because detekt only loads rule sets present in the config.
while IFS= read -r rid; do
    if ! grep -qE "^${rid}:" "$YML"; then
        err "ruleSetId '$rid' is declared in detekt-rules/src but has no block in config/detekt/detekt.yml — the rule will never run"
    fi
done < <(grep -rhoE 'RuleSetId\("[a-z0-9-]+"\)' "$SRC" --include="*.kt" \
         | grep -oE '"[a-z0-9-]+"' | tr -d '"' | sort -u)

if ((ERRORS > 0)); then
    echo ""
    echo "check-detekt-registrations.sh: $ERRORS error(s) ($total registered provider(s))"
    exit 1
fi
echo "check-detekt-registrations.sh: OK — $total provider(s), no duplicates, all resolvable"
