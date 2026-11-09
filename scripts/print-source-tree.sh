#!/usr/bin/env bash
# print-source-tree.sh — emit core/ and feature/ package trees as markdown tables.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$SCRIPT_DIR/print-source-tree.py" "$@"
