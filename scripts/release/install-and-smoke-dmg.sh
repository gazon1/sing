#!/usr/bin/env bash
# install-and-smoke-dmg.sh — attach a Singularity DMG and run the packaged app.
#
# #664 (follow-up): the Linux install-and-smoke.sh proved packages by installing them
# inside a throwaway container. macOS has no lightweight container equivalent for DMG
# images, so this runs directly on the macos-15 runner (a transient VM).
#
# The DMG is attached via hdiutil, the .app bundle is copied to /Applications,
# and the app is exercised through its --version flag — which exits 0 and prints
# the version string before Koin, before the database, before any AppKit call.
#
# Assertions (in increasing order of what they prove):
#   1. hdiutil attaches the DMG without error;
#   2. the .app bundle exists under /Applications;
#   3. the .app runs --version and exits 0;
#   4. the reported version matches the tag.
#
# Usage (from release.yml, running on macos-15 runner):
#   DMG_PATH=/tmp/smoke/singularity-1.2.3-macos-x64.dmg \
#   EXPECT_VERSION=1.2.3 \
#   bash scripts/release/install-and-smoke-dmg.sh

set -euo pipefail

: "${DMG_PATH:?DMG_PATH is the path to the built DMG inside the runner}"
: "${EXPECT_VERSION:?EXPECT_VERSION is the version the tag named}"

app_name="Singularity Todo"
bundle_id="com.singularity.todo"

# Resolve to absolute path
case "$DMG_PATH" in
    /*) ;;
    *) DMG_PATH="$PWD/$DMG_PATH" ;;
esac

if [[ ! -f "$DMG_PATH" ]]; then
    echo "::error::DMG not found at: $DMG_PATH" >&2
    exit 1
fi

echo "Attaching DMG: $DMG_PATH"
# hdiutil attach returns 0 on success. Mount point is printed to stdout.
# /tmp is used as a temporary mount point.
dmg_mount=$(hdiutil attach "$DMG_PATH" -mountpoint /tmp/singularity-dmg -nobrowse 2>&1)
attach_status=$?
echo "hdiutil output: $dmg_mount"
if (( attach_status != 0 )); then
    echo "::error::hdiutil attach failed with exit code $attach_status" >&2
    exit 1
fi

# Find the .app bundle inside the mounted DMG.
# jpackage creates a .app with the product name from Info.plist.
found_app=$(find /tmp/singularity-dmg -maxdepth 2 -type d -name "*.app" 2>/dev/null | head -n1 || true)
if [[ -z "$found_app" ]]; then
    echo "::error::No .app bundle found inside the mounted DMG at /tmp/singularity-dmg" >&2
    hdiutil detach /tmp/singularity-dmg 2>/dev/null || true
    exit 1
fi
echo "Found app bundle: $found_app"

# Copy the .app to /Applications, replacing any existing installation.
if [[ -d "/Applications/$app_name.app" ]]; then
    echo "Removing existing installation..."
    rm -rf "/Applications/$app_name.app"
fi
echo "Copying $found_app to /Applications/"
cp -R "$found_app" "/Applications/$app_name.app"

# Detach the DMG — no longer needed.
hdiutil detach /tmp/singularity-dmg 2>&1 || true

# Find the executable inside the .app bundle.
# jpackage bundles the JRE under Contents/PlugIn or Contents/Helpers, and the actual
# launcher is Contents/MacOS/<appname>.
executable="/Applications/$app_name.app/Contents/MacOS/$app_name"
if [[ ! -x "$executable" ]]; then
    echo "::error::Executable not found or not executable: $executable" >&2
    ls -la "/Applications/$app_name.app/Contents/MacOS/" 2>/dev/null || true
    exit 1
fi

echo "Executable: $executable ($(stat -f '%z' "$executable" 2>/dev/null || stat -c '%y' "$executable" 2>/dev/null))"

# Run --version. Must exit 0 before any Koin/database/AppKit call.
echo "Running: $executable --version"
output=$("$executable" --version 2>&1)
status=$?

if (( status != 0 )); then
    echo "::error::$executable --version exited $status. Output: $output" >&2
    exit 1
fi

echo "Launcher output: $output"

if [[ "$output" != *"$EXPECT_VERSION"* ]]; then
    echo "::error::Version mismatch. Tag: $EXPECT_VERSION, reported: $output" >&2
    exit 1
fi

echo "install-and-smoke OK (DMG $EXPECT_VERSION)"
exit 0
