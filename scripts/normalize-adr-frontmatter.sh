#!/usr/bin/env bash
# normalize-adr-frontmatter.sh — normalize YAML frontmatter in all ADR files.
# Adds status: accepted where missing, converts created: to date:, strips tag quotes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$SCRIPT_DIR/normalize-adr-frontmatter.py" "$@"
