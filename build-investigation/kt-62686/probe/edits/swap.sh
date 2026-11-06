#!/usr/bin/env bash
# The probe's body-only edit, as cycle.sh's EDIT_HOOK: `apply` writes the edited source, `revert` the
# original. The probe is a standalone build, so it has no .patch to `git apply` — this is why the hook
# exists. Both files are byte-identical to what the ADR's probe used.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_file="$here/../probe/src/commonMain/kotlin/probe/Probe.kt"

case "${1:?apply|revert}" in
  apply)  cp "$here/Probe.edited.txt" "$source_file" ;;
  revert) cp "$here/Probe.original.txt" "$source_file" ;;
  *) echo "usage: $(basename "$0") apply|revert" >&2; exit 2 ;;
esac
