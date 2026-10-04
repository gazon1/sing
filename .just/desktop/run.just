# ==============================================================================
# 🖥️ DESKTOP RUN
# ==============================================================================

[group('run')]
[doc('Run the desktop app (headed — requires X server)')]
[working-directory(justfile_directory())]
run:
    #!/bin/bash
    set -euo pipefail
    ./gw :desktopApp:run

[group('run')]
[doc('Run the desktop app headless via Xvfb (no display required)')]
[working-directory(justfile_directory())]
run-headless:
    #!/bin/bash
    set -euo pipefail
    xvfb-run -a ./gw :desktopApp:run

[group('run')]
[doc('Print PID of running desktop app (empty if not running)')]
[working-directory(justfile_directory())]
app-pid:
    #!/bin/bash
    set -euo pipefail
    pgrep -f singularity-todo || echo "(no desktop process)"
