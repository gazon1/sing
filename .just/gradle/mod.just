# ==============================================================================
# 🧱 GRADLE — daemon hygiene and build slots
#
# The memory budget of a worktree is the sum of the heaps it holds: the Gradle
# daemon, the Kotlin daemon (unless the compilation runs in-process) and the
# forked test JVMs. These recipes are the operator's view of that budget.
# ==============================================================================

[group('gradle')]
[doc('Stop the Gradle daemons of every worktree (private homes only, no network)')]
[working-directory(justfile_directory())]
stop-all dry_run="":
    #!/bin/bash
    set -euo pipefail
    # The shared ~/.gradle is never a target: a daemon there belongs to
    # whoever is running a build right now. Only worktree-private homes are
    # stopped, and only with a Gradle that is already on disk — the wrapper
    # would try to download the distribution, and "stop the daemons" must not
    # be an operation that reaches the network.
    if [ "{{dry_run}}" = "--dry-run" ]; then
        ./scripts/gradle-stop-all.sh --dry-run
    else
        ./scripts/gradle-stop-all.sh --force
    fi

[group('gradle')]
[doc('Show build-slot occupancy, daemon count and JVM RSS')]
[working-directory(justfile_directory())]
status:
    #!/bin/bash
    set -euo pipefail
    ./scripts/gradle-slot.sh --status
