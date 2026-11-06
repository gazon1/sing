# ==============================================================================
# 🤖 ANDROID BUILD
# ==============================================================================

[group('build')]
[doc('Build Android: VARIANT=debug|release (default debug)')]
[working-directory(justfile_directory())]
build VARIANT="debug":
    #!/bin/bash
    set -euo pipefail
    case "{{VARIANT}}" in
        debug)
            if command -v heavy >/dev/null 2>&1 && [[ -z "${CI:-}" ]]; then
                ./gw :androidApp:assembleDebug
            else
                ./gw :androidApp:assembleDebug
            fi
            echo "✅ androidApp/build/outputs/apk/debug/androidApp-debug.apk"
            ;;
        release)
            if command -v heavy >/dev/null 2>&1 && [[ -z "${CI:-}" ]]; then
                ./gw :androidApp:assembleRelease
            else
                ./gw :androidApp:assembleRelease
            fi
            echo "✅ androidApp/build/outputs/apk/release/androidApp-release.apk"
            ;;
        *)
            echo "❌ Unknown variant: {{VARIANT}} (use debug | release)"
            exit 1
            ;;
    esac

[group('build')]
[doc('Build Android debug APK (shortcut)')]
[private]
[working-directory(justfile_directory())]
build-debug:
    #!/bin/bash
    set -euo pipefail
    just android::build debug

[group('build')]
[doc('Build Android release APK (shortcut)')]
[private]
[working-directory(justfile_directory())]
build-release:
    #!/bin/bash
    set -euo pipefail
    just android::build release
