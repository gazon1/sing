# ==============================================================================
# 🖥️ DESKTOP BUILD
# ==============================================================================

[group('build')]
[doc('Build Desktop: TARGET=run|dist|deb (default run)')]
[working-directory(justfile_directory())]
build TARGET="run":
    #!/bin/bash
    set -euo pipefail
    case "{{TARGET}}" in
        run)
            if command -v heavy >/dev/null 2>&1 && [[ -z "${CI:-}" ]]; then
                ./gw :desktopApp:run
            else
                ./gw :desktopApp:run
            fi
            ;;
        dist)
            if command -v heavy >/dev/null 2>&1 && [[ -z "${CI:-}" ]]; then
                ./gw :desktopApp:createDistributable
            else
                ./gw :desktopApp:createDistributable
            fi
            echo "✅ desktopApp/build/compose/binaries/main/app/"
            ;;
        deb)
            if command -v heavy >/dev/null 2>&1 && [[ -z "${CI:-}" ]]; then
                ./gw :desktopApp:packageDeb
            else
                ./gw :desktopApp:packageDeb
            fi
            echo "✅ desktopApp/build/compose/binaries/main/deb/singularity-todo_0.1.0_amd64.deb"
            ;;
        *)
            echo "❌ Unknown target: {{TARGET}} (use run | dist | deb)"
            exit 1
            ;;
    esac

[group('build')]
[doc('Build Desktop run (shortcut)')]
[private]
[working-directory(justfile_directory())]
build-run:
    #!/bin/bash
    set -euo pipefail
    just desktop::build run

[group('build')]
[doc('Build Desktop distributable (shortcut)')]
[private]
[working-directory(justfile_directory())]
build-dist:
    #!/bin/bash
    set -euo pipefail
    just desktop::build dist

[group('build')]
[doc('Build Desktop deb package (shortcut)')]
[private]
[working-directory(justfile_directory())]
build-deb:
    #!/bin/bash
    set -euo pipefail
    just desktop::build deb

[group('build')]
[doc('Install the .deb package (run after desktop::build deb)')]
[working-directory(justfile_directory())]
install-deb:
    #!/bin/bash
    set -euo pipefail
    sudo dpkg -i "${PWD}/{{DEB_FILE}}"
