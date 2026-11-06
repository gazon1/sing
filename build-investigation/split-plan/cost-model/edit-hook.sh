#!/usr/bin/env bash
# cycle.sh's EDIT_HOOK for the stand: `apply` swaps one expression inside a function body, `revert`
# restores the generated file. No signature, visibility or call site changes — the same body-only edit
# the :shared cycles use, expressed as file content because the stand has no patch to apply.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "${1:?apply|revert}" in
  apply)  python3 "$HERE/generate.py" --edit >/dev/null ;;
  revert) python3 "$HERE/generate.py" --revert >/dev/null ;;
  *) echo "usage: $(basename "$0") apply|revert" >&2; exit 2 ;;
esac
