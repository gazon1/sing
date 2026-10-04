#!/usr/bin/env bash
# check-skill-frontmatter.sh
# Validates that every SKILL.md in .agents/skills/ has a YAML frontmatter block
# that actually parses, with non-empty `name` and `description` values.
#
# Why this parses instead of grepping (2026-10-05): the previous version grepped for
# `^name:` / `^description:` and reported all 112 skills valid, while 15 of them were
# unparseable YAML. They failed on an unquoted ": " inside the description value
# (e.g. `description: Use for X: the Y case`), so the key was present but the
# document did not parse — every downstream loader silently dropped the metadata.
# Checking key presence cannot detect that class of defect; only parsing can.
#
# Usage: ./scripts/check-skill-frontmatter.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$ROOT/scripts/check_skill_frontmatter.py" "$@"
